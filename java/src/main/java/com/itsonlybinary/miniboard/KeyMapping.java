package com.itsonlybinary.miniboard;

import java.util.Arrays;

/** The key_id and HID key codes assigned to one key slot. Immutable. */
public final class KeyMapping {

    /** Number of key slots on the board. */
    public static final int KEY_COUNT = 54;

    /** HID key codes per slot. */
    public static final int MAX_KEYS = 6;

    private final int index;
    private final int keyId;
    private final int[] keyCodes;

    /**
     * @param index    key slot, 0 to 53
     * @param keyId    opaque per-key identifier, 0-255; never sent over HID
     * @param keyCodes up to 6 HID key codes; shorter arrays are zero-padded
     * @throws IllegalArgumentException if the index is out of range, {@code keyId}
     *                                  is not 0-255, more than 6 codes are given,
     *                                  or a code is not 0-255
     */
    public KeyMapping(int index, int keyId, int... keyCodes) {
        if (index < 0 || index >= KEY_COUNT) {
            throw new IllegalArgumentException(
                    "Key index must be 0-" + (KEY_COUNT - 1) + ", got " + index);
        }
        if (keyId < 0 || keyId > 255) {
            throw new IllegalArgumentException("key_id must be 0-255, got " + keyId);
        }
        if (keyCodes == null) {
            keyCodes = new int[0];
        }
        if (keyCodes.length > MAX_KEYS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_KEYS + " key codes, got " + keyCodes.length);
        }
        this.index = index;
        this.keyId = keyId;
        this.keyCodes = new int[MAX_KEYS];
        for (int i = 0; i < keyCodes.length; i++) {
            if (keyCodes[i] < 0 || keyCodes[i] > 255) {
                throw new IllegalArgumentException(
                        "Key code must be 0-255, got " + keyCodes[i] + " at " + i);
            }
            this.keyCodes[i] = keyCodes[i];
        }
    }

    public int getIndex() {
        return index;
    }

    /** @return the opaque per-key identifier, 0-255. Never sent over HID. */
    public int getKeyId() {
        return keyId;
    }

    /** @return a copy, always length 6; unused slots are 0. */
    public int[] getKeyCodes() {
        return keyCodes.clone();
    }

    public int getKeyCode(int slot) {
        return keyCodes[slot];
    }

    /** @return how many leading slots are non-zero. */
    public int getActiveCount() {
        int n = 0;
        for (int k : keyCodes) {
            if (k != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Decodes the 8-byte GET_KEY / SET_KEY response payload.
     *
     * <p>A malformed payload is the <em>device's</em> fault, not the caller's, so
     * it raises {@link MiniBoardException} — the vocabulary the rest of the
     * command path already fails futures with. {@link IllegalArgumentException}
     * stays reserved for arguments a caller supplied.
     *
     * @throws MiniBoardException if the payload is not 8 bytes or names a key
     *                            slot outside 0-53
     */
    public static KeyMapping fromResponse(byte[] data) {
        if (data == null || data.length != 2 + MAX_KEYS) {
            throw new MiniBoardException(
                    "GET_KEY response must be 8 bytes, got "
                            + (data == null ? "null" : data.length));
        }
        int index = data[0] & 0xFF;
        if (index >= KEY_COUNT) {
            throw new MiniBoardException("Device reported key index " + index
                    + "; expected 0-" + (KEY_COUNT - 1));
        }
        int keyId = data[1] & 0xFF;
        int[] codes = new int[MAX_KEYS];
        for (int i = 0; i < MAX_KEYS; i++) {
            codes[i] = data[2 + i] & 0xFF;
        }
        // Both constructor preconditions are now discharged: the index is checked
        // above and every code is masked to 0-255, so it cannot throw from here.
        return new KeyMapping(index, keyId, codes);
    }

    /** Encodes the 8-byte SET_KEY request payload: index, key_id, then 6 codes. */
    public byte[] toRequestData() {
        byte[] out = new byte[2 + MAX_KEYS];
        out[0] = (byte) index;
        out[1] = (byte) keyId;
        for (int i = 0; i < MAX_KEYS; i++) {
            out[2 + i] = (byte) keyCodes[i];
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeyMapping)) {
            return false;
        }
        KeyMapping other = (KeyMapping) o;
        return index == other.index && keyId == other.keyId
                && Arrays.equals(keyCodes, other.keyCodes);
    }

    @Override
    public int hashCode() {
        return (index * 31 + keyId) * 31 + Arrays.hashCode(keyCodes);
    }

    @Override
    public String toString() {
        return "KeyMapping[index=" + index + ", keyId=" + keyId
                + ", keys=" + Arrays.toString(keyCodes) + "]";
    }
}
