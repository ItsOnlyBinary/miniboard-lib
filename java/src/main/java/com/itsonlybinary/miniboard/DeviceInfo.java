package com.itsonlybinary.miniboard;

import java.util.Locale;

/** Identity reported by the device during the hello sequence. Immutable. */
public final class DeviceInfo {

    /** The name the firmware reports; a connection is rejected if it differs. */
    public static final String EXPECTED_NAME = "MiniBoard54";

    private final String name;
    private final int versionMajor;
    private final int versionMinor;
    private final byte[] serial;
    private final Side side;
    private final int configType;

    DeviceInfo(String name, int versionMajor, int versionMinor,
               byte[] serial, Side side, int configType) {
        this.name = name;
        this.versionMajor = versionMajor;
        this.versionMinor = versionMinor;
        this.serial = serial != null ? serial.clone() : new byte[0];
        this.side = side;
        this.configType = configType;
    }

    public String getName() {
        return name;
    }

    public int getVersionMajor() {
        return versionMajor;
    }

    public int getVersionMinor() {
        return versionMinor;
    }

    /** @return firmware version as "major.minor". */
    public String getVersionString() {
        return versionMajor + "." + versionMinor;
    }

    /** @return a copy of the board unique ID, up to 16 bytes. */
    public byte[] getSerial() {
        return serial.clone();
    }

    /**
     * @return the unique ID as uppercase hex, or "" if none was reported.
     *         {@code Locale.ROOT}, so the digits are the same everywhere: this
     *         string identifies a board and is compared, logged and quoted back in
     *         diagnostics alongside hex produced elsewhere in the library.
     */
    public String getSerialHex() {
        StringBuilder sb = new StringBuilder(serial.length * 2);
        for (byte b : serial) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** @return the active connector side at handshake time. Later changes arrive
     *          via {@code MiniBoardListener.onSideChanged}. */
    public Side getSide() {
        return side;
    }

    public int getConfigType() {
        return configType;
    }

    @Override
    public String toString() {
        return "DeviceInfo[name=" + name + ", version=" + getVersionString()
                + ", serial=" + getSerialHex() + ", side=" + side
                + ", configType=" + configType + "]";
    }

    /** Accumulates hello events until all five have arrived. */
    static final class Builder {
        String name;
        Integer versionMajor;
        Integer versionMinor;
        byte[] serial;
        Side side;
        Integer configType;

        boolean isComplete() {
            return name != null && versionMajor != null && serial != null
                    && side != null && configType != null;
        }

        DeviceInfo build() {
            return new DeviceInfo(name, versionMajor, versionMinor,
                    serial, side, configType);
        }
    }
}
