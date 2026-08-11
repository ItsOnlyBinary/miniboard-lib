package com.itsonlybinary.miniboard.protocol;

import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link Crc8} against vectors computed independently from the C
 * reference in mb_cdc_interface.md and cross-checked by hand. If any of
 * these fail, the CRC implementation is wrong, not the vector - do not
 * adjust an expected value to make a test pass.
 */
class Crc8Test {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    @Test
    void getDebounceRequest() {
        assertEquals(0x4B, Crc8.compute(b(0x82, 0x00)));
    }

    @Test
    void saveRequest() {
        assertEquals(0x23, Crc8.compute(b(0x91, 0x00)));
    }

    @Test
    void getLedRequest() {
        assertEquals(0xB5, Crc8.compute(b(0x8A, 0x01, 0x01)));
    }

    @Test
    void setHidDisableTempRequest() {
        assertEquals(0x90, Crc8.compute(b(0x90, 0x01, 0x01)));
    }

    @Test
    void getDebounceResponse() {
        assertEquals(0x47, Crc8.compute(b(0x82, 0x00, 0x01, 0x32)));
    }

    @Test
    void keyEvent() {
        assertEquals(0x43, Crc8.compute(b(0x06, 0x01, 0x00)));
    }

    @Test
    void nameEvent() throws UnsupportedEncodingException {
        byte[] name = "MiniBoard54".getBytes("US-ASCII");
        byte[] frame = new byte[2 + name.length];
        frame[0] = 0x01;
        frame[1] = (byte) name.length;
        System.arraycopy(name, 0, frame, 2, name.length);
        assertEquals(0x18, Crc8.compute(frame));
    }

    /**
     * Sign-extension guard: Java's byte is signed, so 0xFF as a byte is -1.
     * The brief's own throwaway verifier computed its "expected" value by
     * calling {@code Crc8.compute(b(0xFF))} itself, which is tautological -
     * it would pass even against a broken implementation. Instead this
     * asserts the actual value, hand-derived by tracing the CRC-8/poly-0x07
     * algorithm for a single 0xFF byte: XOR-ing 0xFF into the INIT register
     * of 0xFF yields 0x00, and shifting zero eight times (no high bit is
     * ever set, so the polynomial is never folded in) leaves it at 0x00.
     * That hand trace was cross-checked against the brief's independently
     * supplied "82 00 -> 0x4B" vector to confirm the algorithm was applied
     * correctly before trusting it here.
     */
    @Test
    void highBitByteDoesNotCorruptRegisterViaSignExtension() {
        assertEquals(0x00, Crc8.compute(b(0xFF)));
    }
}
