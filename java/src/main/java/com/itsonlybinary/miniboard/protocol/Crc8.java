package com.itsonlybinary.miniboard.protocol;

/**
 * CRC-8 as used by the mb_cdc protocol: polynomial 0x07, initial value 0xFF,
 * no input/output reflection, no final XOR.
 *
 * <p>The SOF byte (0xAA) is never included in the calculation.
 */
public final class Crc8 {

    /** Initial CRC register value. */
    public static final int INIT = 0xFF;

    /** Generator polynomial. */
    public static final int POLY = 0x07;

    private Crc8() {
    }

    /**
     * Folds one byte into a running CRC.
     *
     * @param crc current CRC value, 0-255
     * @param b   byte to fold in; only the low 8 bits are used
     * @return the updated CRC, 0-255
     */
    public static int update(int crc, int b) {
        crc = (crc ^ (b & 0xFF)) & 0xFF;
        for (int i = 0; i < 8; i++) {
            if ((crc & 0x80) != 0) {
                crc = ((crc << 1) ^ POLY) & 0xFF;
            } else {
                crc = (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /**
     * Computes the CRC over a byte range.
     *
     * @return the CRC, 0-255
     */
    public static int compute(byte[] data, int offset, int length) {
        int crc = INIT;
        for (int i = 0; i < length; i++) {
            crc = update(crc, data[offset + i]);
        }
        return crc;
    }

    /** Computes the CRC over an entire array. */
    public static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }
}
