package com.itsonlybinary.miniboard;

/** One COM port and the evidence for how likely it is to be a MiniBoard. Immutable. */
public final class DiscoveredPort {

    private final String portName;
    private final MatchTier tier;
    private final String hardwareId;
    private final String description;

    DiscoveredPort(String portName, MatchTier tier, String hardwareId, String description) {
        this.portName = portName;
        this.tier = tier;
        this.hardwareId = hardwareId;
        this.description = description;
    }

    /** @return the port name to pass to {@code new MiniBoard(portName)}, e.g. "COM5". */
    public String getPortName() {
        return portName;
    }

    public MatchTier getTier() {
        return tier;
    }

    /** @return the USB device path, or null if the port is not USB-backed. */
    public String getHardwareId() {
        return hardwareId;
    }

    /** @return the Windows device description, or null if unavailable. */
    public String getDescription() {
        return description;
    }

    /** @return true for CONFIRMED or PROBABLE. */
    public boolean isLikelyMiniBoard() {
        return tier != MatchTier.UNKNOWN;
    }

    @Override
    public String toString() {
        return portName + " [" + tier + "]"
                + (description != null ? " " + description : "")
                + (hardwareId != null ? " (" + hardwareId + ")" : "");
    }
}
