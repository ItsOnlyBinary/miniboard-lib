package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedConfigTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    // Spec's worked example: index=1 mode=TRANSITION final=OFF r=FF g=00 b=00
    // iterations=1 duration=100ms -> SET_LED payload 01 01 00 FF 00 00 01 64 00
    @Test
    void setLedPayloadMatchesSpecWorkedExample() {
        LedConfig led = new LedConfig(1, LedMode.TRANSITION, LedFinal.OFF, 0xFF, 0, 0, 1, 100);
        assertArrayEquals(
                b(0x01, 0x01, 0x00, 0xFF, 0x00, 0x00, 0x01, 0x64, 0x00),
                led.toRequestData());
    }

    @Test
    void durationMsIsEncodedLittleEndian() {
        LedConfig led = new LedConfig(1, LedMode.TRANSITION, LedFinal.OFF, 0xFF, 0, 0, 1, 100);
        byte[] p = led.toRequestData();
        // duration 100 == 0x0064 must encode low byte first: 64 00
        assertEquals(0x64, p[7] & 0xFF);
        assertEquals(0x00, p[8] & 0xFF);
    }

    @Test
    void roundTripsDuration100ThroughFromResponse() {
        LedConfig led = new LedConfig(1, LedMode.TRANSITION, LedFinal.OFF, 0xFF, 0, 0, 1, 100);
        // Grow the 9-byte request to the 13-byte response shape; Arrays.copyOf
        // zero-fills the new trailing bytes (cur_r/cur_g/cur_b/flags = 0).
        byte[] response = Arrays.copyOf(led.toRequestData(), LedConfig.RESPONSE_SIZE);
        LedConfig back = LedConfig.fromResponse(response);
        assertEquals(100, back.getDurationMs());
    }

    // Asymmetric value catches a byte-swap bug that 0x0064 alone would hide,
    // since its high byte is zero.
    @Test
    void durationMs0x1234EncodesLowByteFirst() {
        LedConfig wide = new LedConfig(0, LedMode.PULSE, LedFinal.ON, 1, 2, 3, 0, 0x1234);
        byte[] wp = wide.toRequestData();
        assertEquals(0x34, wp[7] & 0xFF);
        assertEquals(0x12, wp[8] & 0xFF);
    }

    @Test
    void durationMs0x1234RoundTripsThroughFromResponse() {
        LedConfig wide = new LedConfig(0, LedMode.PULSE, LedFinal.ON, 1, 2, 3, 0, 0x1234);
        byte[] response = Arrays.copyOf(wide.toRequestData(), LedConfig.RESPONSE_SIZE);
        assertEquals(0x1234, LedConfig.fromResponse(response).getDurationMs());
    }

    @Test
    void decodesLiveColourAndOverlayFlagFromResponse() {
        LedConfig led = LedConfig.fromResponse(
                b(2, 1, 0, 0xFF, 0x00, 0x00, 1, 0x64, 0x00, 0x80, 0x40, 0x20, 0x01));
        assertEquals(0x80, led.getCurRed());
        assertEquals(0x40, led.getCurGreen());
        assertEquals(0x20, led.getCurBlue());
        assertTrue(led.isOverlayActive());
    }

    @Test
    void overlayFlagBitZeroMeansNoOverlay() {
        LedConfig led = LedConfig.fromResponse(
                b(2, 1, 0, 0xFF, 0x00, 0x00, 1, 0x64, 0x00, 0x80, 0x40, 0x20, 0x00));
        assertFalse(led.isOverlayActive());
    }

    // flags = 0x02 has bit 1 set and bit 0 clear: catches a hypothetical regression
    // to "flags != 0" semantics instead of the correct (flags & 0x01) != 0 bitmask.
    // Also getFlags()'s first direct assertion anywhere in the suite.
    @Test
    void nonBitZeroFlagsDoesNotCountAsOverlayActive() {
        LedConfig led = LedConfig.fromResponse(
                b(2, 1, 0, 0xFF, 0x00, 0x00, 1, 0x64, 0x00, 0x80, 0x40, 0x20, 0x02));
        assertEquals(0x02, led.getFlags());
        assertFalse(led.isOverlayActive());
    }

    @Test
    void ledIndexOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedConfig(4, LedMode.SOLID, LedFinal.OFF, 0, 0, 0, 0, 0));
    }

    @Test
    void redOutOfByteRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedConfig(0, LedMode.SOLID, LedFinal.OFF, 256, 0, 0, 0, 0));
    }

    /** @see KeyMappingTest#aMalformedResponseIsAMiniBoardExceptionNotAnIllegalArgument */
    @Test
    void aMalformedResponseIsAMiniBoardExceptionNotAnIllegalArgument() {
        assertThrows(MiniBoardException.class, () -> LedConfig.fromResponse(null));
        assertThrows(MiniBoardException.class, () -> LedConfig.fromResponse(b(0, 0, 0)));
        assertThrows(MiniBoardException.class,
                () -> LedConfig.fromResponse(b(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
    }

    /** Caller-supplied arguments keep IllegalArgumentException; only decoding moved. */
    @Test
    void callerArgumentsStillRaiseIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedConfig(0, null, LedFinal.OFF, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LedConfig(0, LedMode.SOLID, null, 0, 0, 0, 0, 0));
    }

    /**
     * The library's stated policy is graceful degradation: {@code LedMode.fromValue}
     * and {@code LedFinal.fromValue} return null for a value they do not know
     * rather than throwing, and {@code getLed()} must not contradict them.
     * Firmware that adds a mode 4 has to leave every existing consumer working.
     */
    @Test
    void anUnknownModeOrFinalStateDegradesInsteadOfFailing() {
        LedConfig led = LedConfig.fromResponse(b(1, 0x07, 0x09, 10, 20, 30, 2, 0x2C, 0x01, 0, 0, 0, 0));

        assertNull(led.getMode(), "an unrecognised mode must degrade to null, not throw");
        assertNull(led.getFinalState(), "an unrecognised final state must degrade to null");
        assertEquals(0x07, led.getRawMode());
        assertEquals(0x09, led.getRawFinalState());

        // Everything the protocol does still define decodes normally.
        assertEquals(1, led.getIndex());
        assertEquals(300, led.getDurationMs());
        assertEquals(20, led.getGreen());
    }

    /** The unknown byte must survive a re-send, not be lost or replaced. */
    @Test
    void anUnknownModeIsReEmittedUnchangedByToRequestData() {
        LedConfig led = LedConfig.fromResponse(b(1, 0x07, 0x09, 10, 20, 30, 2, 0x2C, 0x01, 0, 0, 0, 0));
        assertArrayEquals(b(1, 0x07, 0x09, 10, 20, 30, 2, 0x2C, 0x01), led.toRequestData());
    }

    @Test
    void acceptsTheValidMaximaAtEveryBoundary() {
        LedConfig max = new LedConfig(3, LedMode.PULSE, LedFinal.RESTORE,
                255, 255, 255, 255, 65535);
        assertEquals(3, max.getIndex());
        assertEquals(65535, max.getDurationMs());
        byte[] wire = max.toRequestData();
        assertEquals(0xFF, wire[7] & 0xFF); // duration low byte
        assertEquals(0xFF, wire[8] & 0xFF); // duration high byte
    }
}
