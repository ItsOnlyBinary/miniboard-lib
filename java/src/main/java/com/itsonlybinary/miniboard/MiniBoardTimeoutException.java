package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Opcode;

/** No response arrived within the command's timeout. */
public class MiniBoardTimeoutException extends MiniBoardException {

    private static final long serialVersionUID = 1L;

    private final Opcode opcode;
    private final int timeoutMs;

    public MiniBoardTimeoutException(Opcode opcode, int timeoutMs) {
        super((opcode != null ? opcode.name() : "command")
                + " timed out after " + timeoutMs + " ms");
        this.opcode = opcode;
        this.timeoutMs = timeoutMs;
    }

    public Opcode getOpcode() {
        return opcode;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
