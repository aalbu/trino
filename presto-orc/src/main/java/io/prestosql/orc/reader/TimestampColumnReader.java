/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.orc.reader;

import com.google.common.base.VerifyException;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.orc.OrcColumn;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.metadata.ColumnEncoding;
import io.prestosql.orc.metadata.ColumnMetadata;
import io.prestosql.orc.metadata.OrcType.OrcTypeKind;
import io.prestosql.orc.stream.BooleanInputStream;
import io.prestosql.orc.stream.InputStreamSource;
import io.prestosql.orc.stream.InputStreamSources;
import io.prestosql.orc.stream.LongInputStream;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.Int96ArrayBlock;
import io.prestosql.spi.block.LongArrayBlock;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.type.TimeZoneKey;
import io.prestosql.spi.type.Type;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.prestosql.orc.metadata.Stream.StreamKind.DATA;
import static io.prestosql.orc.metadata.Stream.StreamKind.PRESENT;
import static io.prestosql.orc.metadata.Stream.StreamKind.SECONDARY;
import static io.prestosql.orc.reader.ReaderUtils.invalidStreamType;
import static io.prestosql.orc.stream.MissingInputStreamSource.missingStreamSource;
import static io.prestosql.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MICROS;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_NANOS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_NANOS;
import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.prestosql.spi.type.Timestamps.NANOSECONDS_PER_MICROSECOND;
import static io.prestosql.spi.type.Timestamps.NANOSECONDS_PER_MILLISECOND;
import static io.prestosql.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static io.prestosql.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static io.prestosql.spi.type.Timestamps.roundDiv;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static org.joda.time.DateTimeConstants.MILLIS_PER_SECOND;

// see ORC encoding notes in TimestampColumnWriter

public class TimestampColumnReader
        implements ColumnReader
{
    private enum TimestampKind
    {
        TIMESTAMP_MILLIS,
        TIMESTAMP_MICROS,
        TIMESTAMP_NANOS,
        INSTANT_MILLIS,
        INSTANT_MICROS,
        INSTANT_NANOS,
    }

    private static final int[] POWERS_OF_TEN = {
            1,
            10,
            100,
            1_000,
            10_000,
            100_000,
            1_000_000,
            10_000_000,
            100_000_000,
    };

    private static final LocalDateTime ORC_EPOCH = LocalDateTime.of(2015, 1, 1, 0, 0, 0, 0);

    private static final long BASE_INSTANT_IN_SECONDS = ORC_EPOCH.toEpochSecond(ZoneOffset.UTC);

    private static final int INSTANCE_SIZE = ClassLayout.parseClass(TimestampColumnReader.class).instanceSize();

    private final Type type;
    private final OrcColumn column;
    private final TimestampKind timestampKind;

    private long baseTimestampInSeconds;
    private DateTimeZone fileDateTimeZone;

    private int readOffset;
    private int nextBatchSize;

    private InputStreamSource<BooleanInputStream> presentStreamSource = missingStreamSource(BooleanInputStream.class);
    @Nullable
    private BooleanInputStream presentStream;

    private InputStreamSource<LongInputStream> secondsStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream secondsStream;

    private InputStreamSource<LongInputStream> nanosStreamSource = missingStreamSource(LongInputStream.class);
    @Nullable
    private LongInputStream nanosStream;

    private boolean rowGroupOpen;

    private final LocalMemoryContext systemMemoryContext;

    public TimestampColumnReader(Type type, OrcColumn column, LocalMemoryContext systemMemoryContext)
            throws OrcCorruptionException
    {
        this.type = requireNonNull(type, "type is null");
        this.column = requireNonNull(column, "column is null");
        this.timestampKind = getTimestampKind(type, column);
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
    }

    private static TimestampKind getTimestampKind(Type type, OrcColumn column)
            throws OrcCorruptionException
    {
        if (type.equals(TIMESTAMP_MILLIS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP)) {
            return TimestampKind.TIMESTAMP_MILLIS;
        }
        if (type.equals(TIMESTAMP_MICROS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP)) {
            return TimestampKind.TIMESTAMP_MICROS;
        }
        if (type.equals(TIMESTAMP_NANOS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP)) {
            return TimestampKind.TIMESTAMP_NANOS;
        }
        if (type.equals(TIMESTAMP_TZ_MILLIS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP_INSTANT)) {
            return TimestampKind.INSTANT_MILLIS;
        }
        if (type.equals(TIMESTAMP_TZ_MICROS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP_INSTANT)) {
            return TimestampKind.INSTANT_MICROS;
        }
        if (type.equals(TIMESTAMP_TZ_NANOS) && (column.getColumnType() == OrcTypeKind.TIMESTAMP_INSTANT)) {
            return TimestampKind.INSTANT_NANOS;
        }
        throw invalidStreamType(column, type);
    }

    @Override
    public void prepareNextRead(int batchSize)
    {
        readOffset += nextBatchSize;
        nextBatchSize = batchSize;
    }

    @Override
    public Block readBlock()
            throws IOException
    {
        if (!rowGroupOpen) {
            openRowGroup();
        }

        if (readOffset > 0) {
            if (presentStream != null) {
                // skip ahead the present bit reader, but count the set bits
                // and use this as the skip size for the data reader
                readOffset = presentStream.countBitsSet(readOffset);
            }
            if (readOffset > 0) {
                verifyStreamsPresent();
                secondsStream.skip(readOffset);
                nanosStream.skip(readOffset);
            }
        }

        Block block;
        if (secondsStream == null && nanosStream == null) {
            if (presentStream == null) {
                throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is null but present stream is missing");
            }
            presentStream.skip(nextBatchSize);
            block = RunLengthEncodedBlock.create(type, null, nextBatchSize);
        }
        else if (presentStream == null) {
            block = readNonNullBlock();
        }
        else {
            boolean[] isNull = new boolean[nextBatchSize];
            int nullCount = presentStream.getUnsetBits(nextBatchSize, isNull);
            if (nullCount == 0) {
                block = readNonNullBlock();
            }
            else if (nullCount != nextBatchSize) {
                block = readNullBlock(isNull);
            }
            else {
                block = RunLengthEncodedBlock.create(type, null, nextBatchSize);
            }
        }

        readOffset = 0;
        nextBatchSize = 0;
        return block;
    }

    private void verifyStreamsPresent()
            throws OrcCorruptionException
    {
        if (secondsStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but seconds stream is missing");
        }
        if (nanosStream == null) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Value is not null but nanos stream is missing");
        }
    }

    private Block readNonNullBlock()
            throws IOException
    {
        verifyStreamsPresent();

        switch (timestampKind) {
            case TIMESTAMP_MILLIS:
                return readNonNullTimestampMillis();
            case TIMESTAMP_MICROS:
                return readNonNullTimestampMicros();
            case TIMESTAMP_NANOS:
                return readNonNullTimestampNanos();
            case INSTANT_MILLIS:
                return readNonNullInstantMillis();
            case INSTANT_MICROS:
                return readNonNullInstantMicros();
            case INSTANT_NANOS:
                return readNonNullInstantNanos();
        }
        throw new VerifyException("Unhandled timestmap kind: " + timestampKind);
    }

    private Block readNullBlock(boolean[] isNull)
            throws IOException
    {
        verifyStreamsPresent();

        switch (timestampKind) {
            case TIMESTAMP_MILLIS:
                return readNullTimestampMillis(isNull);
            case TIMESTAMP_MICROS:
                return readNullTimestampMicros(isNull);
            case TIMESTAMP_NANOS:
                return readNullTimestampNanos(isNull);
            case INSTANT_MILLIS:
                return readNullInstantMillis(isNull);
            case INSTANT_MICROS:
                return readNullInstantMicros(isNull);
            case INSTANT_NANOS:
                return readNullInstantNanos(isNull);
        }
        throw new VerifyException("Unhandled timestamp kind: " + timestampKind);
    }

    private void openRowGroup()
            throws IOException
    {
        presentStream = presentStreamSource.openStream();
        secondsStream = secondsStreamSource.openStream();
        nanosStream = nanosStreamSource.openStream();

        rowGroupOpen = true;
    }

    @Override
    public void startStripe(ZoneId fileTimeZone, InputStreamSources dictionaryStreamSources, ColumnMetadata<ColumnEncoding> encoding)
    {
        baseTimestampInSeconds = ZonedDateTime.ofLocal(ORC_EPOCH, fileTimeZone, null).toEpochSecond();

        fileDateTimeZone = DateTimeZone.forID(fileTimeZone.getId());

        presentStreamSource = missingStreamSource(BooleanInputStream.class);
        secondsStreamSource = missingStreamSource(LongInputStream.class);
        nanosStreamSource = missingStreamSource(LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        secondsStream = null;
        nanosStream = null;

        rowGroupOpen = false;
    }

    @Override
    public void startRowGroup(InputStreamSources dataStreamSources)
    {
        presentStreamSource = dataStreamSources.getInputStreamSource(column, PRESENT, BooleanInputStream.class);
        secondsStreamSource = dataStreamSources.getInputStreamSource(column, DATA, LongInputStream.class);
        nanosStreamSource = dataStreamSources.getInputStreamSource(column, SECONDARY, LongInputStream.class);

        readOffset = 0;
        nextBatchSize = 0;

        presentStream = null;
        secondsStream = null;
        nanosStream = null;

        rowGroupOpen = false;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .addValue(column)
                .toString();
    }

    @Override
    public void close()
    {
        systemMemoryContext.close();
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE;
    }

    @SuppressWarnings("ObjectEquality")
    private boolean isFileUtc()
    {
        return fileDateTimeZone == DateTimeZone.UTC;
    }

    private int decodeNanos(long serialized)
            throws IOException
    {
        // the last three bits encode the leading zeros removed minus one
        int zeros = (int) (serialized & 0b111);
        int nanos = (int) (serialized >>> 3);
        if (zeros > 0) {
            nanos *= POWERS_OF_TEN[zeros + 1];
        }
        if ((nanos < 0) || (nanos > 999_999_999)) {
            throw new OrcCorruptionException(column.getOrcDataSourceId(), "Nanos field of timestamp is out of range: %s", nanos);
        }
        return nanos;
    }

    // TIMESTAMP MILLIS

    private Block readNonNullTimestampMillis()
            throws IOException
    {
        long[] millis = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            millis[i] = readTimestampMillis();
        }
        return new LongArrayBlock(nextBatchSize, Optional.empty(), millis);
    }

    private Block readNullTimestampMillis(boolean[] isNull)
            throws IOException
    {
        long[] millis = new long[isNull.length];
        for (int i = 0; i < isNull.length; i++) {
            if (!isNull[i]) {
                millis[i] = readTimestampMillis();
            }
        }
        return new LongArrayBlock(isNull.length, Optional.of(isNull), millis);
    }

    private long readTimestampMillis()
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long millis = (seconds + baseTimestampInSeconds) * MILLIS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (millis < 0) {
                millis -= MILLIS_PER_SECOND;
            }

            millis += roundDiv(nanos, NANOSECONDS_PER_MILLISECOND);
        }

        if (!isFileUtc()) {
            millis = fileDateTimeZone.convertUTCToLocal(millis);
        }

        return millis * MICROSECONDS_PER_MILLISECOND;
    }

    // TIMESTAMP MICROS

    private Block readNonNullTimestampMicros()
            throws IOException
    {
        long[] micros = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            micros[i] = readTimestampMicros();
        }
        return new LongArrayBlock(nextBatchSize, Optional.empty(), micros);
    }

    private Block readNullTimestampMicros(boolean[] isNull)
            throws IOException
    {
        long[] micros = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            if (!isNull[i]) {
                micros[i] = readTimestampMicros();
            }
        }
        return new LongArrayBlock(nextBatchSize, Optional.of(isNull), micros);
    }

    private long readTimestampMicros()
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long micros = (seconds + baseTimestampInSeconds) * MICROSECONDS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (micros < 0) {
                micros -= MICROSECONDS_PER_SECOND;
            }

            micros += roundDiv(nanos, NANOSECONDS_PER_MICROSECOND);
        }

        if (!isFileUtc()) {
            long millis = floorDiv(micros, MICROSECONDS_PER_MILLISECOND);
            int microsFraction = floorMod(micros, MICROSECONDS_PER_MILLISECOND);
            millis = fileDateTimeZone.convertUTCToLocal(millis);
            micros = (millis * MICROSECONDS_PER_MILLISECOND) + microsFraction;
        }

        return micros;
    }

    // TIMESTAMP NANOS

    private Block readNonNullTimestampNanos()
            throws IOException
    {
        long[] microsValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            readTimestampNanos(i, microsValues, picosFractionValues);
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.empty(), microsValues, picosFractionValues);
    }

    private Block readNullTimestampNanos(boolean[] isNull)
            throws IOException
    {
        long[] microsValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            if (!isNull[i]) {
                readTimestampNanos(i, microsValues, picosFractionValues);
            }
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.of(isNull), microsValues, picosFractionValues);
    }

    private void readTimestampNanos(int i, long[] microsValues, int[] picosFractionValues)
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long micros = (seconds + baseTimestampInSeconds) * MICROSECONDS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);
        int picosFraction = 0;

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (micros < 0) {
                micros -= MICROSECONDS_PER_SECOND;
            }

            // move micros part out of nanos
            micros += nanos / NANOSECONDS_PER_MICROSECOND;
            nanos %= NANOSECONDS_PER_MICROSECOND;

            picosFraction = toIntExact(nanos * PICOSECONDS_PER_NANOSECOND);
        }

        if (!isFileUtc()) {
            long millis = floorDiv(micros, MICROSECONDS_PER_MILLISECOND);
            int microsFraction = floorMod(micros, MICROSECONDS_PER_MILLISECOND);
            millis = fileDateTimeZone.convertUTCToLocal(millis);
            micros = (millis * MICROSECONDS_PER_MILLISECOND) + microsFraction;
        }

        microsValues[i] = micros;
        picosFractionValues[i] = picosFraction;
    }

    // INSTANT MILLIS

    private Block readNonNullInstantMillis()
            throws IOException
    {
        long[] millis = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            millis[i] = readInstantMillis();
        }
        return new LongArrayBlock(nextBatchSize, Optional.empty(), millis);
    }

    private Block readNullInstantMillis(boolean[] isNull)
            throws IOException
    {
        long[] millis = new long[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            if (!isNull[i]) {
                millis[i] = readInstantMillis();
            }
        }
        return new LongArrayBlock(nextBatchSize, Optional.of(isNull), millis);
    }

    private long readInstantMillis()
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long millis = (seconds + BASE_INSTANT_IN_SECONDS) * MILLIS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (millis < 0) {
                millis -= MILLIS_PER_SECOND;
            }

            millis += roundDiv(nanos, NANOSECONDS_PER_MILLISECOND);
        }

        return packDateTimeWithZone(millis, TimeZoneKey.UTC_KEY);
    }

    // INSTANT MICROS

    private Block readNonNullInstantMicros()
            throws IOException
    {
        long[] millisValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            readInstantMicros(i, millisValues, picosFractionValues);
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.empty(), millisValues, picosFractionValues);
    }

    private Block readNullInstantMicros(boolean[] isNull)
            throws IOException
    {
        long[] millisValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            if (!isNull[i]) {
                readInstantMicros(i, millisValues, picosFractionValues);
            }
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.of(isNull), millisValues, picosFractionValues);
    }

    private void readInstantMicros(int i, long[] millisValues, int[] picosFractionValues)
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long millis = (seconds + BASE_INSTANT_IN_SECONDS) * MILLIS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);
        int picosFraction = 0;

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (millis < 0) {
                millis -= MILLIS_PER_SECOND;
            }

            // move millis part out of nanos
            millis += nanos / NANOSECONDS_PER_MILLISECOND;
            nanos %= NANOSECONDS_PER_MILLISECOND;

            // round nanos to micros and convert to picos
            picosFraction = toIntExact(roundDiv(nanos, NANOSECONDS_PER_MICROSECOND)) * PICOSECONDS_PER_MICROSECOND;
        }

        millisValues[i] = packDateTimeWithZone(millis, TimeZoneKey.UTC_KEY);
        picosFractionValues[i] = picosFraction;
    }

    // INSTANT NANOS

    private Block readNonNullInstantNanos()
            throws IOException
    {
        long[] millisValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            readInstantNanos(i, millisValues, picosFractionValues);
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.empty(), millisValues, picosFractionValues);
    }

    private Block readNullInstantNanos(boolean[] isNull)
            throws IOException
    {
        long[] millisValues = new long[nextBatchSize];
        int[] picosFractionValues = new int[nextBatchSize];
        for (int i = 0; i < nextBatchSize; i++) {
            if (!isNull[i]) {
                readInstantNanos(i, millisValues, picosFractionValues);
            }
        }
        return new Int96ArrayBlock(nextBatchSize, Optional.of(isNull), millisValues, picosFractionValues);
    }

    private void readInstantNanos(int i, long[] millisValues, int[] picosFractionValues)
            throws IOException
    {
        long seconds = secondsStream.next();
        long serializedNanos = nanosStream.next();

        long millis = (seconds + BASE_INSTANT_IN_SECONDS) * MILLIS_PER_SECOND;
        long nanos = decodeNanos(serializedNanos);
        int picosFraction = 0;

        if (nanos != 0) {
            // adjust for bad ORC rounding logic
            if (millis < 0) {
                millis -= MILLIS_PER_SECOND;
            }

            // move millis part out of nanos
            millis += nanos / NANOSECONDS_PER_MILLISECOND;
            nanos %= NANOSECONDS_PER_MILLISECOND;

            picosFraction = toIntExact(nanos * PICOSECONDS_PER_NANOSECOND);
        }

        millisValues[i] = packDateTimeWithZone(millis, TimeZoneKey.UTC_KEY);
        picosFractionValues[i] = picosFraction;
    }
}
