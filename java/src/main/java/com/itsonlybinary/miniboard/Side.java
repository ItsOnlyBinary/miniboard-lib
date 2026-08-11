package com.itsonlybinary.miniboard;

/** Which USB connector is active. Bitmask, so both sides can be live at once. */
public enum Side {

    NONE(0x00),
    LEFT(0x01),
    RIGHT(0x02),
    BOTH(0x03);

    private final int value;

    Side(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public boolean isLeftActive() {
        return (value & 0x01) != 0;
    }

    public boolean isRightActive() {
        return (value & 0x02) != 0;
    }

    /** @return the matching side, or {@code null} if unrecognised. */
    public static Side fromValue(int value) {
        for (Side s : values()) {
            if (s.value == (value & 0xFF)) {
                return s;
            }
        }
        return null;
    }
}
