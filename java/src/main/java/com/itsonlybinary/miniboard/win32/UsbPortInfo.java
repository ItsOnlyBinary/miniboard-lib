package com.itsonlybinary.miniboard.win32;

import java.util.Locale;

/** USB metadata for one COM port, read from the Windows registry and PnP database. Immutable. */
public final class UsbPortInfo {

    private final String portName;
    private final String hardwareId;
    private final String friendlyName;
    private final String deviceDesc;
    private final String manufacturer;
    private final String busReportedDescription;
    private final int vendorId;
    private final int productId;

    public UsbPortInfo(String portName, String hardwareId, String friendlyName,
                       String deviceDesc, String manufacturer, String busReportedDescription,
                       int vendorId, int productId) {
        this.portName = portName;
        this.hardwareId = hardwareId;
        this.friendlyName = friendlyName;
        this.deviceDesc = deviceDesc;
        this.manufacturer = manufacturer;
        this.busReportedDescription = busReportedDescription;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    /** @return the COM port name, e.g. "COM5". */
    public String getPortName() {
        return portName;
    }

    /** @return the device instance path, e.g. "USB\\VID_CAFE&amp;PID_4005\\E66038...". */
    public String getHardwareId() {
        return hardwareId;
    }

    /** @return the FriendlyName registry value; may be null. */
    public String getFriendlyName() {
        return friendlyName;
    }

    /** @return the DeviceDesc registry value; may be null. */
    public String getDeviceDesc() {
        return deviceDesc;
    }

    /**
     * The string the device itself reported over USB
     * ({@code DEVPKEY_Device_BusReportedDeviceDesc}).
     *
     * <p>This is the only place a CDC device's product string survives:
     * {@code usbser.inf} replaces {@link #getFriendlyName()},
     * {@link #getDeviceDesc()} and {@link #getManufacturer()} with generic
     * Microsoft text.
     *
     * @return the bus-reported description, or null if unavailable
     */
    public String getBusReportedDescription() {
        return busReportedDescription;
    }

    /** @return the Mfg registry value; may be null. */
    public String getManufacturer() {
        return manufacturer;
    }

    /** @return the best human-readable description available, or null. */
    public String getDescription() {
        if (busReportedDescription != null) {
            return busReportedDescription;
        }
        return friendlyName != null ? friendlyName : deviceDesc;
    }

    /** @return the USB vendor ID, or -1 if it could not be parsed. */
    public int getVendorId() {
        return vendorId;
    }

    /** @return the USB product ID, or -1 if it could not be parsed. */
    public int getProductId() {
        return productId;
    }

    /**
     * Searches the device's descriptive text for a product token.
     *
     * <p>{@link #getHardwareId()} is deliberately <em>not</em> searched. Live data
     * shows it never carries the product string — it is
     * {@code USB\VID_CAFE&PID_4005&MI_00\6&295c3188&0&0000}, a VID/PID/serial path —
     * while a third-party TinyUSB device that happened to encode the token in its
     * serial number would otherwise be promoted to the top tier. 0xCAFE is the
     * shared TinyUSB example VID, so that collision is not hypothetical.
     *
     * <p>The case fold is {@link Locale#ROOT}, never the default locale. Under a
     * Turkish or Azeri locale {@code "MiniBoard CDC".toUpperCase()} yields
     * {@code "MİNİBOARD CDC"} with a dotted capital I, which does not contain the
     * ASCII constant {@code "MINIBOARD"} — an attached board would silently drop
     * from CONFIRMED to PROBABLE, reintroducing the exact bug this field exists to
     * fix.
     *
     * @return true if the token appears in any descriptive field, case-insensitively
     */
    public boolean matchesText(String token) {
        if (token == null) {
            return false;
        }
        String needle = token.toUpperCase(Locale.ROOT);
        return contains(busReportedDescription, needle)
                || contains(friendlyName, needle)
                || contains(deviceDesc, needle)
                || contains(manufacturer, needle);
    }

    private static boolean contains(String haystack, String upperNeedle) {
        return haystack != null && haystack.toUpperCase(Locale.ROOT).contains(upperNeedle);
    }

    @Override
    public String toString() {
        return "UsbPortInfo[" + portName
                + ", vid=" + (vendorId >= 0 ? String.format(Locale.ROOT, "%04X", vendorId) : "?")
                + ", pid=" + (productId >= 0 ? String.format(Locale.ROOT, "%04X", productId) : "?")
                + ", bus=" + busReportedDescription
                + ", friendly=" + friendlyName
                + ", desc=" + deviceDesc
                + ", mfg=" + manufacturer + "]";
    }
}
