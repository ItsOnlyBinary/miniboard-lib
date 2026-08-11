package com.itsonlybinary.miniboard.protocol;

/** Unsolicited device-to-host event types, opcode range 0x01-0x09. */
public enum EventType {

    NAME(0x01),
    VERSION(0x02),
    SERIAL(0x03),
    SIDE(0x04),
    TYPE(0x05),
    KEY(0x06),
    TYPO_REJECTED(0x07),
    LED_FINISH(0x08),
    MSG(0x09);

    /** Lowest event opcode; used to demultiplex events from responses. */
    public static final int MIN_VALUE = 0x01;

    /** Highest event opcode. */
    public static final int MAX_VALUE = 0x09;

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
