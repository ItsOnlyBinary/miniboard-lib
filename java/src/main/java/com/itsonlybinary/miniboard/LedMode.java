package com.itsonlybinary.miniboard;

/** LED animation mode. */
public enum LedMode {

    /**
     * No animation; the LED is driven straight to the target colour.
     * Renamed from {@code NONE} — the wire value (0) is unchanged.
     */
    SOLID(0),
    /** Smooth colour transition to the target. Always runs once, ignoring iterations. */
    TRANSITION(1),
    /** Alternating on/off blink. */
    BLINK(2),
    /** Sine-wave brightness pulse toward the target colour. */
    PULSE(3);

    private final int value;

    LedMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static LedMode fromValue(int value) {
        for (LedMode m : values()) {
            if (m.value == (value & 0xFF)) {
                return m;
            }
        }
        return null;
    }
}
