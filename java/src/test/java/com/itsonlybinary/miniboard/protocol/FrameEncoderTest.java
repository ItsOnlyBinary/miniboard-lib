package com.itsonlybinary.miniboard.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link FrameEncoder} produces the documented byte sequences and
 * rejects payloads that do not match the opcode's declared request length.
 * Expected byte sequences are taken from the task brief and cross-checked
 * against {@link Crc8Test}'s independently derived CRC vectors.
 */
class FrameEncoderTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    @Test
    void encodesGetDebounceRequestWithNoPayload() {
        Frame req = FrameEncoder.encodeRequest(Opcode.GET_DEBOUNCE, null);
        assertArrayEquals(b(0xAA, 0x82, 0x00, 0x4B), req.getRawBytes());
    }

    @Test
    void encodesGetLedRequestWithIndexPayload() {
        Frame req = FrameEncoder.encodeRequest(Opcode.GET_LED, b(0x01));
        assertArrayEquals(b(0xAA, 0x8A, 0x01, 0x01, 0xB5), req.getRawBytes());
    }

    @Test
    void rejectsPayloadOfWrongLengthForOpcode() {
        // SET_KEY declares an 8-byte request; a 1-byte payload must be rejected
        // rather than sent, so a malformed request never reaches the wire.
        assertThrows(IllegalArgumentException.class,
                () -> FrameEncoder.encodeRequest(Opcode.SET_KEY, b(0x00)));
    }
}
