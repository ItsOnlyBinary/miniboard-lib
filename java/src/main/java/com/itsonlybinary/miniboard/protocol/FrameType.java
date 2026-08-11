package com.itsonlybinary.miniboard.protocol;

/**
 * The three frame shapes on the wire. They differ structurally: only
 * {@link #RESPONSE} carries a STATUS byte.
 */
public enum FrameType {
    /** Host to device: SOF, CMD, LEN, DATA, CRC. */
    REQUEST,
    /** Device to host, replying to a request: SOF, CMD, STATUS, LEN, DATA, CRC. */
    RESPONSE,
    /** Device to host, unsolicited: SOF, EVT, LEN, DATA, CRC. */
    EVENT
}
