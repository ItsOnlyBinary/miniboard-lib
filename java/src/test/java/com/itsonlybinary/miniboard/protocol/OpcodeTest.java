package com.itsonlybinary.miniboard.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpcodeTest {

    /**
     * SAVE and RESET inline the literal 5000 because an enum constructor cannot
     * forward-reference FLASH_TIMEOUT_MS. This guards the two from drifting apart.
     */
    @Test
    void flashCommandsUseTheFlashTimeout() {
        assertEquals(Opcode.FLASH_TIMEOUT_MS, Opcode.SAVE.getTimeoutMs());
        assertEquals(Opcode.FLASH_TIMEOUT_MS, Opcode.RESET.getTimeoutMs());
    }

    @Test
    void nonFlashCommandsUseTheDefaultTimeout() {
        assertEquals(Opcode.DEFAULT_TIMEOUT_MS, Opcode.GET_DEBOUNCE.getTimeoutMs());
        assertEquals(Opcode.DEFAULT_TIMEOUT_MS, Opcode.REBOOT.getTimeoutMs());
    }

    @Test
    void opcodeRangesDoNotOverlapEventRanges() {
        // The decoder demultiplexes responses from events purely by opcode range,
        // so an overlap would make the two frame shapes indistinguishable.
        for (Opcode o : Opcode.values()) {
            assertEquals(true, Opcode.isCommandOpcode(o.getValue()),
                    o + " must be in the command range");
            assertEquals(false, EventType.isEventOpcode(o.getValue()),
                    o + " must not collide with the event range");
        }
        for (EventType e : EventType.values()) {
            assertEquals(true, EventType.isEventOpcode(e.getValue()),
                    e + " must be in the event range");
            assertEquals(false, Opcode.isCommandOpcode(e.getValue()),
                    e + " must not collide with the command range");
        }
    }
}
