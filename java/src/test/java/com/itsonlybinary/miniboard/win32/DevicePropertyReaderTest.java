package com.itsonlybinary.miniboard.win32;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardware-independent. On a machine with no serial ports the map is simply
 * empty, which is the supported outcome — the assertions here are about the
 * reader never throwing and never returning null, which is what
 * {@link WindowsPortEnumerator} relies on to degrade gracefully.
 */
class DevicePropertyReaderTest {

    @Test
    void returnsAnEmptyMapNotAnExceptionWhenNotWindows() {
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            assertFalse(WindowsPortEnumerator.isWindows());

            Map<String, String> result =
                    assertDoesNotThrow(DevicePropertyReader::readBusReportedDescriptions);

            assertNotNull(result);
            assertTrue(result.isEmpty(),
                    "no JNA type may be touched off Windows, so the map must be empty");
        } finally {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    void singleLookupReturnsNullNotAnExceptionWhenNotWindows() {
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");

            assertNull(assertDoesNotThrow(
                    () -> DevicePropertyReader.readBusReportedDeviceDesc(
                            "USB\\VID_CAFE&PID_4005&MI_00\\6&295c3188&0&0000")));
        } finally {
            System.setProperty("os.name", originalOsName);
        }
    }

    @Test
    void nullInstanceIdIsRejectedRatherThanLookedUp() {
        assertNull(assertDoesNotThrow(
                () -> DevicePropertyReader.readBusReportedDeviceDesc(null)));
    }

    /**
     * On this host the reader really does call into {@code setupapi.dll}. Whatever
     * it finds, the contract must hold: a non-null map, no null keys or values, and
     * no exception escaping — a locked-down machine or a JNA binding failure must
     * look identical to "no properties found".
     */
    @Test
    void readingOnTheHostHonoursTheContractWhateverTheHardware() {
        Map<String, String> result =
                assertDoesNotThrow(DevicePropertyReader::readBusReportedDescriptions);

        assertNotNull(result);
        for (Map.Entry<String, String> e : result.entrySet()) {
            assertNotNull(e.getKey(), "instance ID keys must never be null");
            assertNotNull(e.getValue(), "descriptions must never be null; absent means absent");
            assertFalse(e.getValue().isEmpty(), "an empty description must be reported as absent");
            // Locale.ROOT, like the reader itself: under a Turkish default locale
            // an unpinned toUpperCase turns "i" into "İ" and this assertion would
            // fail on keys the reader upper-cased perfectly correctly.
            assertTrue(e.getKey().equals(e.getKey().toUpperCase(Locale.ROOT)),
                    "keys must be upper-cased so the enumerator's join is case-insensitive");
        }
    }

    /**
     * Two consecutive reads must agree — proof the device-info set is not reused
     * stale between passes. Deliberately <em>not</em> named for handle exhaustion:
     * 21 iterations against a per-process handle table in the thousands could
     * never detect a leak, and a name claiming coverage the test does not have is
     * worse than no name at all.
     */
    @Test
    void repeatedReadsAreStable() {
        Map<String, String> first = DevicePropertyReader.readBusReportedDescriptions();
        for (int i = 0; i < 20; i++) {
            assertEqualsMap(first, DevicePropertyReader.readBusReportedDescriptions());
        }
    }

    private static void assertEqualsMap(Map<String, String> expected, Map<String, String> actual) {
        assertNotNull(actual);
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual,
                "repeated reads disagreed; the SetupAPI pass is not repeatable");
    }
}
