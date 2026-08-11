package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyMappingTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    // Spec's worked example: index 0, key_id 0, key 0x04 -> SET_KEY payload
    // 00 00 04 00 00 00 00 00
    @Test
    void setKeyPayloadMatchesSpecWorkedExample() {
        KeyMapping km = new KeyMapping(0, 0, 0x04);
        assertArrayEquals(b(0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00), km.toRequestData());
    }

    @Test
    void roundTripsThroughRequestAndResponseEncoding() {
        KeyMapping km = new KeyMapping(0, 7, 0x04);
        KeyMapping rt = KeyMapping.fromResponse(km.toRequestData());
        assertEquals(km, rt);
        assertEquals(7, rt.getKeyId());
    }

    @Test
    void activeCountReflectsNonZeroSlots() {
        KeyMapping km = new KeyMapping(0, 0, 0x04);
        assertEquals(1, km.getActiveCount());
    }

    @Test
    void indexOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeyMapping(54, 0));
    }

    @Test
    void keyIdOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeyMapping(0, 256));
        assertThrows(IllegalArgumentException.class, () -> new KeyMapping(0, -1));
    }

    @Test
    void moreThanSixKeyCodesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new KeyMapping(0, 0, 1, 2, 3, 4, 5, 6, 7));
    }

    /**
     * A malformed device payload and a bad caller argument are different faults
     * and must not share a vocabulary: a consumer branching on
     * {@code MiniBoardException} in {@code onError} would otherwise silently
     * mishandle every {@code getKey}/{@code setKey} decode failure, since these
     * reach it through exactly the same future.
     */
    @Test
    void aMalformedResponseIsAMiniBoardExceptionNotAnIllegalArgument() {
        assertThrows(MiniBoardException.class, () -> KeyMapping.fromResponse(null));
        assertThrows(MiniBoardException.class, () -> KeyMapping.fromResponse(b(0, 1, 2)));
        assertThrows(MiniBoardException.class,
                () -> KeyMapping.fromResponse(b(54, 0, 0, 0, 0, 0, 0, 0)));
    }

    /** Caller-supplied arguments keep IllegalArgumentException; only decoding moved. */
    @Test
    void callerArgumentsStillRaiseIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new KeyMapping(54, 0, 0x04));
    }

    @Test
    void acceptsTheValidMaximaAtEveryBoundary() {
        KeyMapping max = new KeyMapping(53, 255, 255, 255, 255, 255, 255, 255);
        assertEquals(53, max.getIndex());
        assertEquals(255, max.getKeyId());
        assertEquals(255, max.getKeyCode(5));
        assertEquals(6, max.getActiveCount());
    }
}
