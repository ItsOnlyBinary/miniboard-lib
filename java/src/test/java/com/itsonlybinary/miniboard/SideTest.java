package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideTest {

    @Test
    void bothReportsLeftAndRightActive() {
        assertEquals(Side.BOTH, Side.fromValue(0x03));
        assertTrue(Side.BOTH.isLeftActive());
        assertTrue(Side.BOTH.isRightActive());
    }

    @Test
    void leftAndRightReportOnlyTheirOwnFlag() {
        assertTrue(Side.LEFT.isLeftActive());
        assertFalse(Side.LEFT.isRightActive());
        assertTrue(Side.RIGHT.isRightActive());
        assertFalse(Side.RIGHT.isLeftActive());
    }

    @Test
    void noneReportsNeitherFlag() {
        assertFalse(Side.NONE.isLeftActive());
        assertFalse(Side.NONE.isRightActive());
    }

    @Test
    void unrecognisedValueReturnsNullRatherThanThrowing() {
        // Newer firmware must degrade gracefully, not blow up the reader thread.
        assertNull(Side.fromValue(0x07));
        assertNull(Side.fromValue(0xFF));
    }

    @Test
    void everySideRoundTripsThroughItsWireValue() {
        for (Side s : Side.values()) {
            assertEquals(s, Side.fromValue(s.getValue()));
        }
    }
}
