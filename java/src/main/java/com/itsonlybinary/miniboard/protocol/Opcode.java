package com.itsonlybinary.miniboard.protocol;

/** Command opcodes, range 0x80-0x9F. Values and lengths per mb_cdc_interface.md. */
public enum Opcode {

    GET_CONFIG_TYPE(0x80, 0, 1),
    SET_CONFIG_TYPE(0x81, 1, 1),
    GET_DEBOUNCE(0x82, 0, 1),
    SET_DEBOUNCE(0x83, 1, 1),
    GET_HID_ENABLE(0x84, 0, 1),
    SET_HID_ENABLE(0x85, 1, 1),
    GET_TYPO_REJECT(0x86, 0, 1),
    SET_TYPO_REJECT(0x87, 1, 1),
    GET_KEY(0x88, 1, 8),
    SET_KEY(0x89, 8, 8),
    GET_LED(0x8A, 1, 13),
    SET_LED(0x8B, 9, 13),
    GET_LED_BRIGHTNESS(0x8C, 0, 1),
    SET_LED_BRIGHTNESS(0x8D, 1, 1),
    SET_LED_OFF(0x8E, 1, 1),
    GET_WRITES(0x8F, 0, 4),
    SET_HID_DISABLE_TEMP(0x90, 1, 1),
    // FLASH_TIMEOUT_MS cannot be referenced here directly: the compiler treats
    // this as an illegal forward reference even though the field is a
    // compile-time constant, because enum constants are compiled as if
    // declared before the class's other static fields. Literal inlined per
    // the task brief's documented fallback; must stay equal to FLASH_TIMEOUT_MS
    // below (5000).
    SAVE(0x91, 0, 1, 5000),
    RESET(0x92, 0, 0, 5000),
    REBOOT(0x93, 1, 1);

    /** Default per-command timeout. */
    public static final int DEFAULT_TIMEOUT_MS = 1000;

    /** Timeout for commands that write flash (SAVE, RESET). */
    public static final int FLASH_TIMEOUT_MS = 5000;

    /** Lowest command opcode; used to demultiplex responses from events. */
    public static final int MIN_VALUE = 0x80;

    /** Highest command opcode. */
    public static final int MAX_VALUE = 0x9F;

    private final int value;
    private final int requestLength;
    private final int responseLength;
    private final int timeoutMs;

    Opcode(int value, int requestLength, int responseLength) {
        this(value, requestLength, responseLength, DEFAULT_TIMEOUT_MS);
    }

    Opcode(int value, int requestLength, int responseLength, int timeoutMs) {
        this.value = value;
        this.requestLength = requestLength;
        this.responseLength = responseLength;
        this.timeoutMs = timeoutMs;
    }

    public int getValue() {
        return value;
    }

    /** @return expected DATA byte count in the request. */
    public int getRequestLength() {
        return requestLength;
    }

    /** @return expected DATA byte count in an OK response. */
    public int getResponseLength() {
        return responseLength;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    /** @return the matching opcode, or {@code null} if unrecognised. */
    public static Opcode fromValue(int value) {
        for (Opcode o : values()) {
            if (o.value == (value & 0xFF)) {
                return o;
            }
        }
        return null;
    }

    /** @return true if the opcode falls in the command range, known or not. */
    public static boolean isCommandOpcode(int opcode) {
        int v = opcode & 0xFF;
        return v >= MIN_VALUE && v <= MAX_VALUE;
    }
}
