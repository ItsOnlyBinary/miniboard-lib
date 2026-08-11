package com.itsonlybinary.miniboard.protocol;

import java.util.Locale;

/**
 * One decoded protocol frame, retaining the exact bytes that crossed the wire.
 *
 * <p>{@link #getRawBytes()} lets a debug application render a hex dump and a
 * structured view from the same object.
 */
public final class Frame {

    /** Start-of-frame marker, first byte of every frame. */
    public static final int SOF = 0xAA;

    private final FrameType type;
    private final int opcode;
    private final Status status;
    private final int rawStatus;
    private final byte[] data;
    private final byte[] raw;

    Frame(FrameType type, int opcode, Status status, int rawStatus, byte[] data, byte[] raw) {
        this.type = type;
        this.opcode = opcode;
        this.status = status;
        this.rawStatus = rawStatus;
        this.data = data;
        this.raw = raw;
    }

    public FrameType getType() {
        return type;
    }

    /** @return the raw opcode byte: a command opcode or an event type. */
    public int getOpcode() {
        return opcode;
    }

    /** @return the command enum, or {@code null} for events or unknown opcodes. */
    public Opcode getCommand() {
        return Opcode.fromValue(opcode);
    }

    /** @return the event enum, or {@code null} for responses or unknown opcodes. */
    public EventType getEvent() {
        return type == FrameType.EVENT ? EventType.fromValue(opcode) : null;
    }

    /**
     * @return the status, or {@code null} for non-response frames and for status
     *         values this library does not recognise. Check {@link #getRawStatus()}
     *         to distinguish those two cases.
     */
    public Status getStatus() {
        return status;
    }

    /** @return the status byte as received, or -1 if this frame carries no status. */
    public int getRawStatus() {
        return rawStatus;
    }

    /** @return a defensive copy of the payload; never null, may be empty. */
    public byte[] getData() {
        return data.clone();
    }

    /** @return the payload length without copying. */
    public int getDataLength() {
        return data.length;
    }

    /** @return one payload byte as an unsigned 0-255 value. */
    public int getDataByte(int index) {
        return data[index] & 0xFF;
    }

    /** @return a defensive copy of the complete on-wire frame, including SOF and CRC. */
    public byte[] getRawBytes() {
        return raw.clone();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append('[');
        if (type == FrameType.EVENT) {
            EventType e = getEvent();
            sb.append(e != null ? e.name() : String.format(Locale.ROOT, "EVT_0x%02X", opcode));
        } else {
            Opcode o = getCommand();
            sb.append(o != null ? o.name() : String.format(Locale.ROOT, "CMD_0x%02X", opcode));
        }
        if (type == FrameType.RESPONSE) {
            sb.append(" status=").append(status != null
                    ? status.name() : String.format(Locale.ROOT, "0x%02X", rawStatus));
        }
        sb.append(" len=").append(data.length).append(" data=");
        for (byte b : data) {
            sb.append(String.format(Locale.ROOT, "%02X ", b & 0xFF));
        }
        return sb.append(']').toString();
    }
}
