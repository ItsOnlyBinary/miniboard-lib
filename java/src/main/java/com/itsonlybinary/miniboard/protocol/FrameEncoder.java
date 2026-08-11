package com.itsonlybinary.miniboard.protocol;

/** Builds host-to-device request frames. */
public final class FrameEncoder {

    private FrameEncoder() {
    }

    /**
     * Encodes a request frame: SOF, CMD, LEN, DATA, CRC.
     *
     * @param opcode the command to send
     * @param data   payload, or null for none
     * @return a frame whose {@link Frame#getRawBytes()} is ready to transmit
     * @throws IllegalArgumentException if the payload exceeds 255 bytes or does not
     *                                  match the opcode's declared request length
     */
    public static Frame encodeRequest(Opcode opcode, byte[] data) {
        byte[] payload = (data == null) ? new byte[0] : data.clone();
        if (payload.length > 255) {
            throw new IllegalArgumentException(
                    "Payload exceeds 255 bytes: " + payload.length);
        }
        if (payload.length != opcode.getRequestLength()) {
            throw new IllegalArgumentException(opcode + " expects "
                    + opcode.getRequestLength() + " data bytes, got " + payload.length);
        }

        byte[] raw = new byte[4 + payload.length];
        raw[0] = (byte) Frame.SOF;
        raw[1] = (byte) opcode.getValue();
        raw[2] = (byte) payload.length;
        System.arraycopy(payload, 0, raw, 3, payload.length);
        // CRC covers CMD, LEN, DATA - never the SOF.
        raw[raw.length - 1] = (byte) Crc8.compute(raw, 1, raw.length - 2);

        return new Frame(FrameType.REQUEST, opcode.getValue(), null, -1, payload, raw);
    }
}
