package com.itsonlybinary.miniboard;

/** What an LED does once a finite-iteration animation completes. */
public enum LedFinal {

    OFF(0),
    ON(1),
    /** Restore the LED state from before the animation was applied. */
    RESTORE(2);

    private final int value;

    LedFinal(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static LedFinal fromValue(int value) {
        for (LedFinal f : values()) {
            if (f.value == (value & 0xFF)) {
                return f;
            }
        }
        return null;
    }
}
