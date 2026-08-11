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
    private final boolean selfTestPassed;
    private final byte[] stuckKeysBitmap;
    private final boolean ledFault;

    DeviceInfo(String name, int versionMajor, int versionMinor,
               byte[] serial, Side side, int configType,
               boolean selfTestPassed, byte[] stuckKeysBitmap, boolean ledFault) {
        this.name = name;
        this.versionMajor = versionMajor;
        this.versionMinor = versionMinor;
        this.serial = serial != null ? serial.clone() : new byte[0];
        this.side = side;
        this.configType = configType;
        this.selfTestPassed = selfTestPassed;
        this.stuckKeysBitmap = stuckKeysBitmap != null ? stuckKeysBitmap.clone() : new byte[0];
        this.ledFault = ledFault;
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

    /**
     * @return the result of the boot-time self-test: PIO/DMA LED resource claim,
     *         a visible LED chase, and a scan for keys reading "pressed" on boot.
     *         False if either {@link #isLedFault()} or any bit in
     *         {@link #getStuckKeysBitmap()} is set.
     */
    public boolean isSelfTestPassed() {
        return selfTestPassed;
    }

    /**
     * @return a copy of the stuck-key bitmap from the boot self-test: 1 bit per
     *         key, LSB-first, key {@code i} is bit {@code (8 * byte + bit)} of
     *         byte {@code byte}. A set bit means that key was excluded from
     *         {@code KEY} events and HID output for the rest of the session; the
     *         only recovery is a full power cycle. Prefer {@link #isKeyStuck(int)}
     *         over indexing this directly.
     */
    public byte[] getStuckKeysBitmap() {
        return stuckKeysBitmap.clone();
    }

    /**
     * @param keyIndex the key index to check, as used elsewhere (e.g. {@link
     *                 MiniBoardListener#onKey})
     * @return true if the boot self-test found this key stuck and excluded it
     *         from {@code KEY} events and HID output for the session
     */
    public boolean isKeyStuck(int keyIndex) {
        int byteIndex = keyIndex / 8;
        if (byteIndex < 0 || byteIndex >= stuckKeysBitmap.length) {
            return false;
        }
        int bit = keyIndex % 8;
        return (stuckKeysBitmap[byteIndex] & (1 << bit)) != 0;
    }

    /**
     * @return true if the boot self-test could not claim the LED driver's
     *         PIO/DMA resources, or the LED transmit check failed
     */
    public boolean isLedFault() {
        return ledFault;
    }

    @Override
    public String toString() {
        return "DeviceInfo[name=" + name + ", version=" + getVersionString()
                + ", serial=" + getSerialHex() + ", side=" + side
                + ", configType=" + configType + ", selfTestPassed=" + selfTestPassed
                + ", ledFault=" + ledFault + "]";
    }

    /** Accumulates hello events until all six have arrived. */
    static final class Builder {
        String name;
        Integer versionMajor;
        Integer versionMinor;
        byte[] serial;
        Side side;
        Integer configType;
        Boolean selfTestPassed;
        byte[] stuckKeysBitmap;
        Boolean ledFault;

        boolean isComplete() {
            return name != null && versionMajor != null && serial != null
                    && side != null && configType != null && selfTestPassed != null;
        }

        DeviceInfo build() {
            return new DeviceInfo(name, versionMajor, versionMinor,
                    serial, side, configType, selfTestPassed, stuckKeysBitmap, ledFault);
        }
    }
}
