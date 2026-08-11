package com.itsonlybinary.miniboard.win32;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.SetupApi;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code DEVPKEY_Device_BusReportedDeviceDesc} for present COM-port device
 * nodes through the Windows PnP configuration API.
 *
 * <p><strong>Why this exists.</strong> {@code usbser.inf} overwrites every plain
 * registry text value ({@code DeviceDesc}, {@code Mfg}, {@code FriendlyName}) of a
 * CDC device with generic Microsoft text, so the USB product string cannot be found
 * anywhere under {@code Enum\USB}. Windows keeps the string the device actually
 * reported as a device <em>property</em> instead. Its raw registry backing
 * ({@code ...\Properties\{540b947e-...}\0004}) is ACL-protected and denies access
 * without elevation; {@code SetupDiGetDevicePropertyW} reads it unelevated.
 *
 * <p><strong>No serial port is opened.</strong> {@code SetupDiGetClassDevs} builds an
 * information set from the PnP database only — no device is opened, and DTR is never
 * asserted.
 *
 * <p><strong>Every failure degrades to "unknown".</strong> The property is genuinely
 * absent on most devices ({@code ERROR_NOT_FOUND} is the normal case, not an error),
 * it does not exist at all before Windows Vista, and JNA may fail to bind on a
 * locked-down machine. All of those return null / an empty map; nothing here throws.
 */
public final class DevicePropertyReader {

    /** {4D36E978-E325-11CE-BFC1-08002BE10318} — the "Ports (COM &amp; LPT)" class. */
    private static final int PORTS_DATA1 = 0x4D36E978;
    private static final short PORTS_DATA2 = (short) 0xE325;
    private static final short PORTS_DATA3 = (short) 0x11CE;
    private static final byte[] PORTS_DATA4 = {
            (byte) 0xBF, (byte) 0xC1, (byte) 0x08, (byte) 0x00,
            (byte) 0x2B, (byte) 0xE1, (byte) 0x03, (byte) 0x18};

    /** DEVPKEY_Device_BusReportedDeviceDesc: {540B947E-8B40-45BC-A8A2-6A0B894CBDA2}, pid 4. */
    private static final int BRDD_DATA1 = 0x540B947E;
    private static final short BRDD_DATA2 = (short) 0x8B40;
    private static final short BRDD_DATA3 = (short) 0x45BC;
    private static final byte[] BRDD_DATA4 = {
            (byte) 0xA8, (byte) 0xA2, (byte) 0x6A, (byte) 0x0B,
            (byte) 0x89, (byte) 0x4C, (byte) 0xBD, (byte) 0xA2};
    private static final int BRDD_PID = 4;

    private static final int DIGCF_PRESENT = 0x00000002;
    private static final int DEVPROP_TYPE_STRING = 0x00000012;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;

    /** Nothing legitimate is anywhere near this large; a wild size means a bad call. */
    private static final int MAX_PROPERTY_BYTES = 64 * 1024;

    /** Enumeration is bounded so a misbehaving driver cannot spin us forever. */
    private static final int MAX_DEVICE_NODES = 4096;

    private DevicePropertyReader() {
    }

    /**
     * The functions {@code platform:3.5.1} does not bind. Loaded with
     * {@link W32APIOptions#UNICODE_OPTIONS} so the mapper resolves the {@code W}
     * variants; names with no {@code W} export (SetupDiEnumDeviceInfo) fall back to
     * the undecorated symbol automatically.
     */
    private interface SetupApiExt extends StdCallLibrary {

        SetupApiExt INSTANCE = (SetupApiExt) Native.loadLibrary(
                "setupapi", SetupApiExt.class, W32APIOptions.UNICODE_OPTIONS);

        boolean SetupDiEnumDeviceInfo(WinNT.HANDLE deviceInfoSet,
                                      int memberIndex,
                                      SetupApi.SP_DEVINFO_DATA.ByReference deviceInfoData);

        /** {@code DeviceInstanceIdSize} is counted in <em>characters</em>, not bytes. */
        boolean SetupDiGetDeviceInstanceId(WinNT.HANDLE deviceInfoSet,
                                           SetupApi.SP_DEVINFO_DATA.ByReference deviceInfoData,
                                           Pointer deviceInstanceId,
                                           int deviceInstanceIdSize,
                                           IntByReference requiredSize);

        /** {@code propertyBufferSize} and {@code requiredSize} are in bytes. */
        boolean SetupDiGetDevicePropertyW(WinNT.HANDLE deviceInfoSet,
                                          SetupApi.SP_DEVINFO_DATA.ByReference deviceInfoData,
                                          DEVPROPKEY propertyKey,
                                          IntByReference propertyType,
                                          Pointer propertyBuffer,
                                          int propertyBufferSize,
                                          IntByReference requiredSize,
                                          int flags);
    }

    /**
     * {@code DEVPROPKEY} — a {@code GUID} followed by a {@code ULONG}. Declared with
     * flat fields rather than a nested {@link Guid.GUID} so the layout is explicit.
     *
     * <p>{@code getFieldOrder()} is <em>abstract</em> in JNA 3.5.1 (it only became
     * optional in 4.x), so the override below is mandatory, not decorative.
     */
    public static class DEVPROPKEY extends Structure {
        public int Data1;
        public short Data2;
        public short Data3;
        public byte[] Data4 = new byte[8];
        public int pid;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Data1", "Data2", "Data3", "Data4", "pid");
        }
    }

    /**
     * Looks up the bus-reported description for one device instance.
     *
     * @param instanceId a device instance path, e.g.
     *                   {@code USB\VID_CAFE&PID_4005&MI_00\6&295c3188&0&0000}
     * @return the string the device itself reported, or null if unavailable —
     *         non-Windows, no such node, no such property, or a JNA failure
     */
    public static String readBusReportedDeviceDesc(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        return readBusReportedDescriptions().get(instanceId.toUpperCase(Locale.ROOT));
    }

    /**
     * Reads the bus-reported description of every present device in the Ports class
     * in a single pass.
     *
     * @return an unmodifiable map of upper-cased device instance ID to description,
     *         containing only nodes that actually have the property. Empty — never
     *         null, never an exception — on non-Windows or on any native failure.
     */
    public static Map<String, String> readBusReportedDescriptions() {
        Map<String, String> out = new HashMap<String, String>();
        if (!WindowsPortEnumerator.isWindows()) {
            return Collections.unmodifiableMap(out);
        }
        try {
            collect(out);
        } catch (Throwable t) {
            // UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError,
            // or any JNA marshalling fault. Ranking loses a signal; discovery must not
            // lose a port. Whatever was collected before the fault is still valid.
        }
        return Collections.unmodifiableMap(out);
    }

    private static void collect(Map<String, String> out) {
        WinNT.HANDLE devInfoSet = SetupApi.INSTANCE.SetupDiGetClassDevs(
                portsClassGuid(), null, null, DIGCF_PRESENT);
        if (devInfoSet == null || WinBase.INVALID_HANDLE_VALUE.equals(devInfoSet)) {
            return;
        }
        try {
            DEVPROPKEY key = busReportedDeviceDescKey();
            SetupApi.SP_DEVINFO_DATA.ByReference devInfo =
                    new SetupApi.SP_DEVINFO_DATA.ByReference();

            for (int index = 0; index < MAX_DEVICE_NODES; index++) {
                // SetupDiEnumDeviceInfo validates cbSize on every call, and JNA
                // re-reads the struct back from native memory after each one.
                devInfo.cbSize = devInfo.size();
                if (!SetupApiExt.INSTANCE.SetupDiEnumDeviceInfo(devInfoSet, index, devInfo)) {
                    break; // ERROR_NO_MORE_ITEMS, or the set went away
                }
                String instanceId = readInstanceId(devInfoSet, devInfo);
                if (instanceId == null) {
                    continue;
                }
                String description = readStringProperty(devInfoSet, devInfo, key);
                if (description != null) {
                    // Locale.ROOT, matching WindowsPortEnumerator's side of the join.
                    // Both sides applying the same fold in the same JVM would be safe
                    // under any locale, but pinning it means no future reader has to
                    // work out why one toUpperCase is safe and the one in
                    // UsbPortInfo.matchesText is not.
                    out.put(instanceId.toUpperCase(Locale.ROOT), description);
                }
            }
        } finally {
            // A leaked device-info set is a handle leak in a long-running host.
            SetupApi.INSTANCE.SetupDiDestroyDeviceInfoList(devInfoSet);
        }
    }

    private static String readInstanceId(WinNT.HANDLE devInfoSet,
                                         SetupApi.SP_DEVINFO_DATA.ByReference devInfo) {
        IntByReference required = new IntByReference();
        // First call is expected to fail with ERROR_INSUFFICIENT_BUFFER and set
        // *required* to the character count, terminator included.
        SetupApiExt.INSTANCE.SetupDiGetDeviceInstanceId(devInfoSet, devInfo, null, 0, required);
        int chars = required.getValue();
        if (chars <= 0 || chars > MAX_PROPERTY_BYTES) {
            return null;
        }
        Memory buffer = new Memory((long) chars * 2L);
        buffer.clear();
        if (!SetupApiExt.INSTANCE.SetupDiGetDeviceInstanceId(
                devInfoSet, devInfo, buffer, chars, required)) {
            return null;
        }
        String id = buffer.getString(0, true);
        return (id == null || id.isEmpty()) ? null : id;
    }

    private static String readStringProperty(WinNT.HANDLE devInfoSet,
                                             SetupApi.SP_DEVINFO_DATA.ByReference devInfo,
                                             DEVPROPKEY key) {
        IntByReference type = new IntByReference();
        IntByReference required = new IntByReference();

        boolean sized = SetupApiExt.INSTANCE.SetupDiGetDevicePropertyW(
                devInfoSet, devInfo, key, type, null, 0, required, 0);
        if (sized) {
            return null; // a zero-byte buffer cannot legitimately succeed
        }
        if (Native.getLastError() != ERROR_INSUFFICIENT_BUFFER) {
            return null; // ERROR_NOT_FOUND: the usual case, and not worth surfacing
        }
        int bytes = required.getValue();
        if (bytes <= 0 || bytes > MAX_PROPERTY_BYTES) {
            return null;
        }

        Memory buffer = new Memory(bytes);
        buffer.clear();
        if (!SetupApiExt.INSTANCE.SetupDiGetDevicePropertyW(
                devInfoSet, devInfo, key, type, buffer, bytes, required, 0)) {
            return null;
        }
        if (type.getValue() != DEVPROP_TYPE_STRING) {
            return null;
        }
        return decodeUtf16(buffer.getByteArray(0, bytes));
    }

    /** Decodes a UTF-16LE {@code DEVPROP_TYPE_STRING} payload, dropping the NUL. */
    private static String decodeUtf16(byte[] raw) {
        String text;
        try {
            text = new String(raw, "UTF-16LE");
        } catch (UnsupportedEncodingException e) {
            return null; // UTF-16LE is required by the JLS; unreachable in practice
        }
        int nul = text.indexOf('\0');
        if (nul >= 0) {
            text = text.substring(0, nul);
        }
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    private static Guid.GUID.ByReference portsClassGuid() {
        Guid.GUID.ByReference guid = new Guid.GUID.ByReference();
        guid.Data1 = PORTS_DATA1;
        guid.Data2 = PORTS_DATA2;
        guid.Data3 = PORTS_DATA3;
        guid.Data4 = PORTS_DATA4.clone();
        return guid;
    }

    private static DEVPROPKEY busReportedDeviceDescKey() {
        DEVPROPKEY key = new DEVPROPKEY();
        key.Data1 = BRDD_DATA1;
        key.Data2 = BRDD_DATA2;
        key.Data3 = BRDD_DATA3;
        key.Data4 = BRDD_DATA4.clone();
        key.pid = BRDD_PID;
        return key;
    }
}
