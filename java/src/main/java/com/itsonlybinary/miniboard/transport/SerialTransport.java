package com.itsonlybinary.miniboard.transport;

import purejavacomm.CommPortIdentifier;
import purejavacomm.NoSuchPortException;
import purejavacomm.PortInUseException;
import purejavacomm.SerialPort;
import purejavacomm.UnsupportedCommOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** purejavacomm-backed {@link Transport}. The only serial-aware class in the library. */
public final class SerialTransport implements Transport {

    /** Nominal baud. USB CDC ignores it, but the port must still be configured. */
    public static final int BAUD_RATE = 115200;

    /** How long {@link #read} sleeps between {@code available()} polls when idle. */
    public static final int POLL_INTERVAL_MS = 20;

    /** How long to wait for exclusive ownership of the port. */
    public static final int OPEN_TIMEOUT_MS = 2000;

    private static final String OWNER = "miniboard-lib";

    private volatile SerialPort port;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile String portName;

    @Override
    public void open(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("Port name is required");
        }
        if (isOpen()) {
            throw new IOException("Already open on " + portName);
        }

        CommPortIdentifier id;
        try {
            id = CommPortIdentifier.getPortIdentifier(name);
        } catch (NoSuchPortException e) {
            throw new IOException("No such port: " + name, e);
        }

        SerialPort sp;
        try {
            sp = (SerialPort) id.open(OWNER, OPEN_TIMEOUT_MS);
        } catch (PortInUseException e) {
            throw new IOException("Port in use: " + name
                    + " (owner: " + e.currentOwner + ")", e);
        } catch (ClassCastException e) {
            throw new IOException("Not a serial port: " + name, e);
        }

        try {
            sp.setSerialPortParams(BAUD_RATE, SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
            sp.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);

            // Deliberately never enableReceiveTimeout(): on Windows that puts
            // purejavacomm's read() on its overlapped-I/O path (ReadFile +
            // WaitForMultipleObjects). If the port physically disappears while a
            // read is blocked there - exactly what happens the instant a REBOOT
            // command lands - that path corrupts native state and takes the whole
            // JVM down (confirmed: reproduces with a bare SerialTransport, no
            // MiniBoardEngine involved, exit code STATUS_INVALID_CRUNTIME_PARAMETER).
            // Polling available() and only calling read() once bytes are actually
            // present avoids that code path entirely and survives the same
            // disconnect with a normal IOException.

            // DTR assertion is what makes the firmware emit its hello sequence,
            // so it goes last, once we are ready to read.
            sp.setDTR(true);
            sp.setRTS(true);

            InputStream newIn = sp.getInputStream();
            OutputStream newOut = sp.getOutputStream();

            // Commit the fields only once every step above has succeeded. Assigning
            // this.in early would leave a half-open object on a later failure:
            // read() guards on `in`, not `port`, so it would skip its "not open"
            // fast-fail and do real I/O against a stream on a closed port.
            this.in = newIn;
            this.out = newOut;
            this.port = sp;
            this.portName = name;
        } catch (UnsupportedCommOperationException e) {
            closeQuietly(sp);
            throw new IOException("Cannot configure port " + name, e);
        } catch (IOException e) {
            closeQuietly(sp);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(sp);
            throw new IOException("Failed opening " + name, e);
        }
    }

    /**
     * Releases a port during failed-open recovery without ever suppressing the
     * exception that caused the recovery. A throwing close() here would both mask
     * the real cause and leave it unclear whether the native handle was released.
     */
    private static void closeQuietly(SerialPort sp) {
        try {
            sp.close();
        } catch (RuntimeException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        OutputStream o = this.out;
        if (o == null) {
            throw new IOException("Port is not open");
        }
        o.write(data);
        o.flush();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        InputStream i = this.in;
        if (i == null) {
            throw new IOException("Port is not open");
        }
        int avail = i.available();
        if (avail <= 0) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
        return i.read(buffer, 0, Math.min(avail, buffer.length));
    }

    @Override
    public void close() {
        SerialPort sp = this.port;
        this.port = null;
        this.in = null;
        this.out = null;
        if (sp != null) {
            try {
                // Dropping DTR tells the firmware to clear session state,
                // including any temporary HID disable.
                sp.setDTR(false);
            } catch (RuntimeException ignored) {
                // Port may already be gone; closing is what matters.
            }
            try {
                sp.close();
            } catch (RuntimeException ignored) {
                // close() must never throw.
            }
        }
    }

    @Override
    public boolean isOpen() {
        return port != null;
    }

    @Override
    public String getPortName() {
        return portName;
    }

    /**
     * Lists serial ports currently present on the system. Opens nothing.
     *
     * @return port names such as "COM5", never null
     */
    public static List<String> listPortNames() {
        List<String> names = new ArrayList<String>();
        try {
            @SuppressWarnings("unchecked")
            Enumeration<CommPortIdentifier> e =
                    (Enumeration<CommPortIdentifier>) CommPortIdentifier.getPortIdentifiers();
            while (e.hasMoreElements()) {
                CommPortIdentifier id = e.nextElement();
                if (id.getPortType() == CommPortIdentifier.PORT_SERIAL) {
                    names.add(id.getName());
                }
            }
        } catch (RuntimeException ignored) {
            // A broken driver must not take discovery down; report what we have.
        }
        Collections.sort(names);
        return names;
    }
}
