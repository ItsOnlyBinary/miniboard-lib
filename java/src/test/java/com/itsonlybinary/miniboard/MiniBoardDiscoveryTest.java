package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.transport.SerialTransport;
import com.itsonlybinary.miniboard.win32.UsbPortInfo;
import com.itsonlybinary.miniboard.win32.WindowsPortEnumerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests never open a serial port and never require one to be attached:
 * {@link MiniBoardDiscovery#discover()} is registry-plus-enumeration only.
 * Because the result depends on whatever hardware happens to be on the host
 * (on the reference machine used during development, zero serial ports are
 * present), most assertions here are on <em>invariants</em> that hold
 * regardless of hardware, rather than on specific ports or tiers. Where
 * synthetic {@link DiscoveredPort} values can exercise the real ranking logic
 * without touching the registry, we do that instead of accepting a vacuous
 * check on an empty list.
 */
class MiniBoardDiscoveryTest {

    @Test
    void discoverNeverThrowsAndEveryPortHasNonNullNameAndTier() {
        List<DiscoveredPort> ports = assertDoesNotThrow(MiniBoardDiscovery::discover);

        assertNotNull(ports, "discover() must never return null, even with no hardware attached");
        for (DiscoveredPort p : ports) {
            assertNotNull(p.getPortName(), "port name must never be null");
            assertNotNull(p.getTier(), "tier must never be null");
        }
    }

    @Test
    void discoverIsSortedSoNoUnknownPrecedesAConfirmedOrProbablePort() {
        List<DiscoveredPort> ports = MiniBoardDiscovery.discover();

        boolean sawUnknown = false;
        for (DiscoveredPort p : ports) {
            if (p.getTier() == MatchTier.UNKNOWN) {
                sawUnknown = true;
            } else if (sawUnknown) {
                throw new AssertionError("CONFIRMED/PROBABLE port " + p
                        + " appeared after an UNKNOWN port; ranking is broken");
            }
        }
    }

    @Test
    void everyDiscoveredPortNameAppearsInTheLivePortList() {
        List<String> livePorts = SerialTransport.listPortNames();
        List<DiscoveredPort> discovered = MiniBoardDiscovery.discover();

        for (DiscoveredPort p : discovered) {
            assertTrue(livePorts.contains(p.getPortName()),
                    "discover() surfaced " + p.getPortName()
                            + " which is not a live port; a stale registry entry"
                            + " for an unplugged device leaked through as a phantom port");
        }
    }

    @Test
    void findLikelyReturnsOnlyPortsWhereIsLikelyMiniBoardIsTrue() {
        List<DiscoveredPort> likely = MiniBoardDiscovery.findLikely();

        for (DiscoveredPort p : likely) {
            assertTrue(p.isLikelyMiniBoard(),
                    "findLikely() returned " + p + " but isLikelyMiniBoard() is false for it");
        }
        assertTrue(likely.size() <= MiniBoardDiscovery.discover().size());
    }

    @Test
    void findBestReturnsNullOrTheFirstElementOfDiscover() {
        DiscoveredPort best = MiniBoardDiscovery.findBest();
        List<DiscoveredPort> all = MiniBoardDiscovery.discover();

        if (all.isEmpty()) {
            assertNull(best, "with no ports at all, findBest() must return null");
        } else {
            assertNotNull(best);
            DiscoveredPort first = all.get(0);
            assertEquals(first.getPortName(), best.getPortName());
            assertEquals(first.getTier(), best.getTier());
        }
    }

    @Test
    void windowsPortEnumeratorEnumerateReturnsEmptyNotAnExceptionWhenNotWindows() {
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            assertFalse(WindowsPortEnumerator.isWindows());

            List<UsbPortInfo> result = assertDoesNotThrow(WindowsPortEnumerator::enumerate);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        } finally {
            System.setProperty("os.name", originalOsName);
        }
    }

    /**
     * The same locale defect one layer lower, in the OS gate itself.
     * {@code isWindows()} case-folds {@code os.name} before looking for "win", and
     * under a Turkish locale an upper-case I folds to a <em>dotless</em> ı:
     * {@code "WINDOWS 10".toLowerCase()} is {@code "wındows 10"}, which does not
     * contain "win". {@code isWindows()} would then return false on a genuine
     * Windows host, disabling the registry walk and the property reader outright
     * and dropping every port to UNKNOWN.
     *
     * <p>This is latent rather than live today only because the {@code os.name}
     * values Windows actually reports ("Windows 10", "Windows Server 2019") spell
     * the i in lower case, which the Turkish fold leaves alone — the assertion on
     * "Windows 10" below passes with or without the fix. The upper-case case is the
     * discriminating one, and the gate's contract is a case-insensitive match, so
     * it must not depend on that accident of spelling.
     */
    @Test
    void isWindowsCaseFoldIsLocaleIndependent() {
        String originalOsName = System.getProperty("os.name");
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            System.setProperty("os.name", "WINDOWS 10");
            assertTrue(WindowsPortEnumerator.isWindows(),
                    "the dotless i of the Turkish locale must not hide a Windows host");

            System.setProperty("os.name", "Windows 10");
            assertTrue(WindowsPortEnumerator.isWindows());

            System.setProperty("os.name", "Linux");
            assertFalse(WindowsPortEnumerator.isWindows(),
                    "a non-Windows host must still be reported as such");
        } finally {
            System.setProperty("os.name", originalOsName);
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void discoverAsyncCompletesAndAgreesWithTheSynchronousResult() {
        CompletableFuture<List<DiscoveredPort>> future = MiniBoardDiscovery.discoverAsync();

        List<DiscoveredPort> async = assertDoesNotThrow(() -> future.get());
        List<DiscoveredPort> sync = MiniBoardDiscovery.discover();

        assertNotNull(async);
        assertEquals(sync.size(), async.size());
    }

    /**
     * {@link MatchTier} is declared best-first specifically so that
     * {@code compareTo} ranks correctly; this asserts that ordering directly
     * rather than relying on it holding by accident of declaration order.
     */
    @Test
    void matchTierIsOrderedConfirmedBeforeProbableBeforeUnknown() {
        assertTrue(MatchTier.CONFIRMED.compareTo(MatchTier.PROBABLE) < 0);
        assertTrue(MatchTier.PROBABLE.compareTo(MatchTier.UNKNOWN) < 0);
        assertTrue(MatchTier.CONFIRMED.compareTo(MatchTier.UNKNOWN) < 0);
    }

    @Test
    void isLikelyMiniBoardIsTrueOnlyForConfirmedAndProbable() {
        assertTrue(new DiscoveredPort("COM1", MatchTier.CONFIRMED, null, null).isLikelyMiniBoard());
        assertTrue(new DiscoveredPort("COM2", MatchTier.PROBABLE, null, null).isLikelyMiniBoard());
        assertFalse(new DiscoveredPort("COM3", MatchTier.UNKNOWN, null, null).isLikelyMiniBoard());
    }

    /**
     * The ranking comparator used by {@link MiniBoardDiscovery#discover()} is a
     * private implementation detail, and on a machine with no live ports its
     * effect on {@code discover()}'s output is vacuous. Pulled out via
     * reflection here so the tier-then-port-number ordering it implements gets
     * a real, discriminating assertion instead of one that only holds because
     * the input list happens to be empty.
     */
    @Test
    @SuppressWarnings("unchecked")
    void rankingComparatorOrdersByTierThenByNumericPortIndex() throws Exception {
        Field field = MiniBoardDiscovery.class.getDeclaredField("RANKING");
        field.setAccessible(true);
        Comparator<DiscoveredPort> ranking = (Comparator<DiscoveredPort>) field.get(null);

        DiscoveredPort com10Unknown = new DiscoveredPort("COM10", MatchTier.UNKNOWN, null, null);
        DiscoveredPort com3Probable = new DiscoveredPort("COM3", MatchTier.PROBABLE, null, null);
        DiscoveredPort comXConfirmed = new DiscoveredPort("COMX", MatchTier.CONFIRMED, null, null);
        DiscoveredPort com2Confirmed = new DiscoveredPort("COM2", MatchTier.CONFIRMED, null, null);
        DiscoveredPort com1Confirmed = new DiscoveredPort("COM1", MatchTier.CONFIRMED, null, null);

        List<DiscoveredPort> shuffled = new ArrayList<DiscoveredPort>(Arrays.asList(
                com10Unknown, com3Probable, comXConfirmed, com2Confirmed, com1Confirmed));
        Collections.sort(shuffled, ranking);

        List<String> orderedNames = new ArrayList<String>();
        for (DiscoveredPort p : shuffled) {
            orderedNames.add(p.getPortName());
        }

        assertEquals(Arrays.asList("COM1", "COM2", "COMX", "COM3", "COM10"), orderedNames,
                "expected CONFIRMED ports first (numeric COM index ascending, non-numeric last),"
                        + " then PROBABLE, then UNKNOWN");
    }

    // ---- the tier rule itself, asserted directly with no hardware attached ----

    private static UsbPortInfo port(int vid, String friendly, String deviceDesc,
                                    String mfg, String busReported) {
        return port(vid, MiniBoardDiscovery.MINIBOARD_PID, friendly, deviceDesc, mfg, busReported);
    }

    private static UsbPortInfo port(int vid, int pid, String friendly, String deviceDesc,
                                    String mfg, String busReported) {
        return new UsbPortInfo("COM6", "USB\\VID_2E8A&PID_104E&MI_00\\6&295c3188&0&0000",
                friendly, deviceDesc, mfg, busReported, vid, pid);
    }

    /**
     * The real shape of an attached MiniBoard: usbser.inf has overwritten every
     * plain registry string with generic Microsoft text, and the product name
     * survives only as the bus-reported description. This is the case that made
     * CONFIRMED unreachable before Task 6b.
     */
    @Test
    void miniBoardVidPlusTokenInTheBusReportedDescriptionIsConfirmed() {
        UsbPortInfo info = port(MiniBoardDiscovery.MINIBOARD_VID,
                "USB Serial Device (COM6)", "USB Serial Device",
                "@usbser.inf,%msft%;Microsoft", "MiniBoard CDC");

        assertEquals(MatchTier.CONFIRMED, MiniBoardDiscovery.classify(info));
    }

    @Test
    void miniBoardVidWithNoTokenAnywhereIsProbable() {
        UsbPortInfo info = port(MiniBoardDiscovery.MINIBOARD_VID,
                "USB Serial Device (COM6)", "USB Serial Device",
                "@usbser.inf,%msft%;Microsoft", null);

        assertEquals(MatchTier.PROBABLE, MiniBoardDiscovery.classify(info));
    }

    @Test
    void anotherVendorIsUnknownEvenWhenTheTokenIsPresent() {
        UsbPortInfo info = port(0xCAFE, "MiniBoard54 (COM6)", "MiniBoard54",
                "TinyUSB", "MiniBoard CDC");

        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(info),
                "the VID gate must come first; text alone must never confirm");
    }

    @Test
    void absentMetadataIsUnknown() {
        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(null));
    }

    @Test
    void unparseableVendorIdIsUnknown() {
        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(
                port(-1, "MiniBoard54 (COM6)", "MiniBoard54", "ItsOnlyBinary", "MiniBoard CDC")));
    }

    /**
     * The PID gate must apply even when the token is present, exactly like the VID gate
     * does in {@link #anotherVendorIsUnknownEvenWhenTheTokenIsPresent()}: a third-party
     * RP2040-class device sharing 0x2E8A but not 0x104E must not be promoted to CONFIRMED
     * just because it happens to also encode "MiniBoard" somewhere in its descriptive text.
     */
    @Test
    void correctVidWrongPidIsUnknownEvenWhenTheTokenIsPresent() {
        UsbPortInfo info = port(MiniBoardDiscovery.MINIBOARD_VID, 0x1234,
                "USB Serial Device (COM6)", "USB Serial Device",
                "@usbser.inf,%msft%;Microsoft", "MiniBoard CDC");

        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(info),
                "the PID gate must come first; text alone must never confirm");
    }

    /**
     * Same gate, no token: a wrong PID must not even reach PROBABLE. Before the PID gate
     * existed this fell through to PROBABLE on VID alone.
     */
    @Test
    void correctVidWrongPidWithNoTokenIsUnknown() {
        UsbPortInfo info = port(MiniBoardDiscovery.MINIBOARD_VID, 0x1234,
                "USB Serial Device (COM6)", "USB Serial Device",
                "@usbser.inf,%msft%;Microsoft", null);

        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(info));
    }

    @Test
    void unparseableProductIdIsUnknown() {
        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(
                port(MiniBoardDiscovery.MINIBOARD_VID, -1,
                        "MiniBoard54 (COM6)", "MiniBoard54", "ItsOnlyBinary", "MiniBoard CDC")));
    }

    /**
     * Graceful degradation. If the PnP property reader yields nothing — an older
     * Windows, a locked-down machine, a JNA failure — classification must land
     * exactly where Task 6 left it, driven by the registry strings alone. Losing
     * the new signal may cost a tier; it must never cost a port.
     */
    @Test
    void withNoBusReportedDescriptionClassificationMatchesTask6Behaviour() {
        assertEquals(MatchTier.PROBABLE, MiniBoardDiscovery.classify(
                        port(MiniBoardDiscovery.MINIBOARD_VID, "USB Serial Device (COM6)",
                                "USB Serial Device", "@usbser.inf,%msft%;Microsoft", null)),
                "generic usbser text + MiniBoard VID ranked PROBABLE in Task 6 and must still");

        assertEquals(MatchTier.CONFIRMED, MiniBoardDiscovery.classify(
                        port(MiniBoardDiscovery.MINIBOARD_VID, "MiniBoard54 (COM6)",
                                "USB Serial Device", "ItsOnlyBinary", null)),
                "a driver that does publish the name in FriendlyName still confirms"
                        + " without any PnP property");

        assertEquals(MatchTier.CONFIRMED, MiniBoardDiscovery.classify(
                        port(MiniBoardDiscovery.MINIBOARD_VID, null,
                                "MiniBoard54 CDC", "ItsOnlyBinary", null)),
                "DeviceDesc is an independent source and must be consulted on its own");

        assertEquals(MatchTier.UNKNOWN, MiniBoardDiscovery.classify(
                        port(0x1209, "USB Serial Device (COM6)", "USB Serial Device",
                                "Microsoft", null)),
                "a foreign VID was UNKNOWN in Task 6 and must still be");
    }
}
