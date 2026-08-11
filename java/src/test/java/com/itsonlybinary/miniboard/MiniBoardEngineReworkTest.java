package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Crc8;
import com.itsonlybinary.miniboard.protocol.Opcode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted regression coverage for the 2026-07-28 Task 7 rework (R2, R4, R7,
 * R8, R9). Drives {@link MiniBoardEngine} directly through a {@link FakeTransport}
 * so no hardware and none of Task 8's command methods are required.
 *
 * <p>Every test here was run against the pre-fix engine (commit a922201, the pure
 * R1 extraction with every original defect intact). Five of the six failed there;
 * see the Task 7 report for the recorded output. The exception is
 * {@link #concurrentCloseFiresOnDisconnectedExactlyOnce()}: R4's double-teardown
 * window is only a few nanoseconds wide and could not be forced by racing
 * {@code close()} calls (0 hits in 1200 attempts), so that test is a stress
 * regression guard rather than proof. R4 is pinned deterministically by
 * {@link #closeDuringTransportOpenLeavesNoReaderAndTearsDownOnce()} instead.
 */
class MiniBoardEngineReworkTest {

    private static byte[] eventFrame(int evt, byte[] data) {
        byte[] raw = new byte[4 + data.length];
        raw[0] = (byte) 0xAA;
        raw[1] = (byte) evt;
        raw[2] = (byte) data.length;
        System.arraycopy(data, 0, raw, 3, data.length);
        raw[raw.length - 1] = (byte) Crc8.compute(raw, 1, raw.length - 2);
        return raw;
    }

    /** Builds one chunk containing the six hello events the handshake requires. */
    private static byte[] helloSequence() {
        byte[] name = eventFrame(0x01, asciiBytes("MiniBoard54"));
        byte[] version = eventFrame(0x02, new byte[] {1, 0});
        byte[] serial = eventFrame(0x03, new byte[] {0x01, 0x02, 0x03, 0x04});
        byte[] side = eventFrame(0x04, new byte[] {0x01});
        byte[] type = eventFrame(0x05, new byte[] {0x00});
        byte[] status = eventFrame(0x06, new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        byte[] all = new byte[name.length + version.length + serial.length
                + side.length + type.length + status.length];
        int pos = 0;
        for (byte[] part : new byte[][] {name, version, serial, side, type, status}) {
            System.arraycopy(part, 0, all, pos, part.length);
            pos += part.length;
        }
        return all;
    }

    /** Builds a device-to-host response frame: SOF, CMD, STATUS, LEN, DATA, CRC. */
    private static byte[] responseFrame(int opcode, int status, byte[] data) {
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

    private static DeviceInfo connectAndAwaitReady(MiniBoardEngine engine, FakeTransport transport)
            throws Exception {
        CompletableFuture<DeviceInfo> future = engine.connect(null);
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");
        transport.offer(helloSequence());
        DeviceInfo info = future.get(3, TimeUnit.SECONDS);
        assertNotNull(info);
        assertTrue(engine.isConnected());
        return info;
    }

    private static void waitForWriteCount(FakeTransport transport, int count, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (transport.getWritten().size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + count
                        + " write(s); saw " + transport.getWritten().size());
            }
            Thread.sleep(10);
        }
    }

    // ---------------------------------------------------------------- R2

    /**
     * Must fail against the pre-fix engine: unfixed {@code shutdown()} never
     * touches {@code connectFuture} or {@code handshakeTimeout}, so the connect
     * future never completes and this hangs until {@code assertTimeoutPreemptively}
     * times out.
     */
    @Test
    void closeDuringHandshakeCompletesConnectFutureExceptionally() throws Exception {
        FakeTransport transport = new FakeTransport();
        final MiniBoardEngine engine = new MiniBoardEngine("TEST-R2", transport);
        final CompletableFuture<DeviceInfo> connectFuture = engine.connect(null);

        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");
        // No hello bytes are ever offered: the handshake cannot complete on its own.

        engine.close();

        assertTimeoutPreemptively(Duration.ofSeconds(2), new Executable() {
            @Override
            public void execute() {
                ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        connectFuture.get();
                    }
                });
                assertTrue(ex.getCause() instanceof MiniBoardException,
                        "expected MiniBoardException, got " + ex.getCause());
            }
        });
    }

    // ---------------------------------------------------------------- R4

    /**
     * Races many concurrent {@code close()} calls against a connected engine.
     * The pre-fix guard reads {@code running}/{@code state} and writes
     * {@code running = false} in different critical sections, so two callers can
     * both see "first shutdown" and both fire {@code onDisconnected}.
     *
     * <p>{@code onDisconnected} is delivered asynchronously on the callback
     * thread, so a trial's counter must never be read the instant {@code close()}
     * returns. Each trial waits for its first delivery on a latch — which also
     * asserts that exactly-once is not achieved by firing zero times — and the
     * counters are only totalled at the end, after a grace period long enough for
     * any extra firing to have landed.
     */
    @Test
    void concurrentCloseFiresOnDisconnectedExactlyOnce() throws Exception {
        final int trials = 150;
        final int threadsPerTrial = 6;
        List<AtomicInteger> counters = new ArrayList<AtomicInteger>();

        for (int trial = 0; trial < trials; trial++) {
            FakeTransport transport = new FakeTransport();
            final MiniBoardEngine engine = new MiniBoardEngine("TEST-R4-" + trial, transport);
            final AtomicInteger disconnectCount = new AtomicInteger();
            final CountDownLatch fired = new CountDownLatch(1);
            counters.add(disconnectCount);
            engine.setListener(new MiniBoardAdapter() {
                @Override
                public void onDisconnected(Throwable cause) {
                    disconnectCount.incrementAndGet();
                    fired.countDown();
                }
            });

            connectAndAwaitReady(engine, transport);

            final CyclicBarrier barrier = new CyclicBarrier(threadsPerTrial);
            Thread[] closers = new Thread[threadsPerTrial];
            for (int i = 0; i < threadsPerTrial; i++) {
                closers[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            barrier.await();
                        } catch (Exception ignored) {
                            // proceed anyway
                        }
                        engine.close();
                    }
                });
                closers[i].setDaemon(true);
            }
            for (Thread t : closers) {
                t.start();
            }
            for (Thread t : closers) {
                t.join(5000);
            }

            assertTrue(fired.await(5, TimeUnit.SECONDS),
                    "trial " + trial + ": onDisconnected never fired at all");
        }

        // Every trial has fired at least once. Give the callback threads room to
        // deliver a second, erroneous firing before declaring exactly-once.
        Thread.sleep(500);

        int total = 0;
        for (AtomicInteger counter : counters) {
            total += counter.get();
        }
        assertEquals(trials, total,
                "each of " + trials + " trials should fire onDisconnected exactly once; "
                        + "got " + total + " total firings");
    }

    // ------------------------------------------------------------- R4 + R8

    /**
     * The deterministic counterpart to the stress test above, and the only test
     * here that forces R4's double teardown rather than hoping to race it.
     *
     * <p>{@code close()} lands while {@code transport.open()} is still blocking.
     * The pre-fix engine tears down there and then, and {@code connect()} — which
     * neither re-acquires the lock nor re-checks the state — afterwards assigns
     * {@code running = true} and starts a reader on a port the engine believes is
     * closed. That reader's first failure drives a *second* full teardown past a
     * guard that only ever consults {@code running}: two {@code transport.close()}
     * calls, two executor shutdowns.
     *
     * <p>Must fail against the pre-fix engine on the very first assertion: the
     * leaked reader thread is still alive.
     */
    @Test
    void closeDuringTransportOpenLeavesNoReaderAndTearsDownOnce() throws Exception {
        final String port = "TEST-R8";
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch releaseOpen = new CountDownLatch(1);
        final FakeTransport transport = new FakeTransport();
        transport.blockOpen(openEntered, releaseOpen);

        final MiniBoardEngine engine = new MiniBoardEngine(port, transport);
        final AtomicInteger disconnectCount = new AtomicInteger();
        engine.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
            }
        });

        final AtomicReference<CompletableFuture<DeviceInfo>> connectRef =
                new AtomicReference<CompletableFuture<DeviceInfo>>();
        Thread connector = new Thread(new Runnable() {
            @Override
            public void run() {
                connectRef.set(engine.connect(null));
            }
        }, "test-connector");
        connector.setDaemon(true);
        connector.start();

        assertTrue(openEntered.await(5, TimeUnit.SECONDS), "transport.open() was never entered");
        engine.close(); // squarely inside the open() window

        releaseOpen.countDown();
        connector.join(5000);
        assertFalse(connector.isAlive(), "connect() never returned");

        // A reader on a port the engine has already given up on is a leaked thread
        // holding a leaked handle: connect() must re-check the state after open().
        // Prefix, not exact name: reader threads carry their connection generation
        // as a suffix, so an exact-name lookup would match nothing and pass
        // vacuously however many readers had been leaked.
        assertNull(findThreadStartingWith("miniboard-reader-" + port),
                "close() during transport.open() left a live reader thread");

        // Any reader that did start would now drive a second, duplicate teardown.
        transport.offerEof();
        Thread.sleep(300);

        assertEquals(1, transport.getCloseCount(),
                "the port must be closed exactly once, by connect()'s abort path");
        assertEquals(0, disconnectCount.get(),
                "the port never finished opening, so there was no connection to lose");

        CompletableFuture<DeviceInfo> connectFuture = connectRef.get();
        assertNotNull(connectFuture, "connect() returned no future");
        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                connectRef.get().get(2, TimeUnit.SECONDS);
            }
        });
        assertTrue(ex.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + ex.getCause());
    }

    /** @return a live thread whose name starts with this prefix, or null if there is none. */
    private static Thread findThreadStartingWith(String prefix) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith(prefix) && t.isAlive()) {
                return t;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- R7

    /**
     * Must fail against the pre-fix engine: the unfixed shutdown guard fires
     * for a never-connected board too, delivering a spurious {@code onDisconnected(null)}.
     */
    @Test
    void closeOnNeverConnectedEngineFiresNoDisconnectAndDoesNotThrow() throws Exception {
        FakeTransport transport = new FakeTransport();
        final MiniBoardEngine engine = new MiniBoardEngine("TEST-R7", transport);
        final AtomicInteger disconnectCount = new AtomicInteger();
        engine.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnectCount.incrementAndGet();
            }
        });

        assertDoesNotThrow(new Executable() {
            @Override
            public void execute() {
                engine.close();
            }
        });

        Thread.sleep(200); // grace period for any (incorrect) async callback to land
        assertEquals(0, disconnectCount.get(),
                "a board that never connected must not fire onDisconnected");
        assertEquals(0, transport.getCloseCount(),
                "a transport that was never opened should not be touched by close()");
    }

    // ---------------------------------------------------------------- R9 (bound)

    /**
     * Must fail against the pre-fix engine: the unfixed queue is unbounded, so
     * the 65th submission just queues forever instead of being rejected, and
     * {@code overflow.get(2, SECONDS)} throws {@code TimeoutException} rather
     * than the expected {@code ExecutionException}.
     *
     * <p>Driven from {@code CONNECTING}, not {@code IDLE}: since Task 4,
     * {@code submit()} rejects outright while {@code IDLE}, so queuing is no
     * longer legal there. {@code CONNECTING} is still the state that legitimately
     * queues — the deliberate deadline-at-handshake-completion behaviour — and
     * {@code dispatchNext()} refuses to dispatch anything until {@code state ==
     * READY}, so nothing drains the queue while this test fills it. No hello is
     * offered, so the handshake never completes and the engine stays in
     * {@code CONNECTING} for the duration of this test's work — the 3000 ms
     * handshake timeout is armed and would eventually return it to {@code IDLE}.
     *
     * <p>The {@code contains("64")} assertion is load-bearing, not decoration: it is
     * what makes losing that handshake race fail loudly instead of passing for the
     * wrong reason. An {@code IDLE} engine would refuse the 65th submission too, but
     * with the IDLE message, which contains no "64" — so do not simplify that
     * assertion down to {@code instanceof MiniBoardException}.
     *
     * <p>The trailing assertion after {@code close()} pins a second invariant that
     * nothing else in the suite covers: a command queued in {@code CONNECTING} is
     * resolved by the terminal teardown's drain. That drain is the only thing that
     * can ever complete such a future, so a regression there would leave a caller's
     * future unresolvable for the life of the JVM.
     */
    @Test
    void submittingPastMaxQueuedCommandsFailsFast() throws Exception {
        FakeTransport transport = new FakeTransport();
        MiniBoardEngine engine = new MiniBoardEngine("TEST-R9-BOUND", transport);
        final List<CompletableFuture<byte[]>> queued =
                new ArrayList<CompletableFuture<byte[]>>();
        try {
            engine.connect(null);
            assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS),
                    "transport.open() was never called");

            // CONNECTING, and no hello queued: nothing is dispatched and — per R11 —
            // no deadline is armed either. These 64 must simply be accepted; the
            // point of the assertion is that the cap rejects rather than silently
            // completing.
            for (int i = 0; i < MiniBoard.MAX_QUEUED_COMMANDS; i++) {
                queued.add(engine.submit(Opcode.GET_DEBOUNCE, new byte[0]));
            }
            for (CompletableFuture<byte[]> f : queued) {
                assertFalse(f.isDone(),
                        "a command accepted below the cap must not be completed by submit()");
            }

            final CompletableFuture<byte[]> overflow =
                    engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);

            ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    overflow.get(2, TimeUnit.SECONDS);
                }
            });
            assertTrue(ex.getCause() instanceof MiniBoardException,
                    "expected MiniBoardException, got " + ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("64"), ex.getCause().getMessage());
        } finally {
            // In a finally because this test opens a transport and starts a live
            // reader thread: a mid-test failure would otherwise leak both plus two
            // executors for the rest of the JVM.
            engine.close();
        }

        // close()'s terminal teardown drains the 64 still queued in CONNECTING.
        ExecutionException drained = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                queued.get(0).get(5, TimeUnit.SECONDS);
            }
        }, "a command queued in CONNECTING must be resolved by the teardown drain,"
                + " which is the only thing that ever can resolve it");
        assertTrue(drained.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + drained.getCause());
        assertTrue(drained.getCause().getMessage().contains("closed"),
                "the drain must report the connection closing: "
                        + drained.getCause().getMessage());
    }

    // ---------------------------------------------------------------- R11

    /**
     * A command submitted before the handshake completes must not be charged for
     * the handshake wait. {@code GET_DEBOUNCE} carries a 1000 ms timeout while the
     * handshake budget is 3000 ms, so a device that hellos after 1.5 s is entirely
     * legitimate — and {@code board.connect(); board.getDebounce();} must still
     * succeed. The deadline starts at {@code submit()} or at handshake completion,
     * whichever is later.
     *
     * <p>Must fail against the fix-round-1 engine, which armed every deadline in
     * {@code submit()}: the command is failed with {@code MiniBoardTimeoutException}
     * at t=1000 ms, before the device has even said hello.
     */
    @Test
    void commandQueuedBeforeASlowHandshakeIsNotChargedForTheHandshakeWait() throws Exception {
        FakeTransport transport = new FakeTransport();
        MiniBoardEngine engine = new MiniBoardEngine("TEST-R11", transport);

        CompletableFuture<DeviceInfo> connectFuture = engine.connect(null);
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");

        // Submitted while still CONNECTING: queued by design, never written (invariant 2).
        CompletableFuture<byte[]> queued = engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);
        assertTrue(transport.getWritten().isEmpty(),
                "a command submitted before the handshake must not go on the wire");

        // The device takes 1.5 s to hello: over GET_DEBOUNCE's 1000 ms timeout, but
        // comfortably inside the 3000 ms handshake budget.
        Thread.sleep(1500);
        assertFalse(queued.isDone(),
                "the handshake window must not consume a queued command's deadline");

        transport.offer(helloSequence());
        assertNotNull(connectFuture.get(3, TimeUnit.SECONDS), "the handshake should have succeeded");

        // Only now does the command go out, and only now does its own clock start.
        waitForWriteCount(transport, 1, 2000);
        transport.offer(responseFrame(Opcode.GET_DEBOUNCE.getValue(), 0x00, new byte[] {42}));

        byte[] data = queued.get(2, TimeUnit.SECONDS);
        assertNotNull(data);
        assertEquals(1, data.length, "GET_DEBOUNCE returns one byte");
        assertEquals(42, data[0] & 0xFF);

        engine.close();
    }

    // ---------------------------------------------------------------- R9 (deadline)

    /**
     * Must fail against the pre-fix engine: the unfixed design only schedules a
     * command's timeout at dispatch time, so a command stuck behind a slower,
     * never-answered in-flight command is never given a chance to time out on
     * its own — {@code queued.get(3, SECONDS)} throws {@code TimeoutException}
     * rather than completing with {@code MiniBoardTimeoutException}.
     */
    @Test
    void queuedCommandExpiresBeforeEverBeingDispatched() throws Exception {
        FakeTransport transport = new FakeTransport();
        MiniBoardEngine engine = new MiniBoardEngine("TEST-R9-DEADLINE", transport);
        connectAndAwaitReady(engine, transport);

        // SAVE has a 5000 ms timeout and the fake device never answers it, so it
        // occupies the "in flight" slot for far longer than GET_DEBOUNCE's 1000 ms.
        CompletableFuture<byte[]> blocking = engine.submit(Opcode.SAVE, new byte[0]);
        waitForWriteCount(transport, 1, 2000);

        final CompletableFuture<byte[]> queued =
                engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                queued.get(3, TimeUnit.SECONDS);
            }
        });
        assertTrue(ex.getCause() instanceof MiniBoardTimeoutException,
                "expected MiniBoardTimeoutException, got " + ex.getCause());

        assertFalse(blocking.isDone(),
                "the in-flight SAVE should still be waiting on its own, longer timeout");

        engine.close();
    }
}
