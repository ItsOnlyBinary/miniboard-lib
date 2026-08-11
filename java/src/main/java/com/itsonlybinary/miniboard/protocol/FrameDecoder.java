package com.itsonlybinary.miniboard.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Incremental frame decoder. Accepts arbitrary-length byte chunks and emits
 * complete frames, plus notifications for any bytes it rejects.
 *
 * <p>Not thread-safe; feed from a single reader thread.
 */
public final class FrameDecoder {

    /** Receives decoder output. Implementations must not call {@link #feed} re-entrantly. */
    public interface Sink {
        void onFrame(Frame frame);

        void onError(FrameError reason, byte[] discarded);
    }

    /** Largest possible frame: SOF + CMD + STATUS + LEN + 255 data + CRC. */
    private static final int MAX_FRAME = 260;

    private final Sink sink;

    private byte[] buf = new byte[MAX_FRAME * 2];
    private int len;

    /** Timestamp of the most recent byte, for staleness detection. */
    private long lastByteMillis;

    /** Coalescing buffer for consecutive rejected bytes. */
    private final ByteArrayOutputStream garbage = new ByteArrayOutputStream();
    private FrameError garbageReason;

    public FrameDecoder(Sink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink is required");
        }
        this.sink = sink;
    }

    /** Discards all buffered state. Call on connect and on reconnect. */
    public void reset() {
        len = 0;
        garbage.reset();
        garbageReason = null;
    }

    /**
     * Feeds freshly read bytes and emits whatever becomes decodable.
     *
     * @param nowMillis timestamp of the read, used for staleness detection
     */
    public void feed(byte[] data, int offset, int length, long nowMillis) {
        if (length <= 0) {
            return;
        }
        lastByteMillis = nowMillis;
        ensureCapacity(len + length);
        System.arraycopy(data, offset, buf, len, length);
        len += length;
        scan();
        flushGarbage();
    }

    /**
     * Flushes a partial frame that has gone stale, so truncated input cannot wedge
     * the decoder indefinitely. Call periodically from the reader loop.
     *
     * @param nowMillis     current time
     * @param timeoutMillis how long a partial frame may sit unchanged
     */
    public void checkStale(long nowMillis, long timeoutMillis) {
        if (len > 0 && (nowMillis - lastByteMillis) > timeoutMillis) {
            byte[] stale = Arrays.copyOf(buf, len);
            len = 0;
            flushGarbage();
            sink.onError(FrameError.TIMEOUT_PARTIAL, stale);
        }
    }

    private void scan() {
        while (true) {
            if (len == 0) {
                return;
            }
            if ((buf[0] & 0xFF) != Frame.SOF) {
                discardOne(FrameError.BAD_SOF);
                continue;
            }
            if (len < 2) {
                return; // need the opcode
            }

            int opcode = buf[1] & 0xFF;
            int total;
            int dataStart;
            int dataLen;
            FrameType type;
            int rawStatus;

            if (Opcode.isCommandOpcode(opcode)) {
                // Response: SOF CMD STATUS LEN DATA CRC
                if (len < 4) {
                    return;
                }
                rawStatus = buf[2] & 0xFF;
                dataLen = buf[3] & 0xFF;
                dataStart = 4;
                total = 5 + dataLen;
                type = FrameType.RESPONSE;
            } else if (EventType.isEventOpcode(opcode)) {
                // Event: SOF EVT LEN DATA CRC - no status byte
                if (len < 3) {
                    return;
                }
                rawStatus = -1;
                dataLen = buf[2] & 0xFF;
                dataStart = 3;
                total = 4 + dataLen;
                type = FrameType.EVENT;
            } else {
                discardOne(FrameError.UNKNOWN_OPCODE);
                continue;
            }

            if (len < total) {
                return; // incomplete, wait for more bytes
            }

            int expected = Crc8.compute(buf, 1, total - 2);
            int received = buf[total - 1] & 0xFF;
            if (expected != received) {
                byte[] bad = Arrays.copyOf(buf, total);
                consume(total);
                flushGarbage();
                sink.onError(FrameError.CRC_MISMATCH, bad);
                continue;
            }

            byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart + dataLen);
            byte[] raw = Arrays.copyOf(buf, total);
            Status status = (type == FrameType.RESPONSE) ? Status.fromValue(rawStatus) : null;
            consume(total);
            flushGarbage();
            sink.onFrame(new Frame(type, opcode, status, rawStatus, payload, raw));
        }
    }

    private void discardOne(FrameError reason) {
        if (garbageReason != null && garbageReason != reason) {
            flushGarbage();
        }
        garbageReason = reason;
        garbage.write(buf[0] & 0xFF);
        consume(1);
    }

    private void flushGarbage() {
        if (garbage.size() > 0) {
            FrameError reason = garbageReason;
            byte[] bytes = garbage.toByteArray();
            garbage.reset();
            garbageReason = null;
            sink.onError(reason, bytes);
        }
    }

    private void consume(int count) {
        len -= count;
        if (len > 0) {
            System.arraycopy(buf, count, buf, 0, len);
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(needed, buf.length * 2));
        }
    }
}
