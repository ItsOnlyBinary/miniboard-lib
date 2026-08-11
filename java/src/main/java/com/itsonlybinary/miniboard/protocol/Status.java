package com.itsonlybinary.miniboard.protocol;

/** Status byte carried by every command response frame. */
public enum Status {

    OK(0x00, "Command executed successfully"),
    BAD_CMD(0x01, "Unknown command opcode"),
    BAD_LEN(0x02, "Wrong number of data bytes"),
    BAD_DATA(0x03, "Data payload is invalid"),
    CRC_FAILED(0x04, "CRC mismatch in the received request"),
    OUT_OF_RANGE(0x05, "Index exceeds valid range"),
    INTERNAL_ERR(0x06, "Internal firmware error");

    private final int value;
    private final String description;

    Status(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDescription() {
        return description;
    }

    public boolean isOk() {
        return this == OK;
    }

    /**
     * @return the matching status, or {@code null} if the firmware reported a
     *         status this library does not know. Returning null rather than
     *         throwing lets newer firmware degrade gracefully.
     */
    public static Status fromValue(int value) {
        for (Status s : values()) {
            if (s.value == (value & 0xFF)) {
                return s;
            }
        }
        return null;
    }
}
