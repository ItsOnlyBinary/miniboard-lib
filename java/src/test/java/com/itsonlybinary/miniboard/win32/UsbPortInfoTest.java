package com.itsonlybinary.miniboard.win32;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the text-matching rule with synthetic values, so it is asserted
 * directly rather than inferred from whatever hardware the host happens to have.
 */
class UsbPortInfoTest {

    private static final String TOKEN = "MINIBOARD";

    /** The real instance path of the attached board's CDC interface. */
    private static final String REAL_HARDWARE_ID =
            "USB\\VID_CAFE&PID_4005&MI_00\\6&295c3188&0&0000";

    private static UsbPortInfo info(String hardwareId, String friendly, String deviceDesc,
                                    String mfg, String busReported) {
        return new UsbPortInfo("COM6", hardwareId, friendly, deviceDesc, mfg,
                busReported, 0xCAFE, 0x4005);
    }

    @Test
    void tokenIsFoundInTheBusReportedDescription() {
        assertTrue(info(REAL_HARDWARE_ID, "USB Serial Device (COM6)", "USB Serial Device",
                "Microsoft", "MiniBoard CDC").matchesText(TOKEN));
    }

    @Test
    void tokenIsFoundInTheFriendlyName() {
        assertTrue(info(REAL_HARDWARE_ID, "MiniBoard54 (COM6)", "USB Serial Device",
                "Microsoft", null).matchesText(TOKEN));
    }

    @Test
    void tokenIsFoundInTheDeviceDesc() {
        assertTrue(info(REAL_HARDWARE_ID, null, "MiniBoard54 CDC Device",
                "Microsoft", null).matchesText(TOKEN));
    }

    @Test
    void tokenIsFoundInTheManufacturer() {
        assertTrue(info(REAL_HARDWARE_ID, null, "USB Serial Device",
                "MiniBoard Industries", null).matchesText(TOKEN));
    }

    /**
     * Regression guard. The hardware ID is a VID/PID/serial path and never carries
     * the product string, but a third-party TinyUSB device — 0xCAFE is the shared
     * example VID — could put "MiniBoard" in its serial number and be wrongly
     * promoted to the top tier.
     */
    @Test
    void tokenInTheHardwareIdAloneDoesNotMatch() {
        UsbPortInfo impostor = info("USB\\VID_CAFE&PID_9999\\MINIBOARD_LOOKALIKE",
                "USB Serial Device (COM9)", "USB Serial Device", "Microsoft", null);

        assertFalse(impostor.matchesText(TOKEN),
                "hardwareId must not be searched: a serial number containing the token"
                        + " would otherwise fake a top-tier match");
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(info(null, null, null, null, "miniboard cdc").matchesText(TOKEN));
        assertTrue(info(null, null, null, null, "MINIBOARD CDC").matchesText(TOKEN));
        assertTrue(info(null, null, null, null, "MiniBoard CDC").matchesText("miniboard"));
    }

    /**
     * Regression guard for a locale defect. Under a Turkish or Azeri default
     * locale, {@code "MiniBoard CDC".toUpperCase()} produces {@code "MİNİBOARD CDC"}
     * with dotted capital I, while the constant {@code "MINIBOARD"} upper-cases to
     * itself — so {@code contains} fails and an attached board silently drops from
     * CONFIRMED to PROBABLE. That is exactly the bug this whole feature exists to
     * fix, reintroduced by the user's locale. The case fold must be
     * {@link Locale#ROOT}, not the default locale.
     */
    @Test
    void matchingSurvivesATurkishDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            assertTrue(info(REAL_HARDWARE_ID, "USB Serial Device (COM6)", "USB Serial Device",
                            "@usbser.inf,%msft%;Microsoft", "MiniBoard CDC").matchesText(TOKEN),
                    "the dotted/dotless I of the Turkish locale must not break matching");

            // Both directions of the fold: the needle is also case-normalised.
            assertTrue(info(null, null, null, null, "MINIBOARD CDC").matchesText("MiniBoard"));
            assertTrue(info(null, "MiniBoard54 (COM6)", null, null, null).matchesText(TOKEN));
            assertTrue(info(null, null, "MiniBoard54 CDC", null, null).matchesText(TOKEN));

            // A real non-match must still be a non-match, not an accident of folding.
            assertFalse(info(REAL_HARDWARE_ID, "USB Serial Device (COM6)", "USB Serial Device",
                    "@usbser.inf,%msft%;Microsoft", null).matchesText(TOKEN));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void noTokenAnywhereDoesNotMatch() {
        assertFalse(info(REAL_HARDWARE_ID, "USB Serial Device (COM6)", "USB Serial Device",
                "@usbser.inf,%msft%;Microsoft", null).matchesText(TOKEN));
    }

    @Test
    void allNullTextFieldsDoNotMatchAndDoNotThrow() {
        assertFalse(info(null, null, null, null, null).matchesText(TOKEN));
    }

    @Test
    void nullTokenDoesNotMatch() {
        assertFalse(info(null, null, null, null, "MiniBoard CDC").matchesText(null));
    }

    /**
     * Task 6 collapsed FriendlyName and DeviceDesc into one field, so only one was
     * ever consulted. Both must now survive independently.
     */
    @Test
    void friendlyNameAndDeviceDescAreKeptSeparately() {
        UsbPortInfo i = info(REAL_HARDWARE_ID, "USB Serial Device (COM6)",
                "USB Serial Device", "Microsoft", null);

        assertEquals("USB Serial Device (COM6)", i.getFriendlyName());
        assertEquals("USB Serial Device", i.getDeviceDesc());
    }

    @Test
    void getDescriptionPrefersBusReportedThenFriendlyThenDeviceDesc() {
        assertEquals("MiniBoard CDC",
                info(null, "friendly", "desc", null, "MiniBoard CDC").getDescription());
        assertEquals("friendly",
                info(null, "friendly", "desc", null, null).getDescription());
        assertEquals("desc",
                info(null, null, "desc", null, null).getDescription());
        assertNull(info(null, null, null, null, null).getDescription());
    }
}
