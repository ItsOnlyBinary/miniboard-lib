package com.itsonlybinary.miniboard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconnecting one {@link MiniBoard} instance rather than constructing a new one.
 *
 * <p>Every test drives a {@link FakeTransport}; none needs hardware.
 *
 * <p>The synchronisation rule these tests encode: after an <em>unexpected</em>
 * disconnect, wait for {@code onDisconnected} before reconnecting. By the time it
 * fires the port is closed, the reader is joined, and the engine is back in its
 * idle state. {@code disconnect()} is synchronous and needs no wait.
 */
class MiniBoardReconnectTest {

    private static final String PORT = "TEST-RECONNECT";

    /**
     * Used by {@link #twentyReconnectCyclesLeakNoThreads} and by nothing else, in
     * this class or any other. The engine names its dispatch and reader threads
     * after the port, so a port used by exactly one test gives that test thread
     * names no other instance in the JVM can produce.
     */
    private static final String LEAK_PORT = "TEST-LEAKGUARD";

    private FakeTransport transport;
    private MiniBoard board;

    @AfterEach
    void tearDown() {
        if (board != null) {
            board.close();
        }
    }

    private MiniBoard newBoard() {
        return newBoardOn(PORT);
    }

    private MiniBoard newBoardOn(String targetPort) {
        transport = new FakeTransport();
        board = new MiniBoard(targetPort, transport);
        return board;
    }

    /** Connects and completes the handshake, expecting this to be open number {@code n}. */
    private DeviceInfo connectFully(int n) throws Exception {
        CompletableFuture<DeviceInfo> future = board.connect();
        assertTrue(transport.awaitOpenCount(n, 3, TimeUnit.SECONDS),
                "transport open #" + n + " never happened");
        transport.queueHello();
        return future.get(5, TimeUnit.SECONDS);
    }

    /** Installs a listener that counts down when the connection drops. */
    private CountDownLatch onDisconnectLatch(final AtomicReference<Throwable> causeOut) {
        final CountDownLatch latch = new CountDownLatch(1);
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                causeOut.set(cause);
                latch.countDown();
            }
        });
        return latch;
    }

    @Test
    @Timeout(30)
    void reconnectsAfterTheDeviceDropsThePort() throws Exception {
        newBoard();
        AtomicReference<Throwable> cause = new AtomicReference<Throwable>();
        CountDownLatch dropped = onDisconnectLatch(cause);

        assertNotNull(connectFully(1), "first connect must yield identity");

        transport.offerEof();
        assertTrue(dropped.await(5, TimeUnit.SECONDS), "onDisconnected never fired");
        assertNotNull(cause.get(), "an unexpected drop must report a cause");
        assertFalse(board.isConnected(), "the board must not report itself connected");
        assertFalse(board.isClosed(), "an unexpected drop must not close the instance");

        DeviceInfo second = connectFully(2);
        assertNotNull(second, "the instance must be reconnectable after a drop");
        assertTrue(board.isConnected(), "the reconnected board must report connected");
        assertEquals(2, transport.getOpenCount(), "the port must have been opened twice");
    }

    @Test
    @Timeout(30)
    void reconnectsAfterAnExplicitDisconnect() throws Exception {
        newBoard();
        connectFully(1);

        board.disconnect();
        assertFalse(board.isConnected(), "disconnect() must drop the link");
        assertFalse(board.isClosed(), "disconnect() must not close the instance");

        assertNotNull(connectFully(2), "the instance must be reconnectable after disconnect()");
        assertTrue(board.isConnected());
    }

    @Test
    @Timeout(30)
    void connectAfterCloseFailsAndNeverOpensThePort() throws Exception {
        newBoard();
        connectFully(1);
        board.close();
        assertTrue(board.isClosed(), "close() must be terminal");

        final CompletableFuture<DeviceInfo> refused = board.connect();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                refused.get(5, TimeUnit.SECONDS);
            }
        }, "connect() after close() must fail rather than hang");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("cannot reconnect"),
                "a closed instance must say so rather than reporting a transient"
                        + " refusal the caller might retry: " + failure.getCause().getMessage());
        assertEquals(1, transport.getOpenCount(),
                "a refused connect must not touch the transport");
    }

    @Test
    @Timeout(30)
    void connectWhileAlreadyConnectedFailsAndLeavesTheLinkIntact() throws Exception {
        newBoard();
        connectFully(1);

        final CompletableFuture<DeviceInfo> second = board.connect();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                second.get(5, TimeUnit.SECONDS);
            }
        }, "a second connect() while READY must fail");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());

        assertTrue(board.isConnected(), "the refused connect must not disturb the live link");
        assertEquals(1, transport.getOpenCount(), "the port must not be reopened");
    }

    /**
     * Only IDLE admits a connect. A second attempt landing while the first is still
     * inside {@code transport.open()} must be refused, not race it onto the port.
     *
     * <p>{@code connect()} opens the port on the calling thread, so the attempt that
     * is meant to park inside {@code open()} cannot be the test thread itself — it
     * runs on a helper thread, exactly as
     * {@code MiniBoardEngineReworkTest.closeDuringTransportOpenLeavesNoReaderAndTearsDownOnce}
     * does for the same reason.
     */
    @Test
    @Timeout(30)
    void connectWhileAlreadyConnectingFails() throws Exception {
        newBoard();
        CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch releaseOpen = new CountDownLatch(1);
        transport.blockOpen(openEntered, releaseOpen);

        final AtomicReference<CompletableFuture<DeviceInfo>> firstRef =
                new AtomicReference<CompletableFuture<DeviceInfo>>();
        Thread connector = new Thread(new Runnable() {
            @Override
            public void run() {
                firstRef.set(board.connect());
            }
        }, "test-reconnect-connector");
        connector.setDaemon(true);
        connector.start();

        assertTrue(openEntered.await(5, TimeUnit.SECONDS), "open() was never entered");

        final CompletableFuture<DeviceInfo> second = board.connect();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                second.get(5, TimeUnit.SECONDS);
            }
        }, "a connect() while CONNECTING must fail");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());

        releaseOpen.countDown();
        transport.queueHello();
        connector.join(10000);
        assertFalse(connector.isAlive(), "connect() never returned");
        assertNotNull(firstRef.get(), "connect() returned no future");
        assertNotNull(firstRef.get().get(10, TimeUnit.SECONDS),
                "the refused second attempt must not disturb the first");
        assertEquals(1, transport.getOpenCount(), "the port must be opened exactly once");
    }

    /**
     * The headline change: a connect() that cannot open the port returns the
     * instance to IDLE rather than closing it, because a board part-way through
     * re-enumerating is the ordinary case, not a fatal one.
     */
    @Test
    @Timeout(30)
    void aFailedConnectLeavesTheInstanceRetryable() throws Exception {
        newBoard();
        transport.failNextOpen(new IOException("simulated: not enumerated yet"));

        final CompletableFuture<DeviceInfo> failed = board.connect();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                failed.get(5, TimeUnit.SECONDS);
            }
        }, "the first connect must fail");
        assertTrue(failure.getCause() instanceof MiniBoardIoException,
                "expected MiniBoardIoException, got " + failure.getCause());

        assertFalse(board.isClosed(), "a failed connect must NOT close the instance");
        assertFalse(board.isConnected());
        assertEquals(0, transport.getOpenCount(), "the failed open must not count");

        assertNotNull(connectFully(1), "the retry must succeed on the same instance");
        assertTrue(board.isConnected());
    }

    /**
     * Regression for the review's Critical finding. {@code disconnect()} lands while
     * generation 1 is parked inside a blocking {@code transport.open()}; a second
     * connect follows. Generation 1's attempt must not, when its open finally
     * returns, steal generation 2's connect future, close generation 2's port, and
     * leave the engine stuck in CONNECTING — a second terminal state reachable
     * without {@code close()}.
     *
     * <p>The choreography is deliberately outcome-based rather than order-based: it
     * has to reproduce the defect against the unfixed engine (where {@code
     * disconnect()} publishes IDLE immediately, so the retry is admitted at once and
     * both generations end up inside {@code open()} together) while also passing
     * against the fixed one (where {@code disconnect()} holds CONNECTING until
     * generation 1's attempt has let go of the transport, so the retry is refused
     * until it is safe).
     */
    @Test
    @Timeout(60)
    void disconnectDuringABlockedOpenCannotLetTheSupersededAttemptKillTheNextConnection()
            throws Exception {
        newBoard();
        CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch releaseOpen = new CountDownLatch(1);
        transport.blockOpen(openEntered, releaseOpen);

        // Generation 1 parks inside transport.open().
        Thread connector = new Thread(new Runnable() {
            @Override
            public void run() {
                board.connect();
            }
        }, "test-blocked-connector");
        connector.setDaemon(true);
        connector.start();
        assertTrue(openEntered.await(5, TimeUnit.SECONDS), "open() was never entered");

        // disconnect() lands squarely in that window. On its own thread, because the
        // fixed engine makes it block until generation 1 lets go — and this test
        // thread is the one that has to release it.
        final CountDownLatch disconnectReturned = new CountDownLatch(1);
        Thread disconnector = new Thread(new Runnable() {
            @Override
            public void run() {
                board.disconnect();
                disconnectReturned.countDown();
            }
        }, "test-disconnector");
        disconnector.setDaemon(true);
        disconnector.start();

        // Generation 2 retries until the engine admits it. Unfixed, the very first
        // attempt is admitted and races generation 1 onto the port.
        final AtomicReference<DeviceInfo> second = new AtomicReference<DeviceInfo>();
        final AtomicReference<Throwable> lastRefusal = new AtomicReference<Throwable>();
        Thread reconnector = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + 25000;
                while (System.currentTimeMillis() < deadline) {
                    CompletableFuture<DeviceInfo> attempt = board.connect();
                    // Harmless on a refused attempt: unread bytes simply wait in the
                    // fake's inbox for whichever generation finally opens the port.
                    transport.queueHello();
                    try {
                        second.set(attempt.get(3, TimeUnit.SECONDS));
                        return;
                    } catch (Exception e) {
                        lastRefusal.set(e);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "test-reconnector");
        reconnector.setDaemon(true);
        reconnector.start();

        // Let both paths get where they are going before generation 1 is released.
        Thread.sleep(500);
        releaseOpen.countDown();

        assertTrue(disconnectReturned.await(20, TimeUnit.SECONDS), "disconnect() never returned");
        connector.join(20000);
        assertFalse(connector.isAlive(), "the first connect() never returned");
        reconnector.join(30000);
        assertFalse(reconnector.isAlive(), "the reconnect loop never finished");

        assertNotNull(second.get(), "the engine never accepted a reconnect; last refusal was "
                + lastRefusal.get());
        assertTrue(board.isConnected(), "the second connection must be live");
        assertFalse(board.isClosed(), "nothing here should have closed the instance");
    }

    @Test
    @Timeout(30)
    void closeStaysTerminalAfterAReconnect() throws Exception {
        newBoard();
        connectFully(1);
        board.disconnect();
        connectFully(2);

        board.close();
        assertTrue(board.isClosed());
        assertFalse(board.isConnected());

        final CompletableFuture<DeviceInfo> refused = board.connect();
        assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                refused.get(5, TimeUnit.SECONDS);
            }
        }, "close() must remain terminal however many reconnects preceded it");
    }

    @Test
    @Timeout(30)
    void listenerSurvivesAReconnectAndReceivesTheNewConnectionsEvents() throws Exception {
        newBoard();
        final CountDownLatch keyAfterReconnect = new CountDownLatch(1);
        final AtomicReference<Integer> keyIndex = new AtomicReference<Integer>();
        final AtomicReference<Throwable> cause = new AtomicReference<Throwable>();
        final CountDownLatch dropped = new CountDownLatch(1);

        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable c) {
                cause.set(c);
                dropped.countDown();
            }

            @Override
            public void onKey(int index, int keyId) {
                keyIndex.set(index);
                keyAfterReconnect.countDown();
            }
        });

        connectFully(1);
        transport.offerEof();
        assertTrue(dropped.await(5, TimeUnit.SECONDS), "onDisconnected never fired");

        connectFully(2);
        transport.offer(FakeTransport.event(0x10, new byte[] {7, 0}));   // KEY, index 7

        assertTrue(keyAfterReconnect.await(5, TimeUnit.SECONDS),
                "the listener set before the first connect must still receive events");
        assertEquals(Integer.valueOf(7), keyIndex.get(), "wrong key index delivered");
    }

    @Test
    @Timeout(30)
    void disconnectIsIdempotentAndHarmlessAfterClose() throws Exception {
        newBoard();
        connectFully(1);

        board.disconnect();
        board.disconnect();          // must be a no-op, not an error
        board.close();
        board.disconnect();          // must be a no-op, not an error

        assertTrue(board.isClosed());
        assertFalse(board.isConnected());
    }

    @Test
    @Timeout(30)
    void commandsIssuedOnTheSecondConnectionUseTheReopenedPort() throws Exception {
        newBoard();
        connectFully(1);
        board.disconnect();
        connectFully(2);

        int writesBefore = transport.getWritten().size();
        CompletableFuture<Integer> debounce = board.getDebounce();

        long deadline = System.currentTimeMillis() + 3000;
        while (transport.getWritten().size() <= writesBefore
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(transport.getWritten().size() > writesBefore,
                "the command never reached the reopened port");

        transport.offer(FakeTransport.response(0x82, 0x00, new byte[] {12}));
        assertEquals(Integer.valueOf(12), debounce.get(5, TimeUnit.SECONDS));
    }

    /**
     * The stale-reader refusal. A reader wedged inside {@code read()} that will not
     * exit must stop {@code connect()} from reopening the port, because a reopened
     * port would feed that reader the new connection's bytes — a corruption that
     * surfaces as an unexplained handshake timeout far from its cause.
     */
    @Test
    @Timeout(40)
    void connectRefusesToReopenUnderAReaderThatWillNotExit() throws Exception {
        newBoard();
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseReads = new CountDownLatch(1);

        connectFully(1);

        // Wedge the reader only now, so the handshake above completed normally.
        transport.blockReads(readEntered, releaseReads);
        assertTrue(readEntered.await(5, TimeUnit.SECONDS), "the reader never re-entered read()");

        board.disconnect();   // joins, times out, and records the lingering reader

        int opensBefore = transport.getOpenCount();
        final CompletableFuture<DeviceInfo> refused = board.connect();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                refused.get(10, TimeUnit.SECONDS);
            }
        }, "connect() must refuse while the previous reader is still alive");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());
        assertEquals(opensBefore, transport.getOpenCount(),
                "the port must NOT be reopened under a live stale reader");
        assertFalse(board.isClosed(), "a refused reconnect must leave the instance usable");

        releaseReads.countDown();

        // "Usable" has to mean more than "not closed". The refusal must have put the
        // instance back in IDLE; leaving it in CONNECTING would satisfy every
        // assertion above and still be a board that can never reconnect.
        assertNotNull(connectFully(2),
                "the instance must reconnect once the stale reader has finally exited");
    }

    /**
     * The {@code DecoderSink} generation check, in the one window that reaches it.
     *
     * <p>{@code connect()} bumps the generation counter on the way in, before it
     * reaches the reader handoff guard. So a <em>refused</em> reconnect leaves
     * generation 2 current while generation 1's reader is still parked inside
     * {@code read()} holding a generation 1 decoder and sink. Release that reader
     * with bytes waiting and a well-formed frame is genuinely decoded by a
     * superseded connection — which must be dropped, not dispatched as though the
     * live generation had received it.
     *
     * <p>No fixture hook is needed for this: the refusal creates the window, because
     * the counter moves before the port would be reopened.
     */
    @Test
    @Timeout(40)
    void framesReachingASupersededSinkAreDropped() throws Exception {
        newBoard();
        final AtomicInteger keys = new AtomicInteger();
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onKey(int index, int keyId) {
                keys.incrementAndGet();
            }
        });

        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseReads = new CountDownLatch(1);

        connectFully(1);
        transport.blockReads(readEntered, releaseReads);
        assertTrue(readEntered.await(5, TimeUnit.SECONDS), "the reader never re-entered read()");

        board.disconnect();   // joins, times out, and records the lingering reader

        // Refused, so the port is never reopened — but the generation counter was
        // already bumped on the way in, which is what supersedes the parked reader.
        final CompletableFuture<DeviceInfo> refused = board.connect();
        assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                refused.get(10, TimeUnit.SECONDS);
            }
        }, "connect() must refuse while the previous reader is still alive");

        // Hand the superseded reader a real KEY event and let it run. FakeTransport
        // does not gate read() on being open, which is exactly the hazard: a closed
        // port does not stop a wedged reader from returning bytes.
        transport.offer(FakeTransport.event(0x10, new byte[] {9, 0}));
        releaseReads.countDown();
        Thread.sleep(1000);

        assertEquals(0, keys.get(),
                "a frame decoded by a superseded connection must not be dispatched");
    }

    /**
     * Each connection decodes from a clean slate. A connection that ends mid-frame
     * leaves bytes buffered in its decoder; those bytes must die with it. No frame
     * crosses a generation boundary here — that is
     * {@link #framesReachingASupersededSinkAreDropped} — this pins the weaker but
     * distinct property that generation 2 never inherits generation 1's buffer.
     *
     * <p>Fails if the per-connection {@code FrameDecoder} is reverted to one shared
     * instance without a reset: the two leftover bytes would prefix generation 2's
     * first event, and {@code scan()} would read the event's own SOF as a 170-byte
     * length field and wait forever for data that never comes.
     */
    @Test
    @Timeout(30)
    void eachConnectionDecodesFromACleanSlate() throws Exception {
        newBoard();
        final AtomicReference<Throwable> cause = new AtomicReference<Throwable>();
        final CountDownLatch dropped = new CountDownLatch(1);
        final AtomicInteger keys = new AtomicInteger();

        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable c) {
                cause.set(c);
                dropped.countDown();
            }

            @Override
            public void onKey(int index, int keyId) {
                keys.incrementAndGet();
            }
        });

        connectFully(1);

        // Leave generation 1's decoder holding a half-frame it will never complete.
        // It has to be fed while generation 1 is still reading — offering it after
        // the reconnect would simply hand it to generation 2's own decoder and test
        // the staleness timeout instead of the generation boundary.
        transport.offer(new byte[] {(byte) 0xAA, 0x10});     // truncated KEY header
        Thread.sleep(200);

        transport.offerEof();
        assertTrue(dropped.await(5, TimeUnit.SECONDS), "onDisconnected never fired");

        int keysAfterFirstConnection = keys.get();
        connectFully(2);

        // That half-frame must not prefix generation 2's bytes: generation 2 decodes
        // from a clean slate, so this one well-formed event decodes as itself rather
        // than being mis-framed by two leftover bytes.
        transport.offer(FakeTransport.event(0x10, new byte[] {9, 0}));

        long deadline = System.currentTimeMillis() + 3000;
        while (keys.get() <= keysAfterFirstConnection
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(keysAfterFirstConnection + 1, keys.get(),
                "generation 2 must deliver exactly the one well-formed KEY event");
    }

    /**
     * A command left pending when connection 1 ends must be failed by that
     * connection's teardown, and must never be completed by connection 2's traffic —
     * a response opcode matching by coincidence would otherwise resolve it.
     *
     * <p>The discriminating assertion is the reported error, not the state of the old
     * future. Re-checking {@code pending.isCompletedExceptionally()} after it has
     * already thrown cannot fail: completion is once-only, so a wrongly matched
     * {@code complete(12)} would be a no-op and the check would still pass. What
     * does discriminate is that generation 2 has nothing in flight, so the stray
     * response must surface through {@code onError} as an unexpected response. Had
     * generation 2 matched the stale command it would have completed it silently and
     * reported nothing at all.
     */
    @Test
    @Timeout(30)
    void commandsPendingWhenAConnectionEndsAreNeverCompletedByTheNextOne() throws Exception {
        newBoard();
        final CountDownLatch dropped = new CountDownLatch(1);
        final LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<Throwable>();
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable c) {
                dropped.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        });

        connectFully(1);
        final CompletableFuture<Integer> pending = board.getDebounce();

        transport.offerEof();
        assertTrue(dropped.await(5, TimeUnit.SECONDS), "onDisconnected never fired");

        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                pending.get(5, TimeUnit.SECONDS);
            }
        }, "a command pending across a disconnect must fail, not hang");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());

        connectFully(2);
        errors.clear();   // nothing generation 1 may have reported is what this asserts on

        // The same opcode's response on the new connection must find nothing to
        // complete, and say so.
        transport.offer(FakeTransport.response(0x82, 0x00, new byte[] {12}));
        Throwable stray = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(stray, "the stray response was never reported as unexpected");
        assertTrue(stray.getMessage().contains("Unexpected response 0x82"),
                "wrong error reported: " + stray.getMessage());
        assertTrue(board.isConnected(), "the stray response must not tear the new link down");
    }

    /**
     * {@code reboot()} latches {@code expectDisconnect} so the port loss it causes is
     * reported as clean. Nothing cleared that latch before this change, which was
     * harmless only because the instance died with the connection. On a reusable
     * instance an uncleared latch would suppress the cause of every later
     * disconnect for the rest of its life, silently turning genuine I/O failures
     * into clean ones.
     */
    @Test
    @Timeout(30)
    void aRebootDoesNotSuppressTheCauseOfALaterDisconnect() throws Exception {
        newBoard();
        final AtomicReference<Throwable> firstCause = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> secondCause = new AtomicReference<Throwable>();
        final CountDownLatch firstDrop = new CountDownLatch(1);
        final CountDownLatch secondDrop = new CountDownLatch(1);
        final AtomicInteger drops = new AtomicInteger();

        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                if (drops.incrementAndGet() == 1) {
                    firstCause.set(cause);
                    firstDrop.countDown();
                } else {
                    secondCause.set(cause);
                    secondDrop.countDown();
                }
            }
        });

        connectFully(1);
        board.reboot(RebootMode.WATCHDOG);
        transport.offer(FakeTransport.response(0x93, 0x00, new byte[0]));
        transport.offerEof();

        assertTrue(firstDrop.await(5, TimeUnit.SECONDS), "the reboot drop never fired");
        assertEquals(null, firstCause.get(), "a reboot's port loss must be reported as clean");

        connectFully(2);
        transport.offerEof();

        assertTrue(secondDrop.await(5, TimeUnit.SECONDS), "the second drop never fired");
        assertNotNull(secondCause.get(),
                "a genuine drop after a reboot-and-reconnect must report its cause;"
                        + " expectDisconnect was not cleared by connect()");
    }

    /**
     * A command needs a connection in progress or established. Queuing one while idle
     * would leave it with no deadline armed and no guarantee a {@code connect()} is
     * coming — it would hang until {@code close()}. Most likely to be hit by a UI
     * issuing a command straight from its {@code onDisconnected} handler.
     *
     * <p>Both halves pin the IDLE message, not merely {@code MiniBoardException}:
     * {@code MiniBoardIoException}, {@code MiniBoardTimeoutException} and {@code
     * MiniBoardStatusException} all extend it, so {@code instanceof} alone cannot
     * tell "rejected while IDLE" from "rejected while CLOSED", "rejected for
     * queue-full", or "timed out after 1000 ms" — and it is precisely the
     * no-deadline-armed-while-idle invariant that makes the timeout case impossible
     * today.
     */
    @Test
    @Timeout(30)
    void commandsAreRejectedWhileIdle() throws Exception {
        newBoard();

        // Before any connection.
        final CompletableFuture<Integer> beforeConnect = board.getDebounce();
        ExecutionException early = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                beforeConnect.get(5, TimeUnit.SECONDS);
            }
        }, "a command submitted before connect() must be rejected, not queued");
        assertTrue(early.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + early.getCause());
        assertTrue(early.getCause().getMessage().contains("call connect() first"),
                "the rejection must be the IDLE one, not CLOSED, queue-full or a"
                        + " timeout: " + early.getCause().getMessage());

        // And after a connection has ended.
        connectFully(1);
        board.disconnect();

        final CompletableFuture<Integer> afterDisconnect = board.getDebounce();
        ExecutionException late = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                afterDisconnect.get(5, TimeUnit.SECONDS);
            }
        }, "a command submitted while disconnected must be rejected, not queued");
        assertTrue(late.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + late.getCause());
        assertTrue(late.getCause().getMessage().contains("call connect() first"),
                "the rejection must be the IDLE one, not CLOSED, queue-full or a"
                        + " timeout: " + late.getCause().getMessage());
    }

    /**
     * The mirror of {@link #commandsAreRejectedWhileIdle}: a closed instance must
     * not borrow the idle wording. Both states reject, so {@code instanceof} alone
     * cannot tell them apart — but the advice differs and only one of them is
     * actionable. "call connect() first" on a closed board is a lie, because
     * {@code connect()} after {@code close()} fails too; the caller must build a
     * new instance. This is the one rejection branch the message pins had left
     * unguarded.
     */
    @Test
    @Timeout(30)
    void commandsAreRejectedAfterClose() throws Exception {
        newBoard();
        connectFully(1);
        board.close();

        final CompletableFuture<Integer> afterClose = board.getDebounce();
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                afterClose.get(5, TimeUnit.SECONDS);
            }
        }, "a command submitted after close() must be rejected, not queued");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("is closed"),
                "the rejection must be the CLOSED one: " + failure.getCause().getMessage());
        assertFalse(failure.getCause().getMessage().contains("call connect() first"),
                "a closed instance must not advise connect(), which also fails: "
                        + failure.getCause().getMessage());
    }

    /** Connects with a caller-chosen serial, expecting this to be open number {@code n}. */
    private CompletableFuture<DeviceInfo> connectWithSerial(int n, byte[] serial)
            throws Exception {
        CompletableFuture<DeviceInfo> future = board.connect();
        assertTrue(transport.awaitOpenCount(n, 3, TimeUnit.SECONDS),
                "transport open #" + n + " never happened");
        transport.queueHello(serial);
        return future;
    }

    /**
     * COM numbers shuffle, and a reconnect can land on a different physical board.
     * An instance bound to one board must refuse the other rather than let a later
     * setKey() or save() reach the wrong device.
     *
     * <p>The message pins are the discriminating assertions. {@code instanceof
     * MiniBoardException} matches every rejection the handshake can produce — the
     * name mismatch, the handshake timeout, the teardown drain — so it alone would
     * not tell a serial refusal from any of them. Naming each serial in the half of
     * the sentence that belongs to it also catches the two being interpolated the
     * wrong way round, which would send an operator hunting for the wrong board.
     */
    @Test
    @Timeout(30)
    void reconnectingToADifferentBoardIsRefused() throws Exception {
        newBoard();
        byte[] ours = new byte[] {0x01, 0x02, 0x03, 0x04};
        byte[] theirs = new byte[] {(byte) 0xFE, (byte) 0xED};

        assertNotNull(connectWithSerial(1, ours).get(5, TimeUnit.SECONDS));
        board.disconnect();

        final CompletableFuture<DeviceInfo> wrongBoard = connectWithSerial(2, theirs);
        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                wrongBoard.get(5, TimeUnit.SECONDS);
            }
        }, "a different board must not be accepted by a bound instance");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());
        String message = failure.getCause().getMessage();
        assertTrue(message.contains("has serial FEED"),
                "the message must name the serial actually found: " + message);
        assertTrue(message.contains("bound to 01020304"),
                "the message must name the serial this instance is bound to: " + message);
        // docs/usage.md quotes this sentence too, and nothing else pins it.
        assertTrue(message.contains("Refusing to reconnect to a different board"),
                "the message must say what it refused to do, not only what it saw: " + message);

        // Not assertFalse(isConnected()): READY is never assigned on a refusal path,
        // so no defect variant could fail it. What does have force is that the
        // refused board must not have overwritten the retained identity - a UI still
        // rendering "disconnected" from getDeviceInfo() must not be shown the serial
        // of a board this instance never accepted.
        DeviceInfo retained = board.getDeviceInfo();
        assertNotNull(retained, "the first handshake's identity must be retained");
        assertEquals("01020304", retained.getSerialHex(),
                "a refused board must not overwrite the retained identity");

        // Retryability is deliberately NOT asserted here. The refusal's teardown is
        // asynchronous, so an isClosed() check on this thread races it and reads
        // CONNECTING or IDLE whatever the teardown goes on to publish — it passes
        // even when the refusal is made terminal. theCorrectBoardStillConnects
        // AfterARefusedOne pins the property properly, by reconnecting.
    }

    /** The refusal is retryable: the right board on the next attempt still connects. */
    @Test
    @Timeout(30)
    void theCorrectBoardStillConnectsAfterARefusedOne() throws Exception {
        newBoard();
        byte[] ours = new byte[] {0x01, 0x02, 0x03, 0x04};

        // Two drops are expected before the retry: the explicit disconnect() and the
        // refusal's own teardown. Counting them on one latch installed up front avoids
        // racing a listener installed later against a callback already queued.
        //
        // Waiting on isConnected() would not wait at all: it is state == READY, which
        // a refused handshake never reaches, so the loop would exit immediately and
        // the retry would race the in-flight teardown. onDisconnected is posted only
        // after the port is closed, the reader joined and IDLE published.
        final CountDownLatch drops = new CountDownLatch(2);
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                drops.countDown();
            }
        });

        connectWithSerial(1, ours).get(5, TimeUnit.SECONDS);
        board.disconnect();

        final CompletableFuture<DeviceInfo> wrong =
                connectWithSerial(2, new byte[] {(byte) 0xFE, (byte) 0xED});
        assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                wrong.get(5, TimeUnit.SECONDS);
            }
        });

        assertTrue(drops.await(10, TimeUnit.SECONDS),
                "the refusal never tore the connection down: onDisconnected fired "
                        + (2 - drops.getCount()) + " of 2 expected times");

        DeviceInfo again = connectWithSerial(3, ours).get(5, TimeUnit.SECONDS);
        assertNotNull(again, "the bound board must still be accepted after a refusal");
        assertEquals("01020304", again.getSerialHex(),
                "the retry must have handshaked with the bound board");
        assertTrue(board.isConnected());
    }

    /** The same board on a new COM number is the whole point: it must be accepted. */
    @Test
    @Timeout(30)
    void theSameBoardOnANewPortIsAccepted() throws Exception {
        newBoard();
        byte[] ours = new byte[] {0x01, 0x02, 0x03, 0x04};

        connectWithSerial(1, ours).get(5, TimeUnit.SECONDS);
        assertEquals(PORT, board.getPortName());
        board.disconnect();

        CompletableFuture<DeviceInfo> retarget = board.connect("TEST-MOVED");
        assertTrue(transport.awaitOpenCount(2, 3, TimeUnit.SECONDS), "reopen never happened");
        transport.queueHello(ours);

        assertNotNull(retarget.get(5, TimeUnit.SECONDS),
                "the same board on a different port must be accepted");
        assertEquals("TEST-MOVED", board.getPortName(),
                "getPortName() must report the new target");
        assertEquals("TEST-MOVED", transport.getPortName(),
                "the transport must have been opened on the new port");
    }

    /**
     * A device that is not a MiniBoard54 is refused by name, before any serial
     * comparison. This branch had no test anywhere in the suite until now: Task 5
     * refactored it onto the same {@code rejection} local the serial refusal uses,
     * with nothing guarding the change.
     *
     * <p>The negative assertion is the point of the second half. Both rejections now
     * build one local and are completed through one call site, so a future edit
     * could collapse them into a single message and every other test in this class
     * would stay green — {@code reconnectingToADifferentBoardIsRefused} pins the
     * serial wording, but nothing pinned that a <em>name</em> mismatch does not
     * report itself as one. An operator told "this MiniBoard is bound to 01020304"
     * would go hunting for the wrong board when the truth is that the thing on the
     * port is not a MiniBoard at all.
     */
    @Test
    @Timeout(30)
    void aDeviceReportingTheWrongNameIsRefused() throws Exception {
        newBoard();
        final CompletableFuture<DeviceInfo> future = board.connect();
        assertTrue(transport.awaitOpenCount(1, 3, TimeUnit.SECONDS),
                "transport open #1 never happened");
        transport.queueHello("NotAMiniBoard", new byte[] {0x01, 0x02, 0x03, 0x04});

        ExecutionException failure = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(5, TimeUnit.SECONDS);
            }
        }, "a device reporting another name must be refused, not accepted");
        assertTrue(failure.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + failure.getCause());

        String message = failure.getCause().getMessage();
        assertTrue(message.contains("reported name \"NotAMiniBoard\""),
                "the message must quote the name the device actually reported: " + message);
        assertTrue(message.contains("expected \"MiniBoard54\""),
                "the message must name what was expected instead: " + message);
        assertFalse(message.contains("bound to"),
                "a name mismatch must not be reported as the serial-mismatch refusal,"
                        + " which sends the reader looking for the wrong board: " + message);
    }

    @Test
    @Timeout(30)
    void connectRejectsABlankPortName() {
        newBoard();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                board.connect("   ");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                board.connect((String) null);
            }
        });
    }

    /** Names of every live thread whose name starts with {@code prefix}, duplicates kept. */
    private static List<String> liveThreadNames(String prefix) {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 64];
        int found = Thread.enumerate(all);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < found; i++) {
            Thread t = all[i];
            if (t != null && t.isAlive() && t.getName().startsWith(prefix)) {
                names.add(t.getName());
            }
        }
        return names;
    }

    /**
     * Live threads matching {@code prefix} whose names were not already in
     * {@code before} — that is, threads this test is responsible for.
     *
     * <p>Threads are counted, not names: two threads sharing one name count twice,
     * which matters because a leaked executor's replacement carries the same name as
     * the one it replaced.
     */
    private static int countNewThreads(String prefix, Set<String> before) {
        int count = 0;
        for (String name : liveThreadNames(prefix)) {
            if (!before.contains(name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The point of one engine per instance: threads are owned by the instance, not
     * by the connection. Twenty cycles must not accumulate twenty readers, twenty
     * dispatchers or twenty callback threads — and after {@code close()} the
     * instance's own threads must go too.
     *
     * <p>Deliberately <em>not</em> written as "count now, subtract the count taken at
     * the start". This runs late in a shared JVM, and threads belonging to test
     * classes that have already finished may still be winding down while it runs.
     * With numeric baselines one of those dying after the baseline was taken pushes
     * the later count <em>below</em> it and fails an exact-equality assertion for a
     * reason that has nothing to do with this test — while a thread this test is
     * actually responsible for could equally be masked by one dying elsewhere. The
     * baseline is therefore a set of thread <em>names</em>, and every assertion is
     * about threads that were not running before this test started. Nothing here is
     * a bound or a tolerance: the numbers are still exact.
     *
     * <p>That works because the names really are unique to this instance.
     * {@link #LEAK_PORT} is used by no other test, and the engine names its dispatch
     * thread {@code miniboard-dispatch-<port>} and each reader
     * {@code miniboard-reader-<port>-<generation>}. Callback threads are named for a
     * process-wide instance counter instead of the port, which is why the name-set
     * filter is needed rather than the port prefix alone.
     */
    @Test
    @Timeout(120)
    void twentyReconnectCyclesLeakNoThreads() throws Exception {
        final int cycles = 20;
        final String callbackPrefix = "miniboard-callback-";
        final String dispatchPrefix = "miniboard-dispatch-" + LEAK_PORT;
        final String readerPrefix = "miniboard-reader-" + LEAK_PORT + "-";

        Set<String> callbackBefore = new HashSet<String>(liveThreadNames(callbackPrefix));
        Set<String> dispatchBefore = new HashSet<String>(liveThreadNames(dispatchPrefix));
        Set<String> readerBefore = new HashSet<String>(liveThreadNames(readerPrefix));

        newBoardOn(LEAK_PORT);
        for (int i = 1; i <= cycles; i++) {
            // No assertNotNull: connectFully() either throws or returns a built
            // DeviceInfo, never null, so wrapping it would assert nothing. A failed
            // cycle surfaces as its own exception, naming the cycle via open #i.
            connectFully(i);
            board.disconnect();
        }

        // One instance, so exactly one callback and one dispatch thread throughout.
        assertEquals(1, countNewThreads(callbackPrefix, callbackBefore),
                "one instance must own exactly one callback thread across all cycles");
        assertEquals(1, countNewThreads(dispatchPrefix, dispatchBefore),
                "one instance must own exactly one dispatch thread across all cycles");

        board.close();
        board = null;   // already closed; keep @AfterEach from double-closing

        // The executors' threads exit only once they have drained their queues, so
        // poll rather than asserting instantly.
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline
                && (countNewThreads(callbackPrefix, callbackBefore) > 0
                    || countNewThreads(dispatchPrefix, dispatchBefore) > 0
                    || countNewThreads(readerPrefix, readerBefore) > 0)) {
            Thread.sleep(50);
        }

        assertEquals(0, countNewThreads(callbackPrefix, callbackBefore),
                "close() must release the callback thread");
        assertEquals(0, countNewThreads(dispatchPrefix, dispatchBefore),
                "close() must release the dispatch thread");
        assertEquals(0, countNewThreads(readerPrefix, readerBefore),
                cycles + " cycles left reader threads behind: "
                        + liveThreadNames(readerPrefix));
    }
}
