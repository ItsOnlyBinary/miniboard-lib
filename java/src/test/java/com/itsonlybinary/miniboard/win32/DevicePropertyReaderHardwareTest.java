package com.itsonlybinary.miniboard.win32;

import com.itsonlybinary.miniboard.DiscoveredPort;
import com.itsonlybinary.miniboard.MatchTier;
import com.itsonlybinary.miniboard.MiniBoardDiscovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requires a physically attached MiniBoard on any COM port.
 *
 * <p>Skipped by default. To run it:
 *
 * <pre>{@code gradlew test -Phardware=true}</pre>
 *
 * <p>This is the committed replacement for the throwaway probe that originally
 * verified Task 6b's acceptance criterion. Without it, nothing in the suite would
 * fail if the PnP property read silently stopped working — every other test in this
 * package uses synthetic values or asserts only that the reader degrades quietly,
 * both of which stay green when the native layer returns nothing at all.
 *
 * <p><strong>Opens no serial port.</strong> {@code SetupDiGetClassDevs} and the
 * registry walk read the PnP database only; no handle to the device is obtained, so
 * DTR is never asserted and the board is not reset. Nothing here writes to the
 * device.
 *
 * <p>Assertions are on invariants any MiniBoard satisfies — no fixed COM number, no
 * fixed serial, no fixed instance ID — so this passes on any desk with a board
 * plugged in, not just the one it was written on.
 */
@EnabledIfSystemProperty(named = "miniboard.hardware", matches = "true")
class DevicePropertyReaderHardwareTest {

    private static final String TOKEN = "MINIBOARD";

    @Test
    void theBusReportedDescriptionOfSomeAttachedDeviceNamesAMiniBoard() {
        Map<String, String> descriptions = DevicePropertyReader.readBusReportedDescriptions();

        assertFalse(descriptions.isEmpty(),
                "no device reported a bus description at all; either no board is attached"
                        + " or SetupDiGetDevicePropertyW has stopped binding");

        boolean found = false;
        for (String description : descriptions.values()) {
            if (description.toUpperCase(Locale.ROOT).contains(TOKEN)) {
                found = true;
                break;
            }
        }
        assertTrue(found,
                "no attached device reported a description containing \"MiniBoard\";"
                        + " got " + descriptions.values());
    }

    @Test
    void discoveryRanksTheAttachedBoardConfirmed() {
        List<DiscoveredPort> ports = MiniBoardDiscovery.discover();

        boolean confirmed = false;
        for (DiscoveredPort port : ports) {
            if (port.getTier() == MatchTier.CONFIRMED) {
                confirmed = true;
                break;
            }
        }
        assertTrue(confirmed,
                "no port ranked CONFIRMED. PROBABLE means the VID matched but the product"
                        + " string did not reach the tier rule, which is the exact regression"
                        + " Task 6b exists to prevent. Got: " + ports);
    }

    /**
     * The CONFIRMED tier must be driven by the bus-reported description, not by a
     * registry string that happens to carry the token. If usbser.inf's generic text
     * ever started matching, the tier would be right for the wrong reason and the
     * test above would not notice.
     */
    @Test
    void theConfirmedRankingIsDrivenByTheBusReportedDescription() {
        boolean checkedAtLeastOne = false;

        for (UsbPortInfo info : WindowsPortEnumerator.enumerate()) {
            if (info.getBusReportedDescription() == null) {
                continue; // a stale registry entry for an unplugged device
            }
            if (!info.matchesText(TOKEN)) {
                continue; // some other vendor's live CDC device
            }
            checkedAtLeastOne = true;

            assertTrue(info.getBusReportedDescription().toUpperCase(Locale.ROOT).contains(TOKEN),
                    "the bus-reported description must be what carries the token: " + info);
        }

        assertTrue(checkedAtLeastOne,
                "no attached device carried a bus-reported description containing the token");
    }
}
