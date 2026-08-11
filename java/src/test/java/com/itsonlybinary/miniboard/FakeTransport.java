package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Crc8;
import com.itsonlybinary.miniboard.transport.Transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable {@link Transport} double for engine-level tests that must not
 * depend on real hardware. {@code read()} polls an inbox queue with a short
 * timeout, mirroring the real contract: 0 for an idle receive timeout, a
 * positive count for data, -1 for end-of-stream (signalled via {@link #offerEof()}).
 */
final class FakeTransport implements Transport {

    private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<byte[]>();
    private final List<byte[]> written = Collections.synchronizedList(new ArrayList<byte[]>());
    private final AtomicInteger closeCount = new AtomicInteger();
    private final CountDownLatch openedLatch = new CountDownLatch(1);
    /** Successful opens so far. Lets a test wait for a *re*open, which a latch cannot. */
    private final AtomicInteger openCount = new AtomicInteger();
    private volatile CountDownLatch readEntered;
    private volatile CountDownLatch readRelease;

    private volatile boolean open;
    private volatile String portName;
    private volatile IOException openFailure;
    private volatile CountDownLatch openEntered;
    private volatile CountDownLatch openRelease;
    private volatile CountDownLatch writeEntered;
    private volatile CountDownLatch writeRelease;
    private volatile RuntimeException writeFailure;

    /** How many threads are inside {@link #write} right now, and the high-water mark. */
    private final AtomicInteger writersInside = new AtomicInteger();
    private final AtomicInteger maxWritersInside = new AtomicInteger();

    /** Marker enqueued via {@link #offerEof()} to make {@code read()} return -1. */
    private static final byte[] EOF_MARKER = new byte[0];

    void failNextOpen(IOException failure) {
        this.openFailure = failure;
    }

    /**
     * Makes {@link #open} park inside the call, so a test can land another
     * operation squarely in the window a real blocking port open would create.
     *
     * @param entered counted down once {@code open()} has been entered
     * @param release awaited by {@code open()} before it completes
     */
    void blockOpen(CountDownLatch entered, CountDownLatch release) {
        this.openEntered = entered;
        this.openRelease = release;
    }

    @Override
    public void open(String portName) throws IOException {
        CountDownLatch entered = openEntered;
        if (entered != null) {
            entered.countDown();
        }
        CountDownLatch release = openRelease;
        if (release != null) {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test never released open()");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while opening", e);
            }
        }
        IOException failure = openFailure;
        if (failure != null) {
            // One-shot, as the name promises: a reconnect test arms a failure for
            // one attempt and expects the retry to succeed.
            openFailure = null;
            throw failure;
        }
        this.portName = portName;
        this.open = true;
        openedLatch.countDown();
        openCount.incrementAndGet();
    }

    /**
     * Parks every {@code write()} inside the call, standing in for a wedged
     * device or a full USB buffer — the condition a real blocking write hits.
     *
     * @param entered counted down each time {@code write()} is entered
     * @param release awaited by {@code write()} before it returns
     */
    void blockWrites(CountDownLatch entered, CountDownLatch release) {
        this.writeEntered = entered;
        this.writeRelease = release;
    }

    /**
     * Makes the next {@link #write} call throw the given unchecked exception
     * instead of recording data, standing in for a bug in a real transport
     * implementation rather than an {@link IOException}. Consumed once: the
     * write after it succeeds normally.
     */
    void failNextWrite(RuntimeException failure) {
        this.writeFailure = failure;
    }

    @Override
    public void write(byte[] data) throws IOException {
        // Count on entry, before any parking: the point of the high-water mark is
        // to catch a second writer arriving while the first is still stuck inside.
        int inside = writersInside.incrementAndGet();
        int seen = maxWritersInside.get();
        while (inside > seen && !maxWritersInside.compareAndSet(seen, inside)) {
            seen = maxWritersInside.get();
        }
        try {
            if (!open) {
                throw new IOException("Port is not open");
            }
            CountDownLatch entered = writeEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = writeRelease;
            if (release != null) {
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("test never released write()");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while writing", e);
                }
            }
            RuntimeException failure = writeFailure;
            if (failure != null) {
                writeFailure = null;
                throw failure;
            }
            written.add(data.clone());
        } finally {
            writersInside.decrementAndGet();
        }
    }

    /** @return the most threads ever simultaneously inside {@link #write}. */
    int getMaxConcurrentWriters() {
        return maxWritersInside.get();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        CountDownLatch entered = readEntered;
        if (entered != null) {
            entered.countDown();
        }
        CountDownLatch release = readRelease;
        if (release != null) {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("test never released read()");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        byte[] chunk;
        try {
            chunk = inbox.poll(30, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (chunk == null) {
            return 0;
        }
        if (chunk == EOF_MARKER) {
            return -1;
        }
        System.arraycopy(chunk, 0, buffer, 0, chunk.length);
        return chunk.length;
    }

    @Override
    public void close() {
        open = false;
        closeCount.incrementAndGet();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String getPortName() {
        return portName;
    }

    // ------------------------------------------------------------- test hooks

    void offer(byte[] bytes) {
        inbox.offer(bytes);
    }

    void offerEof() {
        inbox.offer(EOF_MARKER);
    }

    boolean awaitOpened(long timeout, TimeUnit unit) throws InterruptedException {
        return openedLatch.await(timeout, unit);
    }

    /**
     * Parks every {@code read()} inside the call, standing in for a reader wedged
     * in a native call that will not return when the port closes — the condition
     * {@code connect()}'s reader handoff guard exists to refuse.
     *
     * @param entered counted down each time {@code read()} is entered
     * @param release awaited by {@code read()} before it returns
     */
    void blockReads(CountDownLatch entered, CountDownLatch release) {
        this.readEntered = entered;
        this.readRelease = release;
    }

    int getOpenCount() {
        return openCount.get();
    }

    /** Waits for the nth successful open. Use instead of {@link #awaitOpened} across reconnects. */
    boolean awaitOpenCount(int target, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (openCount.get() < target) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    int getCloseCount() {
        return closeCount.get();
    }

    List<byte[]> getWritten() {
        return written;
    }

    // -------------------------------------------------------- frame builders

    /** Builds a device-to-host event frame: SOF, EVT, LEN, DATA, CRC. */
    static byte[] event(int evt, byte[] data) {
        byte[] raw = new byte[4 + data.length];
        raw[0] = (byte) 0xAA;
        raw[1] = (byte) evt;
        raw[2] = (byte) data.length;
        System.arraycopy(data, 0, raw, 3, data.length);
        raw[raw.length - 1] = (byte) Crc8.compute(raw, 1, raw.length - 2);
        return raw;
    }

    /** Builds a device-to-host response frame: SOF, CMD, STATUS, LEN, DATA, CRC. */
    static byte[] response(int opcode, int status, byte[] data) {
        byte[] raw = new byte[5 + data.length];
        raw[0] = (byte) 0xAA;
        raw[1] = (byte) opcode;
        raw[2] = (byte) status;
        raw[3] = (byte) data.length;
        System.arraycopy(data, 0, raw, 4, data.length);
        raw[raw.length - 1] = (byte) Crc8.compute(raw, 1, raw.length - 2);
        return raw;
    }

    private static byte[] asciiBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    /** Default passing STATUS payload for the current 54-key presets: result=0 (pass),
     * an all-zero 7-byte stuck_keys_bitmap, led_fault=0. */
    private static final byte[] DEFAULT_STATUS = new byte[] {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    /**
     * Offers one chunk containing the six hello events the handshake requires:
     * NAME "MiniBoard54", VERSION 1.0, SERIAL {0x01,0x02,0x03,0x04}, SIDE LEFT,
     * TYPE 0, STATUS (self-test passed, no stuck keys, no LED fault).
     */
    void queueHello() {
        queueHello(new byte[] {0x01, 0x02, 0x03, 0x04});
    }

    /** As {@link #queueHello()}, but with a caller-chosen board serial. */
    void queueHello(byte[] serialBytes) {
        queueHello("MiniBoard54", serialBytes);
    }

    /**
     * As {@link #queueHello(byte[])}, but with a caller-chosen device name, so a
     * test can drive the handshake's name-mismatch rejection. The literal here is
     * deliberately not {@code DeviceInfo.EXPECTED_NAME}: this is the wire, and a
     * fixture that echoed the constant under test could never disagree with it.
     */
    void queueHello(String deviceName, byte[] serialBytes) {
        queueHello(deviceName, serialBytes, DEFAULT_STATUS);
    }

    /** As {@link #queueHello(String, byte[])}, but with a caller-chosen STATUS payload. */
    void queueHello(String deviceName, byte[] serialBytes, byte[] statusPayload) {
        byte[] name = event(0x01, asciiBytes(deviceName));
        byte[] version = event(0x02, new byte[] {1, 0});
        byte[] serial = event(0x03, serialBytes);
        byte[] side = event(0x04, new byte[] {0x01});
        byte[] type = event(0x05, new byte[] {0x00});
        byte[] status = event(0x06, statusPayload);

        byte[] all = new byte[name.length + version.length + serial.length
                + side.length + type.length + status.length];
        int pos = 0;
        for (byte[] part : new byte[][] {name, version, serial, side, type, status}) {
            System.arraycopy(part, 0, all, pos, part.length);
            pos += part.length;
        }
        offer(all);
    }
}
