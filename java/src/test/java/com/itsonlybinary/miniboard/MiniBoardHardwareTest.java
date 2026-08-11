package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Frame;
import com.itsonlybinary.miniboard.protocol.FrameError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks against a physically attached MiniBoard54.
 *
 * <p><strong>Requires hardware.</strong> Skipped unless explicitly enabled:
 *
 * <pre>gradlew test -Phardware=true</pre>
 *
 * <p>A MiniBoard54 must be connected by USB. Which COM port it lands on does not
 * matter — the port is located through {@link MiniBoardDiscovery}.
 *
 * <p><strong>Strictly read-only.</strong> This class issues no {@code SET_*},
 * {@code SAVE}, {@code RESET} or {@code REBOOT} command, so it can never alter the
 * user's key map or consume one of the board's finite flash writes.
 *
 * <p>Assertions are on invariants that <em>any</em> MiniBoard satisfies — never on
 * a specific COM port, serial number, or firmware version — so the suite passes on
 * any desk rather than only the one it was written on.
 */
@EnabledIfSystemProperty(named = "miniboard.hardware", matches = "true")
class MiniBoardHardwareTest {

    private MiniBoard board;

    /** Every frame the decoder rejected, across the whole session. */
    private final List<String> frameErrors =
            Collections.synchronizedList(new ArrayList<String>());

    /** Counts every frame actually written to the wire, via the raw hook. */
    private final AtomicInteger txFrameCount = new AtomicInteger();

    private final CountDownLatch disconnected = new CountDownLatch(1);

    @BeforeEach
    void connectToAttachedBoard() throws Exception {
        DiscoveredPort port = MiniBoardDiscovery.findBest();
        Assumptions.assumeTrue(port != null && port.isLikelyMiniBoard(),
                "no likely MiniBoard attached; skipping hardware test");

        board = new MiniBoard(port.getPortName());
        board.setListener(new MiniBoardAdapter() {
            @Override
            public void onDisconnected(Throwable cause) {
                disconnected.countDown();
            }
        });
        board.setRawFrameListener(new RawFrameListener() {
            @Override
            public void onTxFrame(Frame frame, long timestampNanos) {
                txFrameCount.incrementAndGet();
            }

            @Override
            public void onRxFrame(Frame frame, long timestampNanos) {
            }

            @Override
            public void onFrameError(byte[] discarded, FrameError reason, long timestampNanos) {
                frameErrors.add(reason + ":" + discarded.length + " bytes");
            }
        });
    }

    @AfterEach
    void disconnect() {
        if (board != null) {
            board.close();
        }
    }

    @Test
    @Timeout(30)
    void handshakeIdentifiesTheDeviceAsAMiniBoard54() throws Exception {
        // MiniBoardEngine.checkHandshakeComplete fails this future itself if the
        // reported name is not DeviceInfo.EXPECTED_NAME, so a successful get()
        // below already proves the name; asserting it again would be dead - it
        // cannot fail. The same holds for getVersionString() (concatenates two
        // ints, never null) and getSide() (a null side leaves Builder.isComplete()
        // false, which times out the handshake instead of completing it).
        DeviceInfo info = board.connect().get(10, TimeUnit.SECONDS);

        assertNotNull(info, "connect() must yield device identity");
        assertTrue(info.getSerial().length > 0, "the board unique ID must not be empty");
        assertTrue(info.getSerial().length <= 16, "the board unique ID is at most 16 bytes");
        assertTrue(board.isConnected(), "the board should be connected after the handshake");
    }

    /**
     * Every check here must be one a live device could actually fail. Plain
     * byte/uint16 range checks on values decoded by {@code oneByte()} or a
     * fixed-width little-endian read are true by construction regardless of what
     * the firmware sends, and array-length / non-null checks on types whose
     * constructors and decoders already throw on any other shape are
     * unreachable rather than passing - so none of those appear below.
     */
    @Test
    @Timeout(30)
    void everyReadOnlyCommandReturnsAValueInItsDocumentedRange() throws Exception {
        // Cross-checks GET_CONFIG_TYPE against the TYPE hello event: the two
        // arrive by completely independent paths (an unsolicited event during
        // the handshake vs. a command round trip), so their agreement is real
        // evidence the decode is wired correctly - unlike a bare range check.
        DeviceInfo info = board.connect().get(10, TimeUnit.SECONDS);
        int configType = board.getConfigType().get(5, TimeUnit.SECONDS);
        assertEquals(info.getConfigType(), configType,
                "GET_CONFIG_TYPE must agree with the TYPE event seen during the handshake");

        // A 0 ms debounce would defeat the feature entirely; not a plausible
        // shipping value.
        int debounce = board.getDebounce().get(5, TimeUnit.SECONDS);
        assertTrue(debounce > 0, "0 ms debounce is not a plausible shipping value, got " + debounce);

        // The device must echo back the requested slot, not some other one.
        KeyMapping key = board.getKey(0).get(5, TimeUnit.SECONDS);
        assertEquals(0, key.getIndex(), "the device must echo the requested key index");

        LedConfig led = board.getLed(0).get(5, TimeUnit.SECONDS);
        assertEquals(0, led.getIndex(), "the device must echo the requested LED index");
    }

    /**
     * The single most valuable assertion in the project: it is the only check that
     * proves the CRC implementation and the frame decoder agree with real firmware,
     * rather than merely with this repository's own hand-computed vectors.
     */
    @Test
    @Timeout(30)
    void noFrameIsRejectedAcrossAWholeSession() throws Exception {
        board.connect().get(10, TimeUnit.SECONDS);

        // Exercise every response payload size the protocol defines:
        // 0 bytes (none read-only), 1 byte, 4 bytes, 8 bytes, 13 bytes.
        board.getDebounce().get(5, TimeUnit.SECONDS);        // LEN 1
        board.getWrites().get(5, TimeUnit.SECONDS);          // LEN 4, little-endian
        board.getKey(0).get(5, TimeUnit.SECONDS);            // LEN 8
        board.getLed(0).get(5, TimeUnit.SECONDS);            // LEN 13, little-endian duration field
        board.getHidEnable().get(5, TimeUnit.SECONDS);
        board.getTypoReject().get(5, TimeUnit.SECONDS);
        board.getLedBrightness().get(5, TimeUnit.SECONDS);
        board.getConfigType().get(5, TimeUnit.SECONDS);

        // Give any trailing frame a moment to arrive and be decoded.
        Thread.sleep(250);

        assertTrue(frameErrors.isEmpty(),
                "the decoder rejected frames from real firmware: " + frameErrors);
    }

    @Test
    @Timeout(30)
    void everyKeySlotAndEveryLedIsReadable() throws Exception {
        board.connect().get(10, TimeUnit.SECONDS);

        for (int i = 0; i < KeyMapping.KEY_COUNT; i++) {
            KeyMapping key = board.getKey(i).get(5, TimeUnit.SECONDS);
            assertEquals(i, key.getIndex(), "key slot " + i + " echoed the wrong index");
        }
        for (int i = 0; i < LedConfig.LED_COUNT; i++) {
            LedConfig led = board.getLed(i).get(5, TimeUnit.SECONDS);
            assertEquals(i, led.getIndex(), "LED " + i + " echoed the wrong index");
        }
        assertTrue(frameErrors.isEmpty(),
                "the decoder rejected frames while sweeping every slot: " + frameErrors);
    }

    @Test
    @Timeout(30)
    void outOfRangeIndicesAreRejectedWithoutTouchingTheDevice() throws Exception {
        board.connect().get(10, TimeUnit.SECONDS);

        // The hardware suite has no FakeTransport to inspect, so "without a round
        // trip" is verified by counting frames actually written to the wire via
        // the raw listener already installed in @BeforeEach.
        int txBefore = txFrameCount.get();

        assertThrows(IllegalArgumentException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        board.getKey(KeyMapping.KEY_COUNT);
                    }
                },
                "key index 54 must be rejected client-side, without a round trip");

        assertThrows(IllegalArgumentException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        board.getLed(LedConfig.LED_COUNT);
                    }
                },
                "LED index 4 must be rejected client-side, without a round trip");

        assertEquals(txBefore, txFrameCount.get(),
                "a client-side-rejected call must never reach the wire");

        // The connection must be entirely unaffected by the rejected calls.
        assertTrue(board.isConnected(), "client-side validation must not disturb the link");
        assertNotNull(board.getDebounce().get(5, TimeUnit.SECONDS));
    }

    /**
     * Reconnecting the same instance against real firmware. The fake transport
     * cannot show that a real port survives being closed and reopened in quick
     * succession, nor that the board re-emits its hello sequence on each DTR
     * assertion — this is the only check that does.
     *
     * <p>Read-only: issues no {@code SET_*}, {@code SAVE}, {@code RESET} or
     * {@code REBOOT}. {@code getDebounce()} is a read.
     */
    @Test
    @Timeout(60)
    void reconnectsToTheSameBoardAcrossThreeCycles() throws Exception {
        DeviceInfo first = board.connect().get(10, TimeUnit.SECONDS);
        String serial = first.getSerialHex();

        for (int cycle = 2; cycle <= 3; cycle++) {
            board.disconnect();
            assertFalse(board.isConnected(), "cycle " + cycle + ": disconnect() did not drop");
            assertFalse(board.isClosed(), "cycle " + cycle + ": disconnect() must not close");

            DeviceInfo again = board.connect().get(10, TimeUnit.SECONDS);
            assertEquals(serial, again.getSerialHex(),
                    "cycle " + cycle + ": the same physical board must answer");
            assertTrue(board.isConnected(), "cycle " + cycle + ": not connected after reconnect");

            // The link must actually work, not merely report itself connected.
            assertTrue(board.getDebounce().get(5, TimeUnit.SECONDS) > 0,
                    "cycle " + cycle + ": the reconnected link returned no usable debounce");
        }

        assertTrue(frameErrors.isEmpty(),
                "the decoder rejected frames across reconnect cycles: " + frameErrors);
    }

    @Test
    @Timeout(30)
    void closeDisconnectsCleanlyAndFailsLaterCommands() throws Exception {
        board.connect().get(10, TimeUnit.SECONDS);
        board.getDebounce().get(5, TimeUnit.SECONDS);

        board.close();

        assertTrue(disconnected.await(5, TimeUnit.SECONDS),
                "onDisconnected must fire after close()");
        assertFalse(board.isConnected(), "the board must not report itself connected");

        final CompletableFuture<Integer> afterClose = board.getDebounce();
        ExecutionException ex = assertThrows(ExecutionException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() throws Throwable {
                        afterClose.get(5, TimeUnit.SECONDS);
                    }
                },
                "a command submitted after close() must fail, not hang or succeed");
        assertTrue(ex.getCause() instanceof MiniBoardException,
                "expected MiniBoardException, got " + ex.getCause());
    }
}
