package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Frame;
import com.itsonlybinary.miniboard.protocol.FrameError;
import com.itsonlybinary.miniboard.protocol.Opcode;
import com.itsonlybinary.miniboard.protocol.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link MiniBoard}'s twenty commands end-to-end through a
 * {@link FakeTransport}, exercising {@code MiniBoardEngine}'s response handling
 * without any hardware.
 *
 * <p>Queue bounds, deadline semantics, and shutdown races belong to
 * {@link MiniBoardEngineReworkTest}; this class is about commands and the
 * response path they run over: the handshake, one round trip, the callback
 * overload, error mapping, single-command serialisation, timeouts, unsolicited
 * events, the raw hook, and {@code close()} failing pending futures.
 */
class MiniBoardEngineTest {

    /** Port name every fake board is opened on; appears in disconnect messages. */
    private static final String PORT_NAME = "TEST-PORT";

    private FakeTransport transport;
    private MiniBoard board;

    @AfterEach
    void tearDown() {
        if (board != null) {
            board.close();
        }
    }

    private MiniBoard newBoard() {
        transport = new FakeTransport();
        board = new MiniBoard(PORT_NAME, transport);
        return board;
    }

    /** Opens the board and completes the handshake with a canonical hello. */
    private DeviceInfo connect() throws Exception {
        MiniBoard b = newBoard();
        CompletableFuture<DeviceInfo> future = b.connect();
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");
        transport.queueHello();
        return future.get(3, TimeUnit.SECONDS);
    }

    private void waitForWriteCount(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (transport.getWritten().size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + count
                        + " write(s); saw " + transport.getWritten().size());
            }
            Thread.sleep(10);
        }
    }

    private static byte[] asciiBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    // ------------------------------------------------------------- handshake

    @Test
    @Timeout(5)
    void handshakeCompletesWithDeviceIdentity() throws Exception {
        DeviceInfo info = connect();

        assertEquals("MiniBoard54", info.getName());
        assertEquals(1, info.getVersionMajor());
        assertEquals(0, info.getVersionMinor());
        assertEquals("01020304", info.getSerialHex());
        assertEquals(Side.LEFT, info.getSide());
        assertEquals(0, info.getConfigType());
        assertTrue(info.isSelfTestPassed());
        assertFalse(info.isLedFault());
        assertFalse(info.isKeyStuck(0));
        assertTrue(board.isConnected());
    }

    /**
     * STATUS's payload is variable-length (N = ceil(key_count/8) bitmap bytes
     * between the fixed leading {@code result} and trailing {@code led_fault}
     * bytes), so this pins the decode against a failing self-test rather than
     * the all-zero default {@link #connect()} uses: key index 5 (bit 5 of byte 0)
     * reported stuck, and {@code led_fault} set.
     */
    @Test
    @Timeout(5)
    void statusEventReportsStuckKeysAndLedFault() throws Exception {
        MiniBoard b = newBoard();
        CompletableFuture<DeviceInfo> future = b.connect();
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");
        byte[] status = new byte[] {
                0x01,                                           // result: failed
                0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,        // key 5 stuck
                0x01                                             // led_fault
        };
        transport.queueHello("MiniBoard54", new byte[] {0x01, 0x02, 0x03, 0x04}, status);

        DeviceInfo info = future.get(3, TimeUnit.SECONDS);
        assertFalse(info.isSelfTestPassed());
        assertTrue(info.isKeyStuck(5));
        assertFalse(info.isKeyStuck(4));
        assertFalse(info.isKeyStuck(6));
        assertTrue(info.isLedFault());
        assertArrayEquals(new byte[] {0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
                info.getStuckKeysBitmap());
    }

    /**
     * Covers {@code MiniBoardEngine}'s 3000 ms handshake timer
     * ({@link MiniBoard#HANDSHAKE_TIMEOUT_MS}), including the stale-timer race
     * guard around it - previously zero coverage. A port that opens cleanly but
     * never sends the six hello events (not a MiniBoard, or one that is stuck)
     * must fail {@code connect()} within the timeout rather than hang forever.
     */
    @Test
    @Timeout(10)
    void silentPortFailsHandshake() throws Exception {
        MiniBoard b = newBoard();
        CompletableFuture<DeviceInfo> future = b.connect();
        assertTrue(transport.awaitOpened(2, TimeUnit.SECONDS), "transport.open() was never called");
        // Offer nothing: no hello sequence, ever.

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(MiniBoard.HANDSHAKE_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
            }
        });
        assertTrue(ex.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + ex.getCause());
        assertFalse(b.isConnected(), "a failed handshake must never leave the board connected");
    }

    // --------------------------------------------------------- command basics

    @Test
    @Timeout(5)
    void commandRoundTripReturnsDecodedValue() throws Exception {
        connect();

        CompletableFuture<Integer> future = board.getDebounce();
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.GET_DEBOUNCE.getValue(), 0x00, new byte[] {42}));

        assertEquals(42, future.get(2, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void callbackOverloadDeliversTheSameResultAsTheFuture() throws Exception {
        connect();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Integer> result = new AtomicReference<Integer>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        board.getDebounce(new MiniBoardCallback<Integer>() {
            @Override
            public void onSuccess(Integer value) {
                result.set(value);
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });

        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.GET_DEBOUNCE.getValue(), 0x00, new byte[] {7}));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback never fired");
        assertNull(error.get());
        assertEquals(Integer.valueOf(7), result.get());
    }

    @Test
    @Timeout(5)
    void nonOkStatusFailsFutureWithStatusException() throws Exception {
        connect();

        final CompletableFuture<Integer> future = board.getDebounce();
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(
                Opcode.GET_DEBOUNCE.getValue(), Status.OUT_OF_RANGE.getValue(), new byte[0]));

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(2, TimeUnit.SECONDS);
            }
        });

        assertTrue(ex.getCause() instanceof MiniBoardStatusException,
                "expected MiniBoardStatusException, got " + ex.getCause());
        MiniBoardStatusException statusEx = (MiniBoardStatusException) ex.getCause();
        assertEquals(Status.OUT_OF_RANGE, statusEx.getStatus());
        assertEquals(Opcode.GET_DEBOUNCE, statusEx.getOpcode());
    }

    // ------------------------------------------------------- serialisation

    /**
     * The invariant the entire protocol depends on: responses are matched only
     * by echoed opcode, with no sequence numbers, so at most one command may
     * ever be on the wire unanswered. If the engine dispatched the second
     * command before the first's response arrived, both would be in flight for
     * {@code GET_DEBOUNCE}'s opcode and {@code handleResponse} could not tell
     * them apart — the second write below would appear immediately instead of
     * only after the first response lands, and this test would fail on the
     * write-count assertion before ever reaching the value checks.
     */
    @Test
    @Timeout(5)
    void secondCommandWaitsForFirstResponse() throws Exception {
        connect();

        CompletableFuture<Integer> first = board.getDebounce();
        waitForWriteCount(1);

        CompletableFuture<Integer> second = board.getConfigType();
        Thread.sleep(200); // give a wrongly-eager engine time to write it
        assertEquals(1, transport.getWritten().size(),
                "the second command must not be written until the first completes");
        assertFalse(second.isDone());

        transport.offer(FakeTransport.response(Opcode.GET_DEBOUNCE.getValue(), 0x00, new byte[] {5}));
        assertEquals(5, first.get(2, TimeUnit.SECONDS));

        waitForWriteCount(2);
        transport.offer(FakeTransport.response(Opcode.GET_CONFIG_TYPE.getValue(), 0x00, new byte[] {3}));
        assertEquals(3, second.get(2, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void commandTimesOutWhenNoResponseArrives() throws Exception {
        connect();

        final CompletableFuture<Integer> future = board.getDebounce();
        waitForWriteCount(1);
        // No response is ever offered; GET_DEBOUNCE carries a 1000 ms timeout.

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(3, TimeUnit.SECONDS);
            }
        });

        assertTrue(ex.getCause() instanceof MiniBoardTimeoutException,
                "expected MiniBoardTimeoutException, got " + ex.getCause());
    }

    /**
     * {@code dispatchNext()} runs as a {@code Runnable} posted to the per-board
     * dispatch executor. A checked {@code IOException} from {@code transport.write}
     * is caught and turned into a failure, but an unchecked exception from the
     * same call used to have no catch at all: it escaped into the executor,
     * which discards it silently, leaving {@code inFlight} set with nothing to
     * clear it until the command's own deadline eventually fires. The command
     * still resolved, but only as a generic timeout — the real cause was lost.
     *
     * <p>A short {@code get()} window pins that this now fails fast: GET_DEBOUNCE
     * carries a 1000 ms deadline, so completing well inside that proves the
     * write failure itself resolved the future rather than the deadline
     * catching up behind it.
     *
     * <p>Must fail against the pre-fix engine: {@code future.get(500, MILLISECONDS)}
     * throws {@code TimeoutException} instead of {@code ExecutionException}, because
     * nothing completes the future until GET_DEBOUNCE's 1000 ms timeout lands.
     */
    @Test
    @Timeout(5)
    void uncheckedWriteFailureFailsFastInsteadOfWaitingForTheDeadline() throws Exception {
        connect();
        transport.failNextWrite(new RuntimeException("simulated: transport.write() blew up"));

        final CompletableFuture<Integer> future = board.getDebounce();

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(500, TimeUnit.MILLISECONDS);
            }
        });

        assertTrue(ex.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + ex.getCause());
        assertFalse(ex.getCause() instanceof MiniBoardTimeoutException,
                "an unchecked write failure must not surface as a generic timeout: got "
                        + ex.getCause());
    }

    // ------------------------------------------------------------ endianness

    /**
     * GET_WRITES is now little-endian: low byte first. Bytes 0x01,0x02,0x00,0x00
     * must decode to 513 (0x00000201). Reading the same bytes big-endian would
     * instead treat 0x01 as the most significant byte and produce a value in the
     * millions — the expected value here is hand-derived, not produced by
     * calling the code under test.
     */
    @Test
    @Timeout(5)
    void getWritesDecodesLittleEndian() throws Exception {
        connect();

        CompletableFuture<Integer> future = board.getWrites();
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.GET_WRITES.getValue(), 0x00,
                new byte[] {0x01, 0x02, 0x00, 0x00}));

        assertEquals(513, future.get(2, TimeUnit.SECONDS));
    }

    /**
     * SAVE's response grew from 0 bytes to 1: a `written` flag distinguishing a
     * real flash write from a skipped no-op. Both values must reach the caller
     * as the resolved boolean, not just decode without throwing.
     */
    @Test
    @Timeout(5)
    void saveResolvesWithTheWrittenFlag() throws Exception {
        connect();

        CompletableFuture<Boolean> wroteFuture = board.save();
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.SAVE.getValue(), 0x00, new byte[] {0x01}));
        assertTrue(wroteFuture.get(2, TimeUnit.SECONDS), "written=1 must resolve true");

        CompletableFuture<Boolean> skippedFuture = board.save();
        waitForWriteCount(2);
        transport.offer(FakeTransport.response(Opcode.SAVE.getValue(), 0x00, new byte[] {0x00}));
        assertFalse(skippedFuture.get(2, TimeUnit.SECONDS), "written=0 must resolve false");
    }

    /**
     * {@code LedConfig.durationMs} is little-endian, the same byte order GET_WRITES now uses.
     * Confirms both the request encoding (low byte first on the wire) and the
     * response decoding through the actual command path, not just LedConfig in
     * isolation.
     */
    @Test
    @Timeout(5)
    void setLedRequestAndResponseUseLittleEndianDuration() throws Exception {
        connect();

        LedConfig request = new LedConfig(2, LedMode.PULSE, LedFinal.RESTORE, 10, 20, 30, 4, 300);
        CompletableFuture<LedConfig> future = board.setLed(request);
        waitForWriteCount(1);

        byte[] writtenFrame = transport.getWritten().get(0);
        // Request layout: SOF, CMD, LEN, DATA..., CRC - data starts at index 3.
        // duration 300 = 0x012C: low byte 0x2C, high byte 0x01.
        assertEquals((byte) 0x2C, writtenFrame[3 + 7], "low duration byte must go out first");
        assertEquals((byte) 0x01, writtenFrame[3 + 8], "high duration byte must go out second");

        // Echo back a different duration, 0x0304 = 772, low byte first, to prove
        // the response is actually decoded rather than the request re-parsed.
        // Trailing cur_r/cur_g/cur_b/flags (50, 60, 70, 0) are deliberately distinct
        // from the target colour (10, 20, 30) above, so this fixture cannot pass a
        // decode bug that swapped the target and live colour byte ranges.
        byte[] echoed = new byte[] {
                2, (byte) LedMode.PULSE.getValue(), (byte) LedFinal.RESTORE.getValue(),
                10, 20, 30, 4, 0x04, 0x03,
                50, 60, 70, 0
        };
        transport.offer(FakeTransport.response(Opcode.SET_LED.getValue(), 0x00, echoed));

        LedConfig result = future.get(2, TimeUnit.SECONDS);
        assertEquals(772, result.getDurationMs());
    }

    // --------------------------------------------------------------- events

    @Test
    @Timeout(5)
    void unsolicitedKeyMsgAndSideEventsReachTheListener() throws Exception {
        connect();

        final CountDownLatch latch = new CountDownLatch(3);
        final List<String> events = new CopyOnWriteArrayList<String>();
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onKey(int keyIndex, int keyId) {
                events.add("KEY:" + keyIndex + ":" + keyId);
                latch.countDown();
            }

            @Override
            public void onMessage(String text) {
                events.add("MSG:" + text);
                latch.countDown();
            }

            @Override
            public void onSideChanged(Side side) {
                events.add("SIDE:" + side);
                latch.countDown();
            }
        });

        transport.offer(FakeTransport.event(0x10, new byte[] {5, 9}));    // KEY, index 5, key_id 9
        transport.offer(FakeTransport.event(0x13, asciiBytes("hi")));     // MSG
        transport.offer(FakeTransport.event(0x04, new byte[] {0x02}));    // SIDE = RIGHT

        assertTrue(latch.await(2, TimeUnit.SECONDS), "not all three events arrived: " + events);
        assertTrue(events.contains("KEY:5:9"), events.toString());
        assertTrue(events.contains("MSG:hi"), events.toString());
        assertTrue(events.contains("SIDE:RIGHT"), events.toString());
    }

    // ------------------------------------------------------------- raw hook

    @Test
    @Timeout(5)
    void rawFrameListenerCapturesBothTxAndRx() throws Exception {
        connect();

        final CountDownLatch latch = new CountDownLatch(2);
        final List<String> directions = new CopyOnWriteArrayList<String>();
        board.setRawFrameListener(new RawFrameListener() {
            @Override
            public void onTxFrame(Frame frame, long timestampNanos) {
                directions.add("TX:" + frame.getOpcode());
                latch.countDown();
            }

            @Override
            public void onRxFrame(Frame frame, long timestampNanos) {
                directions.add("RX:" + frame.getOpcode());
                latch.countDown();
            }

            @Override
            public void onFrameError(byte[] discarded, FrameError reason, long timestampNanos) {
                // not exercised here
            }
        });

        CompletableFuture<Integer> future = board.getDebounce();
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.GET_DEBOUNCE.getValue(), 0x00, new byte[] {1}));
        future.get(2, TimeUnit.SECONDS);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "raw hook did not see both directions: " + directions);
        assertTrue(directions.contains("TX:" + Opcode.GET_DEBOUNCE.getValue()), directions.toString());
        assertTrue(directions.contains("RX:" + Opcode.GET_DEBOUNCE.getValue()), directions.toString());
    }

    // ----------------------------------------------------------------- close

    @Test
    @Timeout(5)
    void closeFailsPendingFuturesWithMiniBoardException() throws Exception {
        connect();

        final CompletableFuture<Integer> future = board.getDebounce();
        waitForWriteCount(1);

        board.close();

        ExecutionException ex = assertThrows(ExecutionException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                future.get(2, TimeUnit.SECONDS);
            }
        });
        assertTrue(ex.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + ex.getCause());

        // MiniBoardTimeoutException extends MiniBoardException, and getDebounce carries a
        // 1000 ms deadline that would elapse inside the 2 s window above. Without these two
        // assertions an engine whose close() ignored pending commands would still go green:
        // the scheduled TimeoutTask would complete the future, and the instanceof check
        // above would happily accept it. Pin the failure to close() specifically.
        assertFalse(ex.getCause() instanceof MiniBoardTimeoutException,
                "close() must fail the command itself, not leave it to time out: "
                        + ex.getCause());
        assertEquals("Connection to " + PORT_NAME + " closed", ex.getCause().getMessage(),
                "the failure must be attributed to the close, not to a timeout");
    }

    // ---------------------------------------------------------------- reboot

    /**
     * REBOOT is special-cased: the firmware replies and then the port drops, and
     * that drop must be reported as a clean disconnect (null cause), not a
     * fault. This only holds if {@code reboot()} calls {@code expectDisconnect()}
     * before sending — if it did not, the simulated EOF below would surface as
     * a non-null "closed by the device" cause instead.
     */
    @Test
    @Timeout(5)
    void rebootCompletesNormallyAndReportsACleanDisconnect() throws Exception {
        connect();

        final CountDownLatch disconnectLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> cause = new AtomicReference<Throwable>();
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable c) {
                cause.set(c);
                disconnectLatch.countDown();
            }
        });

        CompletableFuture<Void> future = board.reboot(RebootMode.WATCHDOG);
        waitForWriteCount(1);
        transport.offer(FakeTransport.response(Opcode.REBOOT.getValue(), 0x00, new byte[0]));
        transport.offerEof(); // the port drops immediately after the reply, as real firmware does

        assertNull(future.get(2, TimeUnit.SECONDS));
        assertTrue(disconnectLatch.await(2, TimeUnit.SECONDS), "onDisconnected never fired");
        assertNull(cause.get(), "the post-REBOOT drop must be reported as a clean disconnect");
    }

    @Test
    @Timeout(5)
    void rebootRejectsANullMode() throws Exception {
        connect();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                board.reboot(null);
            }
        });
        assertTrue(transport.getWritten().isEmpty(), "a rejected reboot() must never reach the wire");
    }

    // ------------------------------------------------------------ opcode wiring

    /** One command: how to invoke it, and a response that lets its future resolve. */
    private static final class Case {
        final Opcode opcode;
        final Function<MiniBoard, CompletableFuture<?>> invoke;
        final byte[] response;

        Case(Opcode opcode, Function<MiniBoard, CompletableFuture<?>> invoke, byte[] response) {
            this.opcode = opcode;
            this.invoke = invoke;
            this.response = response;
        }
    }

    /**
     * One case per command declared in {@link Opcode}. Each response payload is
     * only shaped plausibly enough for its command's decode path to succeed
     * (valid LED mode/final, in-range key codes, etc.) - the payload contents
     * are not otherwise significant here.
     */
    private static List<Case> commandCases() {
        List<Case> cases = new ArrayList<Case>();
        cases.add(new Case(Opcode.GET_CONFIG_TYPE, b -> b.getConfigType(), new byte[] {0}));
        cases.add(new Case(Opcode.SET_CONFIG_TYPE, b -> b.setConfigType(1), new byte[] {1}));
        cases.add(new Case(Opcode.GET_DEBOUNCE, b -> b.getDebounce(), new byte[] {5}));
        cases.add(new Case(Opcode.SET_DEBOUNCE, b -> b.setDebounce(5), new byte[] {5}));
        cases.add(new Case(Opcode.GET_HID_ENABLE, b -> b.getHidEnable(), new byte[] {1}));
        cases.add(new Case(Opcode.SET_HID_ENABLE, b -> b.setHidEnable(true), new byte[] {1}));
        cases.add(new Case(Opcode.GET_TYPO_REJECT, b -> b.getTypoReject(), new byte[] {0}));
        cases.add(new Case(Opcode.SET_TYPO_REJECT, b -> b.setTypoReject(false), new byte[] {0}));
        cases.add(new Case(Opcode.GET_KEY, b -> b.getKey(0),
                new byte[] {0, 3, 4, 5, 6, 7, 8, 9}));
        cases.add(new Case(Opcode.SET_KEY, b -> b.setKey(new KeyMapping(0, 3, 4, 5, 6, 7, 8, 9)),
                new byte[] {0, 3, 4, 5, 6, 7, 8, 9}));
        // Trailing 4 bytes (cur_r/cur_g/cur_b/flags) are deliberately distinct from
        // the target colour (10, 20, 30) in bytes 3-5, so a decode bug that swapped
        // the target and live colour byte ranges would not slip past this fixture.
        cases.add(new Case(Opcode.GET_LED, b -> b.getLed(0),
                new byte[] {0, 0, 0, 10, 20, 30, 1, 0x2C, 0x01, 50, 60, 70, 0}));
        cases.add(new Case(Opcode.SET_LED,
                b -> b.setLed(new LedConfig(0, LedMode.SOLID, LedFinal.OFF, 10, 20, 30, 1, 300)),
                new byte[] {0, 0, 0, 10, 20, 30, 1, 0x2C, 0x01, 50, 60, 70, 0}));
        cases.add(new Case(Opcode.GET_LED_BRIGHTNESS, b -> b.getLedBrightness(),
                new byte[] {(byte) 128}));
        cases.add(new Case(Opcode.SET_LED_BRIGHTNESS, b -> b.setLedBrightness(128),
                new byte[] {(byte) 128}));
        cases.add(new Case(Opcode.SET_LED_OFF, b -> b.setLedOff(0), new byte[] {0}));
        cases.add(new Case(Opcode.GET_WRITES, b -> b.getWrites(), new byte[] {0x05, 0x00, 0x00, 0x00}));
        cases.add(new Case(Opcode.SET_HID_DISABLE_TEMP, b -> b.setHidDisableTemp(true),
                new byte[] {1}));
        cases.add(new Case(Opcode.SAVE, b -> b.save(), new byte[] {1}));
        cases.add(new Case(Opcode.RESET, b -> b.reset(), new byte[0]));
        // REBOOT's documented response is 1 byte (echoes mode); WATCHDOG's wire
        // value is 0.
        cases.add(new Case(Opcode.REBOOT, b -> b.reboot(RebootMode.WATCHDOG), new byte[] {0}));
        return cases;
    }

    /**
     * Defends every one of the twenty commands' opcode and request-length wiring:
     * submits each in turn and checks the CMD and LEN bytes actually written to
     * the wire against {@link Opcode}. Before this test, only four of the twenty
     * commands were exercised anywhere in the suite - a regression swapping, say,
     * {@code SET_HID_ENABLE}'s opcode for {@code SET_HID_DISABLE_TEMP}'s (both
     * take a 1-byte boolean payload, so {@code FrameEncoder} would not catch it)
     * would have been caught by nothing.
     *
     * <p>Also checks each case's fixture response size against
     * {@link Opcode#getResponseLength()}, which is otherwise dead metadata nothing
     * asserts.
     */
    @Test
    @Timeout(20)
    void everyCommandTransmitsItsDocumentedOpcodeAndRequestLength() throws Exception {
        connect();

        List<Case> cases = commandCases();
        int expectedWrites = 0;
        for (Case c : cases) {
            CompletableFuture<?> future = c.invoke.apply(board);
            expectedWrites++;
            waitForWriteCount(expectedWrites);

            byte[] frame = transport.getWritten().get(expectedWrites - 1);
            assertEquals((byte) c.opcode.getValue(), frame[1],
                    c.opcode + ": wrong CMD byte on the wire");
            assertEquals((byte) c.opcode.getRequestLength(), frame[2],
                    c.opcode + ": wrong LEN byte on the wire");
            assertEquals(c.opcode.getResponseLength(), c.response.length,
                    c.opcode + ": fixture size must match the documented response length");

            transport.offer(FakeTransport.response(c.opcode.getValue(), 0x00, c.response));
            future.get(2, TimeUnit.SECONDS); // drain before the next command
        }
        assertEquals(cases.size(), transport.getWritten().size());
    }

    // ------------------------------------------------------- client validation

    @Test
    @Timeout(5)
    void getKeyRejectsAnOutOfRangeIndexWithoutARoundTrip() throws Exception {
        connect();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                board.getKey(KeyMapping.KEY_COUNT);
            }
        });
        assertTrue(transport.getWritten().isEmpty(),
                "an out-of-range index must be rejected client-side, never sent");
    }
}
