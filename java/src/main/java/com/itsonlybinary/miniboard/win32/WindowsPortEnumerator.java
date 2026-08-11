package com.itsonlybinary.miniboard.win32;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads USB metadata for COM ports from the Windows registry.
 *
 * <p>Opens no serial port. Purely a registry walk under
 * {@code HKLM\SYSTEM\CurrentControlSet\Enum\USB}, joined against the PnP
 * bus-reported descriptions from {@link DevicePropertyReader}.
 */
public final class WindowsPortEnumerator {

    private static final String USB_ENUM =
            "SYSTEM\\CurrentControlSet\\Enum\\USB";

    private static final Pattern VID_PATTERN = Pattern.compile("VID_([0-9A-Fa-f]{4})");
    private static final Pattern PID_PATTERN = Pattern.compile("PID_([0-9A-Fa-f]{4})");

    private WindowsPortEnumerator() {
    }

    /** @return true if running on Windows, where the registry walk is meaningful. */
    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        // Locale.ROOT: a Turkish default locale folds an upper-case I to a dotless
        // i, so "WINDOWS" would become "wındows" and fail to contain "win" —
        // disabling the whole registry walk on a genuine Windows host.
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Enumerates every USB device that exposes a COM port.
     *
     * <p>Results may include devices that are not currently attached — Windows
     * retains {@code Enum} keys for every device ever plugged in. Callers must
     * intersect these with the live port list.
     *
     * @return metadata for each USB-backed COM port; empty on non-Windows or on
     *         any registry failure
     */
    public static List<UsbPortInfo> enumerate() {
        List<UsbPortInfo> result = new ArrayList<UsbPortInfo>();
        if (!isWindows()) {
            return result;
        }

        // Read once, join per node. An empty map here is the normal, supported
        // outcome on older or locked-down Windows: everything below still runs and
        // the result is exactly what it was before bus-reported descriptions existed.
        Map<String, String> busReported = DevicePropertyReader.readBusReportedDescriptions();

        String[] deviceKeys;
        try {
            deviceKeys = Advapi32Util.registryGetKeys(
                    WinReg.HKEY_LOCAL_MACHINE, USB_ENUM);
        } catch (RuntimeException e) {
            // No USB enum key or access denied: report nothing rather than failing.
            return result;
        }

        for (String deviceKey : deviceKeys) {
            String devicePath = USB_ENUM + "\\" + deviceKey;
            String[] instances;
            try {
                instances = Advapi32Util.registryGetKeys(
                        WinReg.HKEY_LOCAL_MACHINE, devicePath);
            } catch (RuntimeException e) {
                continue; // key vanished or unreadable; keep going
            }

            for (String instance : instances) {
                String instancePath = devicePath + "\\" + instance;
                String portName = readPortName(instancePath);
                if (portName == null) {
                    continue; // not a COM-port-exposing node
                }

                String friendly = readString(instancePath, "FriendlyName");
                String deviceDesc = readString(instancePath, "DeviceDesc");
                String mfg = readString(instancePath, "Mfg");

                String instanceId = "USB\\" + deviceKey + "\\" + instance;

                result.add(new UsbPortInfo(
                        portName,
                        instanceId,
                        friendly,
                        deviceDesc,
                        mfg,
                        busReported.get(instanceId.toUpperCase(Locale.ROOT)),
                        parseHexGroup(VID_PATTERN, deviceKey),
                        parseHexGroup(PID_PATTERN, deviceKey)));
            }
        }
        return result;
    }

    /** Reads {@code Device Parameters\PortName}, or null if absent. */
    private static String readPortName(String instancePath) {
        String paramsPath = instancePath + "\\Device Parameters";
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, paramsPath)) {
                return null;
            }
            if (!Advapi32Util.registryValueExists(
                    WinReg.HKEY_LOCAL_MACHINE, paramsPath, "PortName")) {
                return null;
            }
            String value = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE, paramsPath, "PortName");
            return (value == null || value.trim().isEmpty()) ? null : value.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readString(String path, String valueName) {
        try {
            if (!Advapi32Util.registryValueExists(
                    WinReg.HKEY_LOCAL_MACHINE, path, valueName)) {
                return null;
            }
            return Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE, path, valueName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseHexGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1), 16);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }
}
