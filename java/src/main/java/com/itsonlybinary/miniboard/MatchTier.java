package com.itsonlybinary.miniboard;

/**
 * How strongly a COM port is believed to be a MiniBoard, based solely on USB
 * metadata. No port is opened to establish these, so none of them is proof —
 * identity is confirmed only by a successful {@code MiniBoard.connect()}.
 *
 * <p>Declared best-first; {@link #compareTo} therefore ranks correctly.
 */
public enum MatchTier {

    /** Vendor ID 0x2E8A, product ID 0x104E, and "MiniBoard" present in the device metadata. */
    CONFIRMED,

    /**
     * Vendor ID 0x2E8A and product ID 0x104E, but no MiniBoard token found. 0x2E8A is
     * Raspberry Pi Trading's registered vendor ID and is shared across many RP2040-class
     * boards, so this is strong but not conclusive. It also covers the common case where
     * the inbox usbser.sys driver overwrote the device description with its own INF text,
     * hiding the USB product string.
     */
    PROBABLE,

    /** No matching USB metadata. Included so every port is listed. */
    UNKNOWN
}
