package com.itsonlybinary.miniboard.protocol;

/** Why the decoder rejected bytes. Surfaced to the raw log so corruption is visible. */
public enum FrameError {

    /** A byte was encountered where a 0xAA start-of-frame was expected. */
    BAD_SOF,

    /** Opcode fell outside both the command (0x80-0x9F) and event (0x01-0x09) ranges. */
    UNKNOWN_OPCODE,

    /** A structurally complete frame failed its CRC check. */
    CRC_MISMATCH,

    /** A partial frame went stale with no further bytes arriving. */
    TIMEOUT_PARTIAL
}
