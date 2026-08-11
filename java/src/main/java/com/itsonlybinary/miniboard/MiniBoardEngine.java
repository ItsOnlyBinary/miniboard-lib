package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.EventType;
import com.itsonlybinary.miniboard.protocol.Frame;
import com.itsonlybinary.miniboard.protocol.FrameDecoder;
import com.itsonlybinary.miniboard.protocol.FrameEncoder;
import com.itsonlybinary.miniboard.protocol.FrameError;
import com.itsonlybinary.miniboard.protocol.FrameType;
import com.itsonlybinary.miniboard.protocol.Opcode;
import com.itsonlybinary.miniboard.protocol.Status;
import com.itsonlybinary.miniboard.transport.Transport;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The connection engine behind {@link MiniBoard}: transport lifecycle, the
 * hello handshake, the reader thread, the callback executor, and the command
 * queue. Package-private and final: an implementation detail, never part of
 * the public API.
 */
final class MiniBoardEngine {

    private static final int READ_BUFFER = 512;

    /**
     * IDLE is re-enterable: every failed or ended connection returns here, and
     * {@code connect()} may be called again from it. CLOSED is reachable only
     * from {@code close()} and is the sole terminal state.
     */
    private enum State { IDLE, CONNECTING, READY, CLOSED }

    /** How long a teardown waits for its reader thread to exit before giving up on it. */
    static final int READER_JOIN_TIMEOUT_MS = 500;

    /**
     * A second, longer chance for a previous connection's reader to exit before
     * {@code connect()} gives up rather than reopen the port under it.
     */
    static final int READER_HANDOFF_TIMEOUT_MS = 1000;

    /**
     * How long a teardown waits for the generation it is ending to let go of the
     * transport — for that generation's {@code connect()} attempt to return, or for
     * a rival teardown of the same generation to finish closing the port.
     *
     * <p>Deliberately generous. It has to outlast a real blocking open
     * ({@code SerialTransport.OPEN_TIMEOUT_MS} is 2000 ms) plus a port close and a
     * reader join, with room to spare, because expiry is treated as a bug rather
     * than a timing accident: the teardown gives up without publishing IDLE, so no
     * successor generation can be handed a transport the previous one may still be
     * inside, and the failure is reported through {@code onError}.
     */
    static final int SETTLE_TIMEOUT_MS = 12000;

    /** Passed as the generation by terminal teardowns, which apply to any generation. */
    private static final int ANY_GENERATION = -1;

    /** A latch that is already through: the "nothing to wait for" case. */
    private static final CountDownLatch SETTLED = new CountDownLatch(0);

    /**
     * One timeout scheduler for the whole library. Strictly non-blocking: every
     * task it runs must return promptly, because it is shared by every
     * {@link MiniBoard} in the JVM. Teardown therefore never runs here, and
     * neither does {@link #dispatchNext()} — a task that reached
     * {@code transport.write} could wedge on a stalled device and stop every
     * other board in the JVM from timing its commands out. Dispatch is handed to
     * the per-board {@link #dispatchExecutor} instead.
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "miniboard-timeout");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger();

    /** The current target. Mutable: {@code connect(String)} may retarget the instance. */
    private volatile String portName;

    /**
     * Bumped on every IDLE to CONNECTING transition. Written only under {@link #lock}.
     * Everything belonging to one connection captures the generation it was born
     * into, so a late callback from a superseded connection is a no-op rather than
     * an action on the live one.
     */
    private volatile int generation;

    private final Transport transport;
    private final ExecutorService callbackExecutor;

    /**
     * The only thread that may ever be inside {@code transport.write} for this
     * board. {@link #dispatchNext()} claims {@code inFlight} under the lock but
     * writes outside it — two threads racing that window would interleave two
     * frames on one unsynchronised stream. Serialising every dispatch here makes
     * that structurally impossible, and simultaneously keeps the blocking write
     * off the shared {@link #SCHEDULER} and off the reader thread.
     */
    private final ExecutorService dispatchExecutor;

    private final Object lock = new Object();
    private final Deque<PendingCommand> queue = new ArrayDeque<PendingCommand>();
    private PendingCommand inFlight;
    private State state = State.IDLE;

    /**
     * True once {@code transport.open()} has returned successfully <em>in the current
     * generation</em>. Reset by {@code connect()}. Guarded by {@link #lock}.
     */
    private boolean portOpened;

    /**
     * True once the current connection has been torn down. Reset by {@code connect()},
     * so the guarantee is "exactly once per generation" rather than once per instance.
     * Guarded by {@link #lock}.
     */
    private boolean teardownDone;

    /**
     * Counted down when the current generation's {@code connect()} attempt has
     * stopped touching the transport — it has returned, aborted, or was never
     * started. Replaced by {@code connect()} under {@link #lock}.
     *
     * <p>A non-terminal teardown waits on this before it publishes IDLE, which is
     * what stops a successor generation from entering {@code transport.open()}
     * while this one is still inside it. There is one {@link Transport} per
     * instance, so two generations on it at once would race, and whichever lost
     * would go on to close the other's port.
     *
     * <p><strong>Invariant for anyone editing {@code connect()}:</strong> release
     * this latch <em>before</em> any call that tears down synchronously on the
     * connecting thread — {@code failConnect}, or {@code teardown} directly. Both
     * such paths (the reader handoff refusal and the failed open) do so explicitly,
     * and both must: {@code failConnect} calls a non-terminal {@code teardown},
     * which waits on this very latch, and {@code connect()}'s {@code finally} has
     * not run yet. Relying on that {@code finally} alone self-deadlocks until
     * {@link #SETTLE_TIMEOUT_MS} expires, after which the teardown declines to
     * publish IDLE and the instance is stranded in CONNECTING. The trap is
     * invisible at the call site, and the extra {@code countDown()} is harmless
     * because counting past zero is a no-op.
     */
    private volatile CountDownLatch connectSettled = SETTLED;

    /**
     * Counted down by whichever teardown claimed the current generation, once it
     * has closed the port, joined the reader and published the resulting state.
     * A teardown that loses that claim waits on this rather than returning early,
     * so {@code disconnect()} and {@code close()} keep the promise their javadoc
     * makes: when they return, the port really is closed.
     */
    private volatile CountDownLatch teardownSettled = SETTLED;

    private volatile Thread readerThread;

    /**
     * A previous connection's reader that outlived its join. Never null while one is
     * outstanding; {@code connect()} refuses to reopen the port until it has exited.
     */
    private volatile Thread lingeringReader;

    private volatile boolean running;
    private volatile MiniBoardListener listener;
    private volatile RawFrameListener rawListener;
    private volatile DeviceInfo deviceInfo;
    private volatile boolean expectDisconnect;

    private CompletableFuture<DeviceInfo> connectFuture;
    private ScheduledFuture<?> handshakeTimeout;
    private DeviceInfo.Builder handshake;

    /**
     * The serial of the board this instance is bound to, from its first successful
     * handshake. Null until then, and never cleared afterwards — the binding
     * outlives the connection, because an instance's identity is the board and not
     * the port it happened to be on. Guarded by {@link #lock}.
     *
     * <p>A board reporting a zero-length serial binds to the empty array and will
     * then match any other serial-less board. That is deliberate: declining to bind
     * would leave the instance unbound and therefore accepting <em>every</em> board,
     * which is strictly worse. It takes degenerate firmware to reach — the handshake
     * cannot complete without a SERIAL event ({@code DeviceInfo.Builder.isComplete}),
     * so this needs an empty payload on the wire rather than a missing one.
     */
    private byte[] boundSerial;

    MiniBoardEngine(String portName, Transport transport) {
        this.portName = portName;
        this.transport = transport;

        final int id = INSTANCE_COUNT.incrementAndGet();
        this.callbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "miniboard-callback-" + id);
                t.setDaemon(true);
                return t;
            }
        });
        this.dispatchExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "miniboard-dispatch-" + portName);
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Opens the port and waits for the hello sequence.
     *
     * @param targetPort a new port to retarget this instance at, or null to keep
     *                   the current one
     */
    CompletableFuture<DeviceInfo> connect(String targetPort) {
        CompletableFuture<DeviceInfo> future = new CompletableFuture<DeviceInfo>();

        State previous;
        final int gen;
        final CountDownLatch settleConnect;
        synchronized (lock) {
            previous = state;
            if (previous == State.IDLE) {
                if (targetPort != null) {
                    portName = targetPort;
                }
                gen = ++generation;
                state = State.CONNECTING;
                connectFuture = future;
                handshake = new DeviceInfo.Builder();
                // Every per-connection latch starts clean, so the new generation
                // cannot inherit the previous one's teardown or reboot state.
                teardownDone = false;
                portOpened = false;
                expectDisconnect = false;
                running = false;
                readerThread = null;
                // Armed before the first blocking call, so a teardown that ends this
                // generation has something to wait on before releasing a successor.
                connectSettled = new CountDownLatch(1);
                settleConnect = connectSettled;
            } else {
                gen = ANY_GENERATION;
                settleConnect = null;
            }
        }
        if (previous != State.IDLE) {
            // Never inline on the caller's thread: callbacks belong to the callback thread.
            completeOnCallbackThread(future, null, new MiniBoardException(
                    previous == State.CLOSED
                            ? "MiniBoard on " + portName + " is closed and cannot reconnect;"
                                    + " construct a new one"
                            : "connect() on " + portName + " is already in progress"
                                    + " or established (state " + previous + ")"));
            return future;
        }

        // Everything from here to the return is this generation's turn with the
        // transport. The latch is released on every exit path, because a teardown
        // that never sees it released can never hand IDLE to a successor.
        try {
            // Never reopen the port under a reader that has not exited: SerialTransport
            // reopens happily, and the old reader would then consume this connection's
            // bytes. Refusing is diagnosable; silent byte theft is not.
            Thread staleReader = lingeringReader;
            if (staleReader != null && staleReader.isAlive()) {
                try {
                    staleReader.join(READER_HANDOFF_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (staleReader.isAlive()) {
                    // Nothing of ours is on the transport — the port was never
                    // opened — and failConnect tears down synchronously on this
                    // thread, so release first exactly as the failed-open path
                    // below does, or the teardown would wait out SETTLE_TIMEOUT_MS
                    // on the very attempt that is calling it and then decline to
                    // publish IDLE, leaving the instance stuck in CONNECTING.
                    settleConnect.countDown();
                    failConnect(gen, new MiniBoardException(
                            "The reader thread for the previous connection to " + portName
                                    + " has not exited after " + READER_HANDOFF_TIMEOUT_MS
                                    + " ms. Reopening the port now would let it consume this"
                                    + " connection's data, so the reconnect is refused."));
                    return future;
                }
            }
            lingeringReader = null;

            try {
                transport.open(portName);
            } catch (IOException e) {
                // Nothing of ours is on the transport now, and failConnect tears
                // down synchronously on this thread — release first or it would
                // wait for the attempt that is calling it.
                settleConnect.countDown();
                failConnect(gen, new MiniBoardIoException("Failed to open " + portName, e));
                return future;
            }

            // The port is open. Re-acquire the lock and re-check: a teardown that
            // landed while open() was blocking must not leave a live reader on an
            // open port. teardownDone is the signal, not the state: a non-terminal
            // teardown of this generation is by now deliberately still holding
            // CONNECTING, waiting for this attempt to finish.
            boolean aborted;
            CompletableFuture<DeviceInfo> abandoned = null;
            Thread reader = null;
            synchronized (lock) {
                portOpened = true;
                if (state != State.CONNECTING || gen != generation || teardownDone) {
                    aborted = true;
                    // This attempt owns closing what it opened, so the flag goes
                    // back down with it: nothing else must try to close it again.
                    portOpened = false;
                    abandoned = connectFuture;
                    connectFuture = null;
                } else {
                    aborted = false;
                    running = true;
                    DecoderSink sink = new DecoderSink(gen);
                    reader = new Thread(new ReaderLoop(gen, new FrameDecoder(sink), sink),
                            "miniboard-reader-" + portName + "-" + gen);
                    reader.setDaemon(true);
                    readerThread = reader;
                }
            }

            if (aborted) {
                transport.close();
                if (abandoned != null) {
                    completeOnCallbackThread(abandoned, null, new MiniBoardException(
                            "connect() on " + portName + " was aborted by a teardown"));
                }
                return future;
            }

            reader.start();

            ScheduledFuture<?> scheduled = SCHEDULER.schedule(new Runnable() {
                @Override
                public void run() {
                    failHandshake(gen, new MiniBoardException(
                            "No hello sequence from " + portName + " within "
                                    + MiniBoard.HANDSHAKE_TIMEOUT_MS
                                    + " ms. The port may not be a MiniBoard."));
                }
            }, MiniBoard.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // Guard the race where the handshake resolved, or a teardown ran, before
            // the field was assigned: an orphaned timer must not outlive the
            // connection. teardownDone again, because a teardown waiting on this
            // attempt has already cancelled whatever timer it found and will not
            // look again.
            boolean stale;
            synchronized (lock) {
                stale = state != State.CONNECTING || connectFuture == null
                        || gen != generation || teardownDone;
                if (!stale) {
                    handshakeTimeout = scheduled;
                }
            }
            if (stale) {
                scheduled.cancel(false);
            }

            return future;
        } finally {
            // Idempotent: countDown past zero is a no-op, so the failed-open path
            // releasing early is harmless here.
            settleConnect.countDown();
        }
    }

    /**
     * Fails one connect attempt that never got a live port, and returns the
     * instance to IDLE so the caller can retry — a board that has not finished
     * re-enumerating is the ordinary case, not a fatal one.
     *
     * <p>The teardown still runs: anything submitted while CONNECTING is sitting in
     * the queue with no deadline armed, and nothing would ever drain it. Unlike the
     * pre-reconnect engine this does <em>not</em> release the executors — they
     * belong to the instance, which is still alive. Callers finished with a board
     * must {@code close()} it.
     */
    private void failConnect(int gen, MiniBoardException error) {
        CompletableFuture<DeviceInfo> pending;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            pending = connectFuture;
            connectFuture = null;
            handshake = null;
        }
        if (pending != null) {
            completeOnCallbackThread(pending, null, error);
        }
        // portOpened is still false, so this closes no transport and fires no
        // spurious onDisconnected, and stays non-blocking on the caller's thread.
        teardown(gen, null, false);
    }

    /**
     * Closes the port, fails outstanding commands, and releases the threads.
     * Idempotent, and blocks until the teardown has actually run — including when
     * another teardown got there first and this call has to wait for it.
     *
     * <p>One exception, and it is why {@code disconnect()} rather than this method
     * carries the "safe to reconnect the moment it returns" promise: a terminal
     * teardown does not wait on the current generation's {@code connect()} attempt
     * ({@code connectInFlight} is captured only when {@code !terminal}). A
     * {@code close()} landing while a {@code connect()} is inside a blocking
     * {@code transport.open()} can therefore return before the port is shut, with
     * the aborting {@code connect()} closing it a moment later. Harmless here —
     * CLOSED admits no successor that could race for the transport — but it means
     * "blocks until the teardown has run" is a statement about this call's own
     * teardown, not about the port being closed on every path.
     */
    void close() {
        teardown(ANY_GENERATION, null, true);
    }

    /**
     * Drops the connection but leaves the instance reconnectable. Idempotent, and
     * blocks until the port is closed and the reader joined, so the caller may
     * reconnect the moment it returns.
     */
    void disconnect() {
        int gen;
        synchronized (lock) {
            gen = generation;
        }
        teardown(gen, null, false);
    }

    boolean isClosed() {
        synchronized (lock) {
            return state == State.CLOSED;
        }
    }

    String getPortName() {
        return portName;
    }

    boolean isConnected() {
        synchronized (lock) {
            return state == State.READY;
        }
    }

    /**
     * @return the identity from the most recent successful handshake, or null if
     *         there has never been one. Retained across a disconnect, so it may be
     *         stale when {@link #isConnected()} is false.
     */
    DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    void setListener(MiniBoardListener listener) {
        this.listener = listener;
    }

    void setRawFrameListener(RawFrameListener rawListener) {
        this.rawListener = rawListener;
    }

    void expectDisconnect() {
        this.expectDisconnect = true;
    }

    // ----------------------------------------------------------- command engine

    CompletableFuture<byte[]> submit(Opcode opcode, byte[] data) {
        CompletableFuture<byte[]> future = new CompletableFuture<byte[]>();
        Frame frame;
        try {
            frame = FrameEncoder.encodeRequest(opcode, data);
        } catch (RuntimeException e) {
            completeOnCallbackThread(future, null, e);
            return future;
        }

        PendingCommand pending = new PendingCommand(opcode, frame, future);
        Throwable rejection = null;
        boolean armNow = false;
        synchronized (lock) {
            if (state == State.CLOSED) {
                rejection = new MiniBoardException("MiniBoard on " + portName
                        + " is closed");
            } else if (state == State.IDLE) {
                // Not merely unhelpful to queue: a command accepted here has no
                // deadline armed and no guarantee a connect() is coming, so it would
                // hang until close() rather than fail.
                rejection = new MiniBoardException("Not connected to " + portName
                        + "; call connect() first");
            } else if (queue.size() >= MiniBoard.MAX_QUEUED_COMMANDS) {
                rejection = new MiniBoardException(String.format(Locale.ROOT,
                        "Command queue for %s is full: %d commands already queued, "
                                + "limit is %d. The device is not keeping up.",
                        portName, queue.size(), MiniBoard.MAX_QUEUED_COMMANDS));
            } else {
                queue.add(pending);
                // The deadline starts at submit() or at handshake completion,
                // whichever is later. Before READY the handshake owns the clock
                // and has its own budget; charging the wait to a 1000 ms command
                // would expire it on a device that simply hellos slowly.
                armNow = state == State.READY;
            }
        }
        if (rejection != null) {
            completeOnCallbackThread(future, null, rejection);
            return future;
        }

        if (armNow) {
            armDeadline(pending);
        }

        postDispatch();
        return future;
    }

    /**
     * Hands one dispatch attempt to {@link #dispatchExecutor}. Every caller uses
     * this rather than calling {@link #dispatchNext()} inline, so no dispatch ever
     * runs on the shared scheduler, the reader thread, or a caller's thread.
     *
     * <p>Losing the attempt to a rejected {@code execute()} cannot strand a
     * command. The executor only rejects once a terminal {@link #teardown} has
     * closed it, and that teardown publishes {@code state = CLOSED} — which makes
     * {@code submit()} reject every later command — strictly before it drains the
     * queue and fails everything it finds. A command therefore either predates the
     * drain and is failed by it, or is refused by {@code submit()} outright. A
     * non-terminal teardown never closes the executor at all. Independently of
     * that, a dispatch is never the thing that resolves a command: its deadline is
     * armed by {@code submit()} or by {@code checkHandshakeComplete()}, so an
     * undispatched command still expires on its own clock.
     */
    private void postDispatch() {
        try {
            dispatchExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    dispatchNext();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shut down: see above. Nothing is left that this dispatch could send.
        }
    }

    /**
     * Starts one command's timeout clock. Called from {@code submit()} for a
     * board that is already READY, and from {@code checkHandshakeComplete()} for
     * the backlog it releases.
     *
     * <p>Usually exactly one of the two, but not guaranteed to be: a command can be
     * armed a second time if it is submitted in the narrow window after a
     * teardown has drained the queue but before it has published IDLE — {@code
     * submit()} still reads {@code state == READY} there, so it arms the command
     * and queues it, and it then survives into the next connection, where {@code
     * checkHandshakeComplete()}'s backlog loop arms it again. A re-arm therefore
     * cancels whatever timer this command already carries before scheduling the
     * replacement, so the earlier timer can never survive to fire on its own,
     * shorter budget and complete the command with a spurious timeout.
     *
     * <p>That cancel-then-reschedule runs under {@link #lock} because it is a
     * read-modify-write of {@code command.timeout}. Declaring the field volatile
     * keeps each read whole but does not make the sequence atomic: two arms of the
     * same command could otherwise both cancel the same previous timer, schedule
     * one replacement each, and leave the loser's timer orphaned — the exact defect
     * this method exists to prevent, merely through a narrower window. Holding the
     * lock also honours {@link #cancelTimeout}'s documented contract, which every
     * other call site already does.
     *
     * <p>Safe to hold the lock here: {@code SCHEDULER.schedule} only enqueues onto
     * a delay queue and returns long before the task can run, so it never waits on
     * a task, a device or a caller. Both callers release {@code lock} before
     * arriving ({@code submit()} at the end of its critical section, {@code
     * checkHandshakeComplete()} before its backlog loop), and the monitor is
     * reentrant in any case.
     */
    private void armDeadline(PendingCommand command) {
        synchronized (lock) {
            if (command.isSettled()) {
                return; // already answered, timed out, or drained by teardown
            }
            cancelTimeout(command);
            command.timeout = SCHEDULER.schedule(new TimeoutTask(command),
                    command.opcode.getTimeoutMs(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends the next queued command if the line is free and the handshake is done.
     * Runs only on {@link #dispatchExecutor}; reach it through
     * {@link #postDispatch()}, never inline.
     */
    private void dispatchNext() {
        PendingCommand next;
        final int gen;
        synchronized (lock) {
            if (inFlight != null || state != State.READY) {
                return; // busy, or still handshaking: commands stay queued
            }
            next = queue.poll();
            if (next == null) {
                return;
            }
            inFlight = next;
            gen = generation;
        }

        // Write outside the lock: never hold it across blocking I/O.
        long nanos = System.nanoTime();
        try {
            transport.write(next.frame.getRawBytes());
            emitTxFrame(next.frame, nanos);
        } catch (IOException e) {
            handleFailure(gen, new MiniBoardIoException("Write failed on " + portName, e));
        } catch (RuntimeException e) {
            // dispatchNext() runs as a Runnable posted to dispatchExecutor (see
            // postDispatch()): an exception escaping here would otherwise reach
            // only the executor's default handler and be lost silently, leaving
            // inFlight set and the caller's future to expire on its deadline
            // instead of failing with the real cause.
            handleFailure(gen, new MiniBoardException("Write failed on " + portName, e));
        }
        // No timer is armed here: submit() already started this command's deadline.
    }

    /**
     * Fails one command when its deadline passes, wherever it happens to be:
     * still queued, in flight, or already settled (in which case: nothing).
     */
    private final class TimeoutTask implements Runnable {
        private final PendingCommand command;

        TimeoutTask(PendingCommand command) {
            this.command = command;
        }

        @Override
        public void run() {
            boolean wasInFlight = false;
            synchronized (lock) {
                if (inFlight == command) {
                    inFlight = null;
                    wasInFlight = true;
                } else if (!queue.remove(command)) {
                    return; // already answered, timed out, or drained by teardown
                }
            }
            if (command.claim()) {
                completeOnCallbackThread(command.future, null, new MiniBoardTimeoutException(
                        command.opcode, command.opcode.getTimeoutMs()));
            }
            if (wasInFlight) {
                postDispatch(); // the line is free again
            }
        }
    }

    private void handleResponse(Frame frame) {
        PendingCommand command;
        synchronized (lock) {
            command = inFlight;
            if (command == null || command.opcode.getValue() != frame.getOpcode()) {
                final int op = frame.getOpcode();
                final Opcode awaited = command == null ? null : command.opcode;
                notifyError(new MiniBoardException(
                        "Unexpected response 0x" + String.format(Locale.ROOT, "%02X", op)
                                + " on " + portName
                                + (awaited == null ? " with no command in flight"
                                                   : " while awaiting " + awaited)));
                return;
            }
            inFlight = null;
            cancelTimeout(command);
        }

        if (command.claim()) {
            Status status = frame.getStatus();
            if (status == null || !status.isOk()) {
                completeOnCallbackThread(command.future, null, new MiniBoardStatusException(
                        command.opcode, status, frame.getRawStatus()));
            } else {
                completeOnCallbackThread(command.future, frame.getData(), null);
            }
        }
        postDispatch();
    }

    // --------------------------------------------------------------- handshake

    private void handleEvent(Frame frame) {
        EventType type = frame.getEvent();
        if (type == null) {
            return; // unknown event from newer firmware; already surfaced raw
        }

        DeviceInfo.Builder builder;
        synchronized (lock) {
            builder = handshake;
        }

        if (builder != null) {
            switch (type) {
                case NAME:
                    builder.name = ascii(frame.getData());
                    checkHandshakeComplete();
                    return;
                case VERSION:
                    if (frame.getDataLength() >= 2) {
                        builder.versionMajor = frame.getDataByte(0);
                        builder.versionMinor = frame.getDataByte(1);
                    }
                    checkHandshakeComplete();
                    return;
                case SERIAL:
                    builder.serial = frame.getData();
                    checkHandshakeComplete();
                    return;
                case SIDE:
                    if (frame.getDataLength() >= 1) {
                        builder.side = Side.fromValue(frame.getDataByte(0));
                    }
                    checkHandshakeComplete();
                    return;
                case TYPE:
                    if (frame.getDataLength() >= 1) {
                        builder.configType = frame.getDataByte(0);
                    }
                    checkHandshakeComplete();
                    return;
                default:
                    break; // runtime events can arrive mid-handshake
            }
        }

        dispatchEvent(type, frame);
    }

    private void dispatchEvent(EventType type, Frame frame) {
        switch (type) {
            case KEY: {
                if (frame.getDataLength() < 2) {
                    return;
                }
                final int index = frame.getDataByte(0);
                final int keyId = frame.getDataByte(1);
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onKey(index, keyId);
                        }
                    }
                });
                break;
            }
            case TYPO_REJECTED:
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onTypoRejected();
                        }
                    }
                });
                break;
            case LED_FINISH: {
                final int led = frame.getDataLength() >= 1 ? frame.getDataByte(0) : -1;
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onLedFinish(led);
                        }
                    }
                });
                break;
            }
            case MSG: {
                final String text = ascii(frame.getData());
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onMessage(text);
                        }
                    }
                });
                break;
            }
            case SIDE: {
                if (frame.getDataLength() < 1) {
                    return;
                }
                final Side side = Side.fromValue(frame.getDataByte(0));
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onSideChanged(side);
                        }
                    }
                });
                break;
            }
            default:
                break; // NAME/VERSION/SERIAL/TYPE outside the handshake: nothing to do
        }
    }

    private void checkHandshakeComplete() {
        CompletableFuture<DeviceInfo> future;
        DeviceInfo info;
        MiniBoardException rejection = null;
        List<PendingCommand> backlog = null;
        int gen;

        synchronized (lock) {
            if (handshake == null || !handshake.isComplete()) {
                return;
            }
            gen = generation;
            info = handshake.build();
            handshake = null;
            future = connectFuture;
            connectFuture = null;
            if (handshakeTimeout != null) {
                handshakeTimeout.cancel(false);
                handshakeTimeout = null;
            }
            if (!DeviceInfo.EXPECTED_NAME.equals(info.getName())) {
                // No state assignment on any rejection path: teardown returns the
                // instance to IDLE.
                rejection = new MiniBoardException("Device on " + portName
                        + " reported name \"" + info.getName() + "\", expected \""
                        + DeviceInfo.EXPECTED_NAME + "\"");
            } else if (boundSerial != null && !Arrays.equals(boundSerial, info.getSerial())) {
                // An instance is bound to a board, not to a port. Accepting a
                // different one would let a later setKey() or save() reach the
                // wrong device after COM numbers shuffle.
                //
                // This message is quoted verbatim in docs/usage.md and pinned by
                // MiniBoardReconnectTest.reconnectingToADifferentBoardIsRefused.
                // If you reword it, update both — the guide has no other guard.
                rejection = new MiniBoardException("The device now on " + portName
                        + " has serial " + info.getSerialHex()
                        + ", but this MiniBoard is bound to " + hex(boundSerial)
                        + ". Refusing to reconnect to a different board;"
                        + " construct a new MiniBoard for it.");
            } else {
                state = State.READY;
                deviceInfo = info;
                if (boundSerial == null) {
                    // First successful handshake: this is the board from now on.
                    // getSerial() already returns a copy, so nothing shared escapes.
                    boundSerial = info.getSerial();
                }
                // Snapshot the backlog in the same critical section that publishes
                // READY, so anything queued after this point sees READY in submit()
                // and arms itself rather than waiting on a loop that has already
                // run. That makes one arm per command the norm but not a guarantee:
                // a command submitted inside the previous teardown's window was
                // already armed by submit() and is in this backlog too, which is why
                // armDeadline() cancels any timer it finds before re-arming.
                backlog = new ArrayList<PendingCommand>(queue);
            }
        }

        if (rejection != null) {
            if (future != null) {
                completeOnCallbackThread(future, null, rejection);
            }
            teardownAsync(gen, null, false);
            return;
        }

        // The handshake window is over, so the queued commands' clocks start now.
        for (PendingCommand p : backlog) {
            armDeadline(p);
        }

        if (future != null) {
            completeOnCallbackThread(future, info, null);
        }
        postDispatch(); // release anything queued during the handshake
    }

    private void failHandshake(int gen, final MiniBoardException error) {
        CompletableFuture<DeviceInfo> future;
        synchronized (lock) {
            if (gen != generation || connectFuture == null) {
                return; // already resolved, or a superseded connection's timer
            }
            future = connectFuture;
            connectFuture = null;
            handshake = null;
        }
        completeOnCallbackThread(future, null, error);
        teardownAsync(gen, null, false);
    }

    // ------------------------------------------------------------------ reader

    /**
     * One decoder sink per connection. Carries the generation it belongs to, so a
     * frame decoded after the connection was superseded is dropped instead of being
     * handled as if the live connection had sent it. Owns its own read timestamp
     * for the same reason.
     */
    private final class DecoderSink implements FrameDecoder.Sink {
        private final int gen;

        /** Timestamp of the read currently being processed, for the raw hook. */
        volatile long chunkNanos;

        DecoderSink(int gen) {
            this.gen = gen;
        }

        @Override
        public void onFrame(Frame frame) {
            if (gen != generation) {
                return; // superseded connection
            }
            emitRxFrame(frame, chunkNanos);
            if (frame.getType() == FrameType.RESPONSE) {
                handleResponse(frame);
            } else {
                handleEvent(frame);
            }
        }

        @Override
        public void onError(FrameError reason, byte[] discarded) {
            if (gen != generation) {
                return; // superseded connection
            }
            emitFrameError(discarded, reason, chunkNanos);
        }
    }

    private final class ReaderLoop implements Runnable {

        /**
         * The generation this reader was started for. Captured, never read from the
         * volatile: the teardown's join is bounded at
         * {@link #READER_JOIN_TIMEOUT_MS} and proceeds regardless, so a reader wedged
         * in a native call can outlive its connection and wake after the generation
         * counter has already been bumped by a later {@code connect()}. Reading the
         * live counter then would report the failure against whichever generation is
         * current and tear down a healthy connection.
         *
         * <p>It wakes after the <em>bump</em>, not after a reopen: {@code connect()}
         * increments {@link #generation} before it reaches the reader handoff guard,
         * and that guard refuses to reopen the port at all while this thread is
         * still alive. So the window this field guards is real and reachable — a
         * refused reconnect leaves exactly this state — but the stale reader is
         * never fed a live connection's bytes.
         */
        private final int gen;
        private final FrameDecoder decoder;
        private final DecoderSink sink;

        ReaderLoop(int gen, FrameDecoder decoder, DecoderSink sink) {
            this.gen = gen;
            this.decoder = decoder;
            this.sink = sink;
        }

        /**
         * {@code running} alone is not enough: it is instance-wide, so once a new
         * connection sets it true a stale reader would resume. The generation check
         * is what actually stops it.
         */
        private boolean live() {
            return running && gen == generation;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[READ_BUFFER];
            try {
                while (live()) {
                    int count = transport.read(buffer);
                    long nanos = System.nanoTime();
                    long millis = System.currentTimeMillis();
                    // Refresh unconditionally: a TIMEOUT_PARTIAL flushed on the
                    // idle path below is stamped with this too, and the previous
                    // chunk's nanoTime could by then be seconds stale.
                    sink.chunkNanos = nanos;

                    if (count > 0) {
                        decoder.feed(buffer, 0, count, millis);
                    } else if (count < 0) {
                        if (live()) {
                            handleFailure(gen, new MiniBoardException(
                                    "Port " + portName + " closed by the device"));
                        }
                        return;
                    } else {
                        // Idle: flush any partial frame that has gone stale.
                        decoder.checkStale(millis, Opcode.DEFAULT_TIMEOUT_MS);
                    }
                }
            } catch (IOException e) {
                if (live()) {
                    handleFailure(gen,
                            new MiniBoardIoException("Read failed on " + portName, e));
                }
            } catch (RuntimeException e) {
                if (live()) {
                    handleFailure(gen,
                            new MiniBoardException("Reader failed on " + portName, e));
                }
            }
        }
    }

    // --------------------------------------------------------------- raw hook

    private void emitTxFrame(final Frame frame, final long nanos) {
        final RawFrameListener r = rawListener;
        if (r == null) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                r.onTxFrame(frame, nanos);
            }
        });
    }

    private void emitRxFrame(final Frame frame, final long nanos) {
        final RawFrameListener r = rawListener;
        if (r == null) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                r.onRxFrame(frame, nanos);
            }
        });
    }

    private void emitFrameError(final byte[] discarded, final FrameError reason,
                                final long nanos) {
        final RawFrameListener r = rawListener;
        if (r == null) {
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                r.onFrameError(discarded, reason, nanos);
            }
        });
    }

    // --------------------------------------------------------------- teardown

    private void handleFailure(int gen, MiniBoardException error) {
        // Reached from the reader thread, the dispatch thread and SCHEDULER
        // threads; none of them may run the blocking teardown.
        teardownAsync(gen, expectDisconnect ? null : error, false);
    }

    /**
     * Runs {@link #teardown} on a dedicated daemon thread. Teardown blocks on
     * {@code transport.close()} and on joining the reader, so it must never run
     * on the reader thread (self-join) or on the shared SCHEDULER (which would
     * stall every other board's timeouts).
     */
    private void teardownAsync(final int gen, final Throwable cause, final boolean terminal) {
        Thread t = new TeardownThread(new Runnable() {
            @Override
            public void run() {
                teardown(gen, cause, terminal);
            }
        }, "miniboard-teardown-" + portName);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Marker type. The only thread on which a rejected completion may safely run
     * inline: it is ours, it is already tearing this board down, and it serves no
     * other board.
     */
    private static final class TeardownThread extends Thread {
        TeardownThread(Runnable target, String name) {
            super(target, name);
        }
    }

    /**
     * Ends a connection. With {@code terminal} false the instance returns to IDLE
     * and can be reconnected; with it true the instance is closed for good and the
     * executors are released.
     *
     * <p>A non-terminal teardown does <em>not</em> publish IDLE until the generation
     * it is ending has finished with the transport: its {@code connect()} attempt
     * has returned or aborted, the port is closed and the reader is joined. There is
     * one {@link Transport} per instance, so releasing a successor any earlier would
     * put two generations on it at once — and the superseded one would then close the
     * live one's port. A terminal teardown needs no such wait: CLOSED admits no
     * successor, so it publishes immediately, which is what makes {@code submit()}
     * and {@code connect()} refuse from that moment on.
     *
     * <p>A teardown that loses the claim to a rival does not return early either; it
     * waits for the winner, so {@code disconnect()} and {@code close()} never hand
     * back to a caller who then reconnects onto a port that is still being closed.
     *
     * @param gen the generation this teardown belongs to. Ignored when terminal —
     *            {@code close()} applies whatever is current. A non-terminal
     *            teardown for a superseded generation returns without acting, which
     *            is what stops a dead connection's late failure from killing a live
     *            one.
     */
    private void teardown(int gen, final Throwable cause, boolean terminal) {
        boolean connectionTeardown;
        boolean wasLive = false;
        CountDownLatch settled;
        CountDownLatch connectInFlight = null;

        synchronized (lock) {
            if (!terminal && gen != generation) {
                return; // a superseded connection's failure must not touch this one
            }
            if (!terminal && state == State.CLOSED) {
                return; // disconnect() after close() is a no-op, not an error
            }
            connectionTeardown = !teardownDone;
            if (connectionTeardown) {
                teardownDone = true;
                running = false;
                // Must land with the claim, or a hello arriving in the window below
                // could still drive checkHandshakeComplete back to READY.
                handshake = null;
                wasLive = portOpened;
                portOpened = false;
                if (handshakeTimeout != null) {
                    handshakeTimeout.cancel(false);
                    handshakeTimeout = null;
                }
                teardownSettled = new CountDownLatch(1);
                if (!terminal) {
                    connectInFlight = connectSettled;
                }
            }
            settled = teardownSettled;
            if (terminal) {
                state = State.CLOSED;
            }
        }

        if (!connectionTeardown) {
            // A rival claimed this generation. Wait for it rather than returning to a
            // caller who would reasonably take our return as "the port is closed now".
            awaitSettled(settled, "another teardown of");
            if (terminal) {
                dispatchExecutor.shutdown();
                callbackExecutor.shutdown();
            }
            return;
        }

        boolean handedOver;
        try {
            // See the javadoc above: IDLE must not be published, and the port must
            // not be closed, while this generation's connect() may still be inside
            // transport.open().
            handedOver = connectInFlight == null
                    || awaitSettled(connectInFlight, "the connect attempt on");

            // Collected unconditionally, even if the wait expired: nothing is ever
            // gathered that this call will not go on to complete, and no caller may
            // be left holding an unresolvable future however badly the wait went.
            // Nothing can be added back once the state leaves READY, because
            // submit() rejects and dispatchNext() will not dispatch.
            List<PendingCommand> orphaned = new ArrayList<PendingCommand>();
            CompletableFuture<DeviceInfo> pendingConnect;
            synchronized (lock) {
                // A teardown during the handshake must not leave connect() hanging.
                pendingConnect = connectFuture;
                connectFuture = null;

                if (inFlight != null) {
                    cancelTimeout(inFlight);
                    orphaned.add(inFlight);
                    inFlight = null;
                }
                for (PendingCommand p : queue) {
                    cancelTimeout(p);
                    orphaned.add(p);
                }
                queue.clear();
            }

            if (handedOver && wasLive) {
                transport.close();

                Thread reader = readerThread;
                if (reader != null && reader != Thread.currentThread()) {
                    try {
                        reader.join(READER_JOIN_TIMEOUT_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // A reader that outlived its join blocks the next connect()
                    // rather than being left to race it.
                    lingeringReader = reader.isAlive() ? reader : null;
                }
            }

            Throwable failure = cause != null ? cause
                    : new MiniBoardException("Connection to " + portName + " closed");
            for (PendingCommand p : orphaned) {
                if (p.claim()) {
                    completeOnCallbackThread(p.future, null, failure);
                }
            }
            if (pendingConnect != null) {
                completeOnCallbackThread(pendingConnect, null, cause != null ? cause
                        : new MiniBoardException("Connection to " + portName
                                + " closed before the handshake completed"));
            }

            if (!terminal && handedOver) {
                synchronized (lock) {
                    // A close() may have landed while this ran; CLOSED outranks IDLE.
                    if (state != State.CLOSED) {
                        state = State.IDLE;
                    }
                }
            }

            if (handedOver && wasLive) {
                // After the state is published, so that by the time a listener sees
                // it the port is closed, the reader is joined and the state is
                // already IDLE: reconnecting from this callback is safe.
                //
                // Still inside the try, and so strictly before the latch below
                // releases: a rival close() waiting on that latch goes straight on
                // to shut the callback executor down, and this must already be
                // queued on it by then or the notification would be dropped.
                post(new Runnable() {
                    @Override
                    public void run() {
                        MiniBoardListener l = listener;
                        if (l != null) {
                            l.onDisconnected(cause);
                        }
                    }
                });
            }
        } finally {
            // Always, so a rival teardown waiting on us can never hang.
            settled.countDown();
        }

        if (terminal) {
            // Last: everything above has already been handed to the executors. A
            // dispatch still queued or running here is harmless — the state is
            // CLOSED, so dispatchNext() returns without touching the queue.
            dispatchExecutor.shutdown();
            callbackExecutor.shutdown();
        }
    }

    /**
     * Waits, with a bound, for one of this generation's settle latches.
     *
     * <p>Only ever called from a thread that is allowed to block: a
     * {@link TeardownThread}; the application thread inside {@code disconnect()} or
     * {@code close()}; or the application thread inside {@code connect()}, which
     * reaches here through {@code failConnect}'s synchronous teardown on both of
     * its refusal paths (the reader handoff and a failed open). Never from the
     * shared {@link #SCHEDULER}, the reader thread, or {@link #dispatchExecutor}.
     *
     * <p>The {@code connect()} paths are only safe because each releases
     * {@link #connectSettled} first — see that field's invariant.
     *
     * @return true if it settled; false if the bound expired, in which case the
     *         caller must not go on to touch the transport or publish IDLE
     */
    private boolean awaitSettled(CountDownLatch latch, String what) {
        try {
            if (latch.await(SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError(new MiniBoardException("Interrupted waiting for " + what + " "
                    + portName + " to finish; the port has been left as it was"));
            return false;
        }
        // Loudly, and without proceeding: leaving the state where it is keeps a
        // successor generation off a transport the previous one may still be inside.
        notifyError(new MiniBoardException(String.format(Locale.ROOT,
                "Timed out after %d ms waiting for %s %s to finish. The port has been"
                        + " left open and this instance will not accept a reconnect;"
                        + " close() it.",
                SETTLE_TIMEOUT_MS, what, portName)));
        return false;
    }

    // -------------------------------------------------------------- utilities

    /** Cancels a command's deadline timer. Call under {@link #lock}. */
    private static void cancelTimeout(PendingCommand command) {
        ScheduledFuture<?> t = command.timeout;
        if (t != null) {
            t.cancel(false);
        }
    }

    /**
     * Completes a future on the callback thread. Unlike a notification, a
     * completion may never be dropped: if the executor is already shut down the
     * completion is handed to a short-lived daemon thread instead, because a
     * completion arriving on an odd thread during teardown beats a future that
     * nothing can ever resolve.
     *
     * <p>It is deliberately not run inline. {@code CompletableFuture.complete}
     * synchronously runs dependent stages that were registered without an
     * executor, so inline would put arbitrary consumer code on whichever thread
     * happened to call — which for a late {@code TimeoutTask} is the shared
     * {@code miniboard-timeout} scheduler that serves every board in the JVM.
     * The one exception is our own teardown thread, which is already dedicated to
     * tearing this board down.
     */
    private <T> void completeOnCallbackThread(final CompletableFuture<T> future,
                                              final T value, final Throwable error) {
        Runnable completion = guarded(new Runnable() {
            @Override
            public void run() {
                if (error != null) {
                    future.completeExceptionally(error);
                } else {
                    future.complete(value);
                }
            }
        });
        try {
            callbackExecutor.execute(completion);
        } catch (RejectedExecutionException e) {
            if (Thread.currentThread() instanceof TeardownThread) {
                completion.run();
                return;
            }
            Thread t = new Thread(completion, "miniboard-complete-" + portName);
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Runs a notification on the callback thread. Dropping one during shutdown
     * is acceptable; losing a future completion is not — see
     * {@link #completeOnCallbackThread}.
     */
    private void post(final Runnable task) {
        try {
            callbackExecutor.execute(guarded(task));
        } catch (RejectedExecutionException e) {
            // Executor already shut down; nothing left to deliver to.
        }
    }

    /** Wraps a task so a throwing listener cannot kill the callback thread. */
    private Runnable guarded(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    safeOnError(t);
                }
            }
        };
    }

    private void notifyError(final Throwable error) {
        post(new Runnable() {
            @Override
            public void run() {
                safeOnError(error);
            }
        });
    }

    /** Last line of defence: a throwing listener must not kill the callback thread. */
    private void safeOnError(Throwable error) {
        MiniBoardListener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onError(error);
        } catch (Throwable ignored) {
            // Nowhere left to report it.
        }
    }

    /**
     * Uppercase hex, for naming a serial in a message. {@code Locale.ROOT} because
     * the result is compared and read by people, not localised — and because the
     * same message also carries {@link DeviceInfo#getSerialHex()}, which formats the
     * same way.
     */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static String ascii(byte[] data) {
        try {
            return new String(data, "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            return new String(data); // US-ASCII is always available; unreachable
        }
    }

    private static final class PendingCommand {
        final Opcode opcode;
        final Frame frame;
        final CompletableFuture<byte[]> future;
        /** Whoever wins this CAS owns completing {@link #future}, exactly once. */
        private final AtomicBoolean settled = new AtomicBoolean();
        volatile ScheduledFuture<?> timeout;

        PendingCommand(Opcode opcode, Frame frame, CompletableFuture<byte[]> future) {
            this.opcode = opcode;
            this.frame = frame;
            this.future = future;
        }

        boolean claim() {
            return settled.compareAndSet(false, true);
        }

        boolean isSettled() {
            return settled.get();
        }
    }
}
