package com.itsonlybinary.miniboard.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FrameDecoder} against every behaviour the raw log and the
 * command pipeline depend on: whole-frame decode, arbitrary split reads,
 * multi-frame chunks, garbage coalescing, CRC-failure recovery, unknown
 * opcode rejection, the maximum payload size, and stale-partial flushing.
 *
 * <p>Byte vectors are taken from the task brief and cross-checked against
 * {@link Crc8Test}'s independently derived CRC values.
 */
class FrameDecoderTest {

    private final List<Frame> frames = new ArrayList<Frame>();
    private final List<String> errors = new ArrayList<String>();

    private final FrameDecoder.Sink sink = new FrameDecoder.Sink() {
        @Override
        public void onFrame(Frame frame) {
            frames.add(frame);
        }

        @Override
        public void onError(FrameError reason, byte[] discarded) {
            errors.add(reason + ":" + hex(discarded));
        }
    };

    private FrameDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new FrameDecoder(sink);
        frames.clear();
        errors.clear();
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format(Locale.ROOT, "%02X", x & 0xFF));
        }
        return sb.toString();
    }

    private void feed(byte[] data) {
        decoder.feed(data, 0, data.length, System.currentTimeMillis());
    }

    @Test
    void decodesWholeResponseFrame() {
        feed(b(0xAA, 0x82, 0x00, 0x01, 0x32, 0x47));

        assertEquals(1, frames.size());
        assertTrue(errors.isEmpty(), "errors=" + errors);

        Frame f = frames.get(0);
        assertEquals(FrameType.RESPONSE, f.getType());
        assertEquals(Opcode.GET_DEBOUNCE, f.getCommand());
        assertEquals(Status.OK, f.getStatus());
        assertEquals(1, f.getDataLength());
        assertEquals(0x32, f.getDataByte(0));
        assertArrayEquals(b(0xAA, 0x82, 0x00, 0x01, 0x32, 0x47), f.getRawBytes());
    }

    @Test
    void decodesKeyEventWithoutMisreadingItAsAResponse() {
        // Events carry no STATUS byte; misreading one as a response would
        // shift every field after the opcode.
        feed(b(0xAA, 0x10, 0x01, 0x00, 0x9C));

        assertEquals(1, frames.size());
        assertTrue(errors.isEmpty(), "errors=" + errors);
        Frame f = frames.get(0);
        assertEquals(EventType.KEY, f.getEvent());
        assertEquals(FrameType.EVENT, f.getType());
        assertEquals(0x00, f.getDataByte(0));
    }

    @Test
    void decodesFrameSplitAcrossReadsAtEveryPossibleByteBoundary() {
        byte[] whole = b(0xAA, 0x82, 0x00, 0x01, 0x32, 0x47);
        for (int split = 1; split < whole.length; split++) {
            decoder.reset();
            frames.clear();
            errors.clear();

            byte[] a = new byte[split];
            byte[] c = new byte[whole.length - split];
            System.arraycopy(whole, 0, a, 0, split);
            System.arraycopy(whole, split, c, 0, c.length);

            feed(a);
            feed(c);

            assertEquals(1, frames.size(),
                    "split at byte " + split + ": frames=" + frames.size() + " errors=" + errors);
            assertTrue(errors.isEmpty(),
                    "split at byte " + split + ": errors=" + errors);
        }
    }

    @Test
    void decodesTwoFramesDeliveredInOneChunk() {
        feed(b(0xAA, 0x82, 0x00, 0x01, 0x32, 0x47,
                0xAA, 0x10, 0x01, 0x00, 0x9C));

        assertEquals(2, frames.size());
        assertTrue(errors.isEmpty(), "errors=" + errors);
    }

    @Test
    void coalescesLeadingGarbageIntoOneErrorAndStillRecoversFrame() {
        feed(b(0x11, 0x22, 0x33, 0xAA, 0x82, 0x00, 0x01, 0x32, 0x47));

        assertEquals(1, frames.size(), "frame should still be recovered after garbage");
        assertEquals(1, errors.size(), "the three garbage bytes must coalesce into one error");
        assertEquals("BAD_SOF:112233", errors.get(0));
    }

    @Test
    void crcFailureConsumesWholeCandidateFrameAndRecoversTheNextOne() {
        // A single-byte drop here would chew through the payload emitting a
        // cascade of BAD_SOF errors; the whole candidate must be consumed instead.
        feed(b(0xAA, 0x82, 0x00, 0x01, 0x32, 0xFF,
                0xAA, 0x10, 0x01, 0x00, 0x9C));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("CRC_MISMATCH:"), "" + errors);
        assertEquals(1, frames.size());
        assertEquals(EventType.KEY, frames.get(0).getEvent());
    }

    @Test
    void rejectsUnknownOpcodeRatherThanMisparsingIt() {
        // 0xA0 falls outside both the event range (0x01-0x7F) and the command
        // range (0x80-0x9F).
        feed(b(0xAA, 0xA0, 0x00, 0x00));

        assertTrue(frames.isEmpty());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).startsWith("UNKNOWN_OPCODE:"), "" + errors);
    }

    @Test
    void decodesMaxLength255BytePayload() {
        byte[] big = new byte[4 + 255];
        big[0] = (byte) 0xAA;
        big[1] = 0x13; // MSG event
        big[2] = (byte) 255;
        for (int i = 0; i < 255; i++) {
            big[3 + i] = (byte) i;
        }
        big[big.length - 1] = (byte) Crc8.compute(big, 1, big.length - 2);

        feed(big);

        assertEquals(1, frames.size());
        assertEquals(255, frames.get(0).getDataLength());
        assertTrue(errors.isEmpty(), "errors=" + errors);
    }

    @Test
    void flushesStalePartialFrameAsTimeoutPartial() {
        decoder.feed(b(0xAA, 0x82, 0x00), 0, 3, 1000L);
        decoder.checkStale(3000L, 1000L);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("TIMEOUT_PARTIAL:"), "" + errors);
    }
}
