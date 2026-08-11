package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Opcode;
import com.itsonlybinary.miniboard.protocol.Status;

import java.util.Locale;

/** The device replied with a non-OK status. */
public class MiniBoardStatusException extends MiniBoardException {

    private static final long serialVersionUID = 1L;

    private final Status status;
    private final int rawStatus;
    private final Opcode opcode;

    public MiniBoardStatusException(Opcode opcode, Status status, int rawStatus) {
        super(describe(opcode, status, rawStatus));
        this.opcode = opcode;
        this.status = status;
        this.rawStatus = rawStatus;
    }

    private static String describe(Opcode opcode, Status status, int rawStatus) {
        String name = opcode != null ? opcode.name() : "command";
        if (status != null) {
            return name + " failed: " + status.name() + " (" + status.getDescription() + ")";
        }
        return name + " failed with unknown status 0x" + String.format(Locale.ROOT, "%02X", rawStatus);
    }

    /** @return the status, or {@code null} if the device sent a value this library
     *          does not recognise; see {@link #getRawStatus()}. */
    public Status getStatus() {
        return status;
    }

    public int getRawStatus() {
        return rawStatus;
    }

    /** @return the command that failed, or {@code null} if it was unrecognised. */
    public Opcode getOpcode() {
        return opcode;
    }
}
