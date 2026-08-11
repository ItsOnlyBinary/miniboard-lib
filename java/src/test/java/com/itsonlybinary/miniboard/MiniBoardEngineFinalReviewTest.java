package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Opcode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the three engine defects raised by the final
 * whole-branch review (I1, I2, I3). Drives {@link MiniBoardEngine} through a
 * {@link FakeTransport}, so no hardware is involved.
 *
 * <p>Every test here was recorded failing against the pre-fix engine; see each
 * test's javadoc for the specific failure captured against it.
 */
class MiniBoardEngineFinalReviewTest {

    private static final String CALLBACK_THREAD_PREFIX = "miniboard-callback-";

    private static DeviceInfo connectAndAwaitReady(MiniBoardEngine engine,
                                                   FakeTransport transport) throws Exception {
        CompletableFuture<DeviceInfo> future = engine.connect(null);
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS),
                "transport.open() was never called");
        transport.queueHello();
        DeviceInfo info = future.get(3, TimeUnit.SECONDS);
        assertNotNull(info);
        assertTrue(engine.isConnected());
        return info;
    }

    private static int countCallbackThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(CALLBACK_THREAD_PREFIX)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------- I1

    /**
     * A command submitted before {@code connect()} is now rejected outright rather
     * than queued unarmed. The original hazard was that a queued command had no
     * {@code TimeoutTask} and no guaranteed drain, so a {@code connect()} that failed
     * to open the port left its future unresolvable for the life of the JVM — and the
     * README explicitly invites walking a discovery list onto ports that will not
     * open.
     *
     * <p>Rejecting at submit removes the hazard at its root. This pins that, and that
     * the failed connect still reports the open error rather than being masked.
     *
     * <p>The engine here is IDLE, not CLOSED — it is freshly constructed and never
     * connected — so the message assertion pins the IDLE wording. {@code
     * MiniBoardIoException}, {@code MiniBoardTimeoutException} and {@code
     * MiniBoardStatusException} all extend {@code MiniBoardException}, so
     * {@code instanceof} alone could not tell "rejected while IDLE" from a timeout
     * or from the CLOSED refusal.
     */
    @Test
    @Timeout(15)
    void aCommandSubmittedBeforeConnectIsRejectedOutright() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.failNextOpen(new IOException("simulated: port will not open"));
        MiniBoardEngine engine = new MiniBoardEngine("TEST-I1-STRAND", transport);
        try {
            final CompletableFuture<byte[]> early =
                    engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);

            // Rejected immediately, without waiting for any connect() to happen.
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            early.get(5, TimeUnit.SECONDS);
                        }
                    });
            assertTrue(rejected.getCause() instanceof MiniBoardException,
                    "expected MiniBoardException, got " + rejected.getCause());
            assertTrue(rejected.getCause().getMessage().contains("call connect() first"),
                    "the rejection must be the IDLE one, not CLOSED, queue-full or a"
                            + " timeout: " + rejected.getCause().getMessage());

            final CompletableFuture<DeviceInfo> connect = engine.connect(null);
            ExecutionException connectFailure = assertThrows(ExecutionException.class,
                    new Executable() {
                        @Override
                        public void execute() throws Throwable {
                            connect.get(5, TimeUnit.SECONDS);
                        }
                    });
            assertTrue(connectFailure.getCause() instanceof MiniBoardIoException,
                    "expected MiniBoardIoException, got " + connectFailure.getCause());

            // The port never opened, so there was no connection to report the loss of.
            assertEquals(0, transport.getCloseCount(),
                    "a transport that never opened must not be closed by the failure path");
        } finally {
            engine.close();
        }
    }

    /**
     * A failed connect no longer disposes of the instance — it returns to IDLE so
     * the caller can retry once the board finishes re-enumerating — so the executors
     * necessarily outlive it. Disposal therefore belongs to the caller, and this
     * pins the invariant that replaced the old one: twenty failed connects, each
     * followed by {@code close()}, must leave no thread behind.
     *
     * <p>Also pins, by completing at all, that a failed connect leaves an instance
     * that is still healthy enough to be closed.
     */
    @Test
    @Timeout(30)
    void twentyFailedConnectsFollowedByCloseLeakNoCallbackThreads() throws Exception {
        final int attempts = 20;
        int baseline = countCallbackThreads();

        for (int i = 0; i < attempts; i++) {
            FakeTransport transport = new FakeTransport();
            transport.failNextOpen(new IOException("simulated: port will not open"));
            MiniBoardEngine engine = new MiniBoardEngine("TEST-I1-LEAK-" + i, transport);
            final CompletableFuture<DeviceInfo> connect = engine.connect(null);
            assertThrows(ExecutionException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    connect.get(5, TimeUnit.SECONDS);
                }
            });
            engine.close();
        }

        // Shutdown is asynchronous in the sense that the executor's thread exits
        // only once it has drained its queue; poll rather than assert instantly.
        long deadline = System.currentTimeMillis() + 10000;
        int live = countCallbackThreads();
        while (live > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            live = countCallbackThreads();
        }

        assertTrue(live <= baseline, attempts + " closed instances leaked "
                + (live - baseline) + " callback thread(s): close() must release"
                + " every thread the instance owns");
    }

    // ------------------------------------------------------------------- I2

    /**
     * {@code TimeoutTask} runs on the single {@code miniboard-timeout} thread that
     * every board in the JVM shares. If it finishes by dispatching the next
     * command inline, one board whose write wedges — a stalled device, a full USB
     * buffer — takes the scheduler with it, and no other board can time anything
     * out again.
     *
     * <p>Board A's writes are parked. A1 goes on the wire and sticks there; A2
     * queues behind it. A1's deadline lands first and, pre-fix, dispatches A2 from
     * the scheduler thread straight into a second parked write. Board B is a
     * healthy, entirely separate board whose device simply does not answer: its
     * command must still expire on its own clock.
     *
     * <p>Must fail against the pre-fix engine: board B's timeout task never gets
     * to run, so {@code onB.get(6, SECONDS)} throws {@code TimeoutException}
     * rather than the expected {@code ExecutionException}.
     */
    @Test
    @Timeout(45)
    void aWedgedWriteOnOneBoardStillLetsAnotherBoardTimeOut() throws Exception {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);

        FakeTransport wedged = new FakeTransport();
        final MiniBoardEngine boardA = new MiniBoardEngine("TEST-I2-A", wedged);
        FakeTransport healthy = new FakeTransport();
        MiniBoardEngine boardB = new MiniBoardEngine("TEST-I2-B", healthy);

        Thread submitter = null;
        try {
            connectAndAwaitReady(boardA, wedged);
            connectAndAwaitReady(boardB, healthy);
            wedged.blockWrites(writeEntered, releaseWrite);

            // Pre-fix, submit() dispatches inline, so this call itself parks inside
            // write(). It has to happen off the test thread for either engine.
            submitter = new Thread(new Runnable() {
                @Override
                public void run() {
                    boardA.submit(Opcode.GET_DEBOUNCE, new byte[0]);
                }
            }, "test-i2-submitter");
            submitter.setDaemon(true);
            submitter.start();

            assertTrue(writeEntered.await(5, TimeUnit.SECONDS),
                    "board A's first command never reached write()");

            // Queued behind the wedged write. This is what the pre-fix TimeoutTask
            // will try to dispatch inline when A's first command expires.
            boardA.submit(Opcode.GET_DEBOUNCE, new byte[0]);

            // Submitted late enough that its 1000 ms deadline lands strictly after
            // board A's, so the scheduler is already wedged when it comes due.
            Thread.sleep(600);
            final CompletableFuture<byte[]> onB = boardB.submit(Opcode.GET_DEBOUNCE, new byte[0]);

            ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    onB.get(6, TimeUnit.SECONDS);
                }
            }, "board B's command never completed: a blocking write on another"
                    + " board has stalled the shared timeout scheduler");
            assertTrue(ex.getCause() instanceof MiniBoardTimeoutException,
                    "expected MiniBoardTimeoutException, got " + ex.getCause());
        } finally {
            releaseWrite.countDown();
            if (submitter != null) {
                submitter.join(5000);
            }
            boardA.close();
            boardB.close();
        }
    }

    // ------------------------------------------------------------------- I3

    /**
     * {@code dispatchNext()} claims {@code inFlight} under the lock but writes
     * outside it, which it must — holding a lock across blocking I/O is forbidden.
     * That leaves a window in which the in-flight command's own {@code TimeoutTask}
     * fires, takes the lock, clears {@code inFlight} and dispatches the next
     * command while the first thread is still inside {@code transport.write}.
     * {@code SerialTransport.write} is an unsynchronised {@code write(); flush()}
     * on one stream, so the two frames interleave on the wire.
     *
     * <p>Deadline-at-submit makes the window ordinary rather than exotic: against
     * a slow device with a deep queue, a command routinely dispatches within
     * microseconds of its own deadline. Here it is forced open by parking the
     * write for longer than the command's 1000 ms timeout.
     *
     * <p>Must fail against the pre-fix engine: the timeout fires mid-write, the
     * scheduler thread enters {@code write()} for the second command, and the
     * high-water mark reaches 2.
     */
    @Test
    @Timeout(45)
    void aDeadlineFiringMidWriteCannotPutASecondWriterOnTheWire() throws Exception {
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);

        FakeTransport transport = new FakeTransport();
        final MiniBoardEngine engine = new MiniBoardEngine("TEST-I3", transport);

        Thread submitter = null;
        try {
            connectAndAwaitReady(engine, transport);
            transport.blockWrites(writeEntered, releaseWrite);

            submitter = new Thread(new Runnable() {
                @Override
                public void run() {
                    engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);
                }
            }, "test-i3-submitter");
            submitter.setDaemon(true);
            submitter.start();

            assertTrue(writeEntered.await(5, TimeUnit.SECONDS),
                    "the first command never reached write()");

            // Queued while the first command is stuck inside write().
            engine.submit(Opcode.GET_CONFIG_TYPE, new byte[0]);

            // GET_DEBOUNCE's deadline is 1000 ms from submit(); wait it out with
            // room for the (incorrect) inline dispatch that follows it to land.
            Thread.sleep(2000);

            assertEquals(1, transport.getMaxConcurrentWriters(),
                    "two threads were inside transport.write() at once: a command's"
                            + " timeout dispatched the next one while the first was"
                            + " still writing, interleaving two frames on the wire");
        } finally {
            releaseWrite.countDown();
            if (submitter != null) {
                submitter.join(5000);
            }
            engine.close();
        }
    }

    // -------------------------------------------------------------- re-arm

    /**
     * Pins the defect parked during Task 2's review, in the same method Task 4
     * touches for a different reason. A command submitted after a teardown's
     * queue drain but before it publishes IDLE still sees {@code state == READY},
     * so {@code submit()} arms its deadline once; the command then survives into
     * the next connection's queue and is armed a second time by {@code
     * checkHandshakeComplete()}'s backlog loop. Before this fix, {@code
     * armDeadline} overwrote {@code command.timeout} without cancelling the
     * previous {@code ScheduledFuture}, orphaning the first timer — which still
     * fired on its original, shorter budget and completed the command with a
     * spurious {@code MiniBoardTimeoutException} up to 1000 ms before the
     * re-armed deadline ever would have.
     *
     * <p>The real window is a few microseconds wide and not worth chasing with a
     * teardown race, so this drives {@code armDeadline} directly — it and {@code
     * PendingCommand} are both private, hence the reflection, in keeping with
     * this codebase's existing precedent ({@code
     * MiniBoardDiscoveryTest.rankingComparatorOrdersByTierThenByNumericPortIndex}).
     * A command is submitted normally while READY (arming it once), captured as
     * the engine's {@code inFlight} command, then armed a second time to
     * reproduce the re-arm — with a deliberate pause first, so the two
     * deadlines land far enough apart that "which timer fired" is unambiguous.
     *
     * <p>Must fail against the pre-fix {@code armDeadline}, and does so first at
     * {@code assertTrue(firstTimer.isCancelled())} — the pre-fix code overwrote
     * {@code command.timeout} without cancelling, so the first timer is still live
     * at that point and the re-arm never reaches the pause. That is the captured
     * failure. The later {@code assertFalse(future.isDone())} is an independent
     * second line of defence on the same defect, catching it behaviourally rather
     * than by flag: with the {@code isCancelled} assertion disabled as well, the
     * orphaned first timer completes {@code future} partway through the pause
     * between the two deadlines and that assertion fails instead. Both directions
     * were recorded failing.
     */
    @Test
    @Timeout(30)
    void reArmingADeadlineCancelsTheOrphanedTimerInsteadOfOrphaningIt() throws Exception {
        FakeTransport transport = new FakeTransport();
        MiniBoardEngine engine = new MiniBoardEngine("TEST-REARM", transport);
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);

        try {
            connectAndAwaitReady(engine, transport);
            transport.blockWrites(writeEntered, releaseWrite);

            // Armed once here, at t=0: GET_DEBOUNCE carries a 1000 ms timeout, so
            // this first timer is scheduled to fire at t=1000 if never cancelled.
            final CompletableFuture<byte[]> future =
                    engine.submit(Opcode.GET_DEBOUNCE, new byte[0]);
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS),
                    "the command never reached write()");

            Field inFlightField = MiniBoardEngine.class.getDeclaredField("inFlight");
            inFlightField.setAccessible(true);
            Object command = inFlightField.get(engine);
            assertNotNull(command, "no command was in flight to re-arm");

            Class<?> pendingClass = command.getClass();
            Field timeoutField = pendingClass.getDeclaredField("timeout");
            timeoutField.setAccessible(true);
            ScheduledFuture<?> firstTimer = (ScheduledFuture<?>) timeoutField.get(command);
            assertNotNull(firstTimer, "submit() while READY must arm a deadline");

            // Wait long enough that the two deadlines cannot be confused for one
            // another, but short enough to stay well clear of t=1000.
            Thread.sleep(700);

            // Reproduces checkHandshakeComplete()'s backlog re-arm: the second timer
            // is scheduled for t=(now + 1000) =~ t=1700.
            Method armDeadline = MiniBoardEngine.class.getDeclaredMethod(
                    "armDeadline", pendingClass);
            armDeadline.setAccessible(true);
            armDeadline.invoke(engine, command);

            ScheduledFuture<?> secondTimer = (ScheduledFuture<?>) timeoutField.get(command);
            assertNotNull(secondTimer, "the re-arm must schedule a replacement timer");
            assertNotSame(firstTimer, secondTimer,
                    "the re-arm must replace the timer, not merely re-read the old one");
            assertTrue(firstTimer.isCancelled(),
                    "the first timer must be cancelled by the re-arm, or it is orphaned");

            // t=1300: past the original t=1000 deadline, comfortably short of the
            // re-armed t=1700 one. An orphaned first timer would have already
            // completed the future by now.
            Thread.sleep(600);
            assertFalse(future.isDone(),
                    "an orphaned first timer fired a spurious timeout before the"
                            + " re-armed deadline");

            // The re-armed deadline is the one that actually governs the command:
            // it still expires, just on its own, later budget, and only once.
            ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    future.get(3, TimeUnit.SECONDS);
                }
            });
            assertTrue(ex.getCause() instanceof MiniBoardTimeoutException,
                    "expected MiniBoardTimeoutException, got " + ex.getCause());
        } finally {
            releaseWrite.countDown();
            engine.close();
        }
    }
}
