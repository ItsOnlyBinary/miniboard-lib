package com.itsonlybinary.miniboard.transport;

import java.io.IOException;

/**
 * Byte-level I/O to a device. Exists so the protocol and command layers never
 * touch a serial API directly.
 */
public interface Transport {

    /**
     * Opens the port and asserts DTR, which triggers the device's hello sequence.
     *
     * @throws IOException if the port does not exist, is in use, or cannot be configured
     */
    void open(String portName) throws IOException;

    /** Writes all bytes. Blocking, but bounded by the OS write buffer. */
    void write(byte[] data) throws IOException;

    /**
     * Reads available bytes, blocking up to the configured receive timeout.
     *
     * @return byte count, {@code 0} if the receive timeout elapsed with no data,
     *         or {@code -1} if the port reached end-of-stream. Callers must treat
     *         0 and -1 differently: 0 is normal idling, -1 means the port is gone.
     */
    int read(byte[] buffer) throws IOException;

    /** Closes the port. Idempotent, and must never throw. */
    void close();

    boolean isOpen();

    /** @return the port name passed to {@link #open}, or null if never opened. */
    String getPortName();
}
