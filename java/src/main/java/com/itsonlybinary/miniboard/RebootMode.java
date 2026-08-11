package com.itsonlybinary.miniboard;

/** Reboot target for the REBOOT command. */
public enum RebootMode {

    /** Watchdog reset into a normal boot. */
    WATCHDOG(0),
    /** Reboot into the UF2 bootloader (BOOTSEL). */
    USB_BOOT(1);

    private final int value;

    RebootMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
