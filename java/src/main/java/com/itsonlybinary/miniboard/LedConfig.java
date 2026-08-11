package com.itsonlybinary.miniboard;

/** LED animation configuration for one LED. Immutable. */
public final class LedConfig {

    /** Number of addressable LEDs. */
    public static final int LED_COUNT = 4;

    /** Wire size of a GET_LED / SET_LED request payload. */
    public static final int REQUEST_SIZE = 9;

    /** Wire size of a GET_LED / SET_LED response payload. */
    public static final int RESPONSE_SIZE = 13;

    /** Bit 0 of the response flags byte: a firmware notification overlay is active. */
    private static final int FLAG_OVERLAY_ACTIVE = 0x01;

    private final int index;
    private final LedMode mode;
    private final LedFinal finalState;
    private final int rawMode;
    private final int rawFinalState;
    private final int red;
    private final int green;
    private final int blue;
    private final int iterations;
    private final int durationMs;
    private final int curRed;
    private final int curGreen;
    private final int curBlue;
    private final int flags;

    /**
     * @param index      LED index, 0 to 3
     * @param mode       animation mode
     * @param finalState state to settle into when a finite animation ends
     * @param red        target red, 0-255
     * @param green      target green, 0-255
     * @param blue       target blue, 0-255
     * @param iterations cycles for BLINK and PULSE; 0 means infinite, and then
     *                   no LED_FINISH event is ever sent. Ignored for TRANSITION.
     * @param durationMs animation step duration, 0-65535
     * @throws IllegalArgumentException if any argument is out of range or null
     */
    public LedConfig(int index, LedMode mode, LedFinal finalState,
                     int red, int green, int blue, int iterations, int durationMs) {
        // Every argument is validated on the way in, so this is the caller-facing
        // door: it is the one that raises IllegalArgumentException. A LedConfig
        // built this way (rather than decoded from a response) carries no live
        // state, so the cur*/flags fields are zero — they are never sent on the
        // wire anyway, since toRequestData() ignores them.
        this(checkIndex(index),
                required(mode, "mode").getValue(),
                required(finalState, "finalState").getValue(),
                checkByte("red", red), checkByte("green", green), checkByte("blue", blue),
                checkByte("iterations", iterations), checkDuration(durationMs),
                0, 0, 0, 0);
    }

    /**
     * Canonical constructor, taking the mode and final state as the raw wire
     * bytes so a value this library version does not recognise can still be
     * carried. Validates nothing: both callers have already done so.
     */
    private LedConfig(int index, int rawMode, int rawFinalState,
                      int red, int green, int blue, int iterations, int durationMs,
                      int curRed, int curGreen, int curBlue, int flags) {
        this.index = index;
        this.rawMode = rawMode;
        this.rawFinalState = rawFinalState;
        this.mode = LedMode.fromValue(rawMode);
        this.finalState = LedFinal.fromValue(rawFinalState);
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.iterations = iterations;
        this.durationMs = durationMs;
        this.curRed = curRed;
        this.curGreen = curGreen;
        this.curBlue = curBlue;
        this.flags = flags;
    }

    private static int checkByte(String name, int v) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException(name + " must be 0-255, got " + v);
        }
        return v;
    }

    private static int checkIndex(int index) {
        if (index < 0 || index >= LED_COUNT) {
            throw new IllegalArgumentException(
                    "LED index must be 0-" + (LED_COUNT - 1) + ", got " + index);
        }
        return index;
    }

    private static int checkDuration(int durationMs) {
        if (durationMs < 0 || durationMs > 0xFFFF) {
            throw new IllegalArgumentException(
                    "durationMs must be 0-65535, got " + durationMs);
        }
        return durationMs;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public int getIndex() {
        return index;
    }

    /**
     * @return the animation mode, or null if the device reported a mode this
     *         library version does not know — see {@link #getRawMode()}. Never
     *         null for an instance you constructed yourself.
     */
    public LedMode getMode() {
        return mode;
    }

    /**
     * @return the state the LED settles into, or null if the device reported one
     *         this library version does not know — see
     *         {@link #getRawFinalState()}. Never null for an instance you
     *         constructed yourself.
     */
    public LedFinal getFinalState() {
        return finalState;
    }

    /** @return the mode exactly as it appeared on the wire, 0-255. */
    public int getRawMode() {
        return rawMode;
    }

    /** @return the final state exactly as it appeared on the wire, 0-255. */
    public int getRawFinalState() {
        return rawFinalState;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int getIterations() {
        return iterations;
    }

    public int getDurationMs() {
        return durationMs;
    }

    /**
     * @return red component physically lit right now, 0-255. Zero for an
     *         instance you constructed yourself — only a decoded response
     *         carries live state.
     */
    public int getCurRed() {
        return curRed;
    }

    /** @return green component physically lit right now, 0-255. See {@link #getCurRed()}. */
    public int getCurGreen() {
        return curGreen;
    }

    /** @return blue component physically lit right now, 0-255. See {@link #getCurRed()}. */
    public int getCurBlue() {
        return curBlue;
    }

    /** @return the response flags byte exactly as it appeared on the wire, 0-255. */
    public int getFlags() {
        return flags;
    }

    /** @return true if a firmware notification overlay (e.g. typo rejection) is covering this LED. */
    public boolean isOverlayActive() {
        return (flags & FLAG_OVERLAY_ACTIVE) != 0;
    }

    /**
     * Decodes the 13-byte GET_LED / SET_LED response payload.
     *
     * <p>A mode or final state this library version does not recognise
     * <strong>degrades</strong> rather than failing: {@link #getMode()} or
     * {@link #getFinalState()} returns null and the byte survives in
     * {@link #getRawMode()} / {@link #getRawFinalState()}, matching what
     * {@link LedMode#fromValue} and {@link LedFinal#fromValue} already promise.
     * Firmware that adds a mode 4 must not make {@code getLed()} fail hard, and
     * {@link #toRequestData()} re-emits the raw byte, so the round trip is
     * lossless.
     *
     * <p>A payload the protocol cannot describe at all is a different matter and
     * raises {@link MiniBoardException} — the device is at fault, not the caller,
     * so this is deliberately not {@link IllegalArgumentException}.
     *
     * @throws MiniBoardException if the payload is not 13 bytes or names an LED
     *                            outside 0-3
     */
    public static LedConfig fromResponse(byte[] d) {
        // Deliberately exact (!=), unlike MiniBoard.getWrites() and the oneByte()
        // helper, which accept a length of at least the expected size (>=). Both
        // styles are intentional, inherited from before this migration; this is
        // not an inconsistency to fix.
        if (d == null || d.length != RESPONSE_SIZE) {
            throw new MiniBoardException("GET_LED response must be " + RESPONSE_SIZE
                    + " bytes, got " + (d == null ? "null" : d.length));
        }
        int index = d[0] & 0xFF;
        if (index >= LED_COUNT) {
            // Every request this library sends already carries a validated index,
            // so an out-of-range echo is a fault rather than a newer firmware.
            throw new MiniBoardException("Device reported LED index " + index
                    + "; expected 0-" + (LED_COUNT - 1));
        }
        // duration_ms is little-endian: low byte first.
        int duration = (d[7] & 0xFF) | ((d[8] & 0xFF) << 8);
        return new LedConfig(index, d[1] & 0xFF, d[2] & 0xFF,
                d[3] & 0xFF, d[4] & 0xFF, d[5] & 0xFF, d[6] & 0xFF, duration,
                d[9] & 0xFF, d[10] & 0xFF, d[11] & 0xFF, d[12] & 0xFF);
    }

    /** Encodes the 9-byte SET_LED request payload. Live state (cur* / flags) is never sent. */
    public byte[] toRequestData() {
        byte[] out = new byte[REQUEST_SIZE];
        out[0] = (byte) index;
        // Raw, not mode.getValue(): an unrecognised mode read back from the
        // device must go out again unchanged rather than throwing.
        out[1] = (byte) rawMode;
        out[2] = (byte) rawFinalState;
        out[3] = (byte) red;
        out[4] = (byte) green;
        out[5] = (byte) blue;
        out[6] = (byte) iterations;
        out[7] = (byte) (durationMs & 0xFF);         // little-endian low byte
        out[8] = (byte) ((durationMs >> 8) & 0xFF);  // little-endian high byte
        return out;
    }

    @Override
    public String toString() {
        return "LedConfig[index=" + index
                + ", mode=" + (mode == null ? "UNKNOWN(" + rawMode + ")" : mode)
                + ", final=" + (finalState == null
                        ? "UNKNOWN(" + rawFinalState + ")" : finalState)
                + ", rgb=(" + red + "," + green + "," + blue + ")"
                + ", iterations=" + iterations + ", durationMs=" + durationMs
                + ", cur=(" + curRed + "," + curGreen + "," + curBlue + ")"
                + ", overlayActive=" + isOverlayActive() + "]";
    }
}
