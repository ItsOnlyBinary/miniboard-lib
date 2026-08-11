package com.itsonlybinary.miniboard.protocol;

/**
 * Unsolicited device-to-host event types, opcode range 0x01-0x7F: hello events
 * (sent once during the connection sequence) occupy 0x01-0x0F, runtime events
 * occupy 0x10-0x7F.
 */
public enum EventType {

    NAME(0x01),
    VERSION(0x02),
    SERIAL(0x03),
    SIDE(0x04),
    TYPE(0x05),
    STATUS(0x06),
    KEY(0x10),
    TYPO_REJECTED(0x11),
    LED_FINISH(0x12),
    MSG(0x13);

    /** Lowest event opcode; used to demultiplex events from responses. */
    public static final int MIN_VALUE = 0x01;

    /** Highest event opcode. */
    public static final int MAX_VALUE = 0x7F;

    private final int value;

    EventType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /** @return the matching event, or {@code null} if unrecognised. */
    public static EventType fromValue(int value) {
        for (EventType e : values()) {
            if (e.value == (value & 0xFF)) {
                return e;
            }
        }
        return null;
    }

    /** @return true if the opcode falls in the event range, known or not. */
    public static boolean isEventOpcode(int opcode) {
        int v = opcode & 0xFF;
        return v >= MIN_VALUE && v <= MAX_VALUE;
    }
}
