package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.transport.SerialTransport;
import com.itsonlybinary.miniboard.win32.UsbPortInfo;
import com.itsonlybinary.miniboard.win32.WindowsPortEnumerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Locates candidate MiniBoard COM ports using USB metadata only.
 *
 * <p><strong>No serial port is ever opened by this class.</strong> Opening a port
 * asserts DTR, which resets or disturbs many unrelated devices. Every port on the
 * system is returned, ranked by {@link MatchTier}, and the caller decides which to
 * open. Identity is confirmed when {@link MiniBoard#connect()} completes.
 */
public final class MiniBoardDiscovery {

    /** USB vendor ID reported by MiniBoard firmware (registered to Raspberry Pi Trading). */
    public static final int MINIBOARD_VID = 0x2E8A;

    /**
     * USB product ID reported by MiniBoard firmware. Shared by every interface of the
     * composite device (CDC, HID, and the parent node all report {@code PID_104E}), so
     * this gates identically to {@link #MINIBOARD_VID} rather than varying per port.
     */
    public static final int MINIBOARD_PID = 0x104E;

    /**
     * Token searched for in the device's descriptive text.
     *
     * <p>On a CDC device this is normally only found in the bus-reported
     * description ({@code "MiniBoard CDC"}); {@code usbser.inf} overwrites the
     * plain registry strings with generic Microsoft text.
     */
    public static final String MINIBOARD_TOKEN = "MINIBOARD";

    private MiniBoardDiscovery() {
    }

    /**
     * Lists every COM port on the system, most likely MiniBoard first.
     *
     * <p>Synchronous because it performs only registry reads — a few milliseconds,
     * with no port I/O. Use {@link #discoverAsync()} to keep it off a UI thread.
     *
     * @return all ports, ranked; empty only if the system has no serial ports
     */
    public static List<DiscoveredPort> discover() {
        List<String> livePorts = SerialTransport.listPortNames();

        // Registry entries persist for devices that were unplugged long ago, so
        // metadata is only trusted for ports that are actually present now.
        Map<String, UsbPortInfo> byPort = new HashMap<String, UsbPortInfo>();
        for (UsbPortInfo info : WindowsPortEnumerator.enumerate()) {
            // Locale.ROOT on both sides of this join, for the same reason as the
            // instance-ID join in WindowsPortEnumerator: the fold must not vary
            // with the host's locale.
            byPort.put(info.getPortName().toUpperCase(Locale.ROOT), info);
        }

        List<DiscoveredPort> result = new ArrayList<DiscoveredPort>(livePorts.size());
        for (String port : livePorts) {
            UsbPortInfo info = byPort.get(port.toUpperCase(Locale.ROOT));
            result.add(new DiscoveredPort(port, classify(info),
                    info != null ? info.getHardwareId() : null,
                    info != null ? info.getDescription() : null));
        }

        Collections.sort(result, RANKING);
        return result;
    }

    /** Same as {@link #discover()}, off the calling thread. */
    public static CompletableFuture<List<DiscoveredPort>> discoverAsync() {
        return CompletableFuture.supplyAsync(new java.util.function.Supplier<List<DiscoveredPort>>() {
            @Override
            public List<DiscoveredPort> get() {
                return discover();
            }
        });
    }

    /**
     * @return the highest-ranked port, or {@code null} if the system has no serial
     *         ports at all. May return an {@link MatchTier#UNKNOWN} port, so check
     *         {@link DiscoveredPort#getTier()} before trusting it.
     */
    public static DiscoveredPort findBest() {
        List<DiscoveredPort> ports = discover();
        return ports.isEmpty() ? null : ports.get(0);
    }

    /** @return only CONFIRMED and PROBABLE ports. */
    public static List<DiscoveredPort> findLikely() {
        List<DiscoveredPort> out = new ArrayList<DiscoveredPort>();
        for (DiscoveredPort p : discover()) {
            if (p.isLikelyMiniBoard()) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The central ranking rule. Both {@code CONFIRMED} and {@code PROBABLE} require the
     * MiniBoard VID <em>and</em> PID; {@code CONFIRMED} additionally requires the product
     * token in one of the searched text fields. Anything else is {@code UNKNOWN}.
     *
     * <p>Package-private rather than private so it can be tested directly against
     * synthetic {@link UsbPortInfo} values, with no hardware attached.
     */
    static MatchTier classify(UsbPortInfo info) {
        if (info == null || info.getVendorId() != MINIBOARD_VID || info.getProductId() != MINIBOARD_PID) {
            return MatchTier.UNKNOWN;
        }
        return info.matchesText(MINIBOARD_TOKEN) ? MatchTier.CONFIRMED : MatchTier.PROBABLE;
    }

    /** Tier first, then numeric COM index, so ordering is stable across runs. */
    private static final Comparator<DiscoveredPort> RANKING =
            new Comparator<DiscoveredPort>() {
                @Override
                public int compare(DiscoveredPort a, DiscoveredPort b) {
                    int byTier = a.getTier().compareTo(b.getTier());
                    if (byTier != 0) {
                        return byTier;
                    }
                    int na = portNumber(a.getPortName());
                    int nb = portNumber(b.getPortName());
                    if (na != nb) {
                        return na < nb ? -1 : 1;
                    }
                    return a.getPortName().compareTo(b.getPortName());
                }
            };

    /** @return the numeric part of "COM5", or MAX_VALUE for non-COM names. */
    private static int portNumber(String name) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
