package com.itsonlybinary.miniboard;

import com.itsonlybinary.miniboard.protocol.Opcode;
import com.itsonlybinary.miniboard.transport.SerialTransport;
import com.itsonlybinary.miniboard.transport.Transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * A connection to one MiniBoard54 over its USB CDC serial port.
 *
 * <p>Every device operation is asynchronous. The lifecycle methods are not:
 * {@link #connect()} opens the port on the calling thread — and on a reconnect may
 * first wait for the previous connection's reader to exit — while
 * {@link #disconnect()} and {@link #close()} block while the port closes and the
 * reader is joined.
 *
 * <pre>{@code
 * MiniBoard board = new MiniBoard("COM5");
 * board.setListener(new MiniBoardAdapter() {
 *     public void onKey(int index, int keyId) { System.out.println("key " + index); }
 * });
 * board.connect().thenCompose(info -> {
 *     System.out.println("connected to " + info);
 *     return board.getDebounce();
 * }).thenAccept(ms -> System.out.println("debounce " + ms + " ms"));
 * }</pre>
 *
 * <p>Instances are independent: connect to several boards at once.
 *
 * <p>There is no <em>automatic</em> reconnect — the library never retries on its
 * own. On an unexpected disconnect every pending future fails and
 * {@link MiniBoardListener#onDisconnected} fires, and the instance returns to an
 * idle state from which {@link #connect()} may simply be called again. Only
 * {@link #close()} ends an instance.
 *
 * <p>This class is the thin public surface: construction, lifecycle, and the
 * command entry point. The connection engine — transport lifecycle, the hello
 * handshake, the reader thread, the callback executor, and the command queue —
 * lives in the package-private {@code MiniBoardEngine}.
 */
public class MiniBoard implements AutoCloseable {

    /** How long to wait for the five hello events after asserting DTR. */
    public static final int HANDSHAKE_TIMEOUT_MS = 3000;

    /** Commands queued beyond this depth are rejected immediately, never accepted. */
    public static final int MAX_QUEUED_COMMANDS = 64;

    private final MiniBoardEngine engine;

    /**
     * @param portName e.g. "COM5", from {@link DiscoveredPort#getPortName()}
     */
    public MiniBoard(String portName) {
        this(portName, new SerialTransport());
    }

    /** Injectable-transport constructor, for driving a fake device. */
    public MiniBoard(String portName, Transport transport) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("portName is required");
        }
        if (transport == null) {
            throw new IllegalArgumentException("transport is required");
        }
        this.engine = new MiniBoardEngine(portName.trim(), transport);
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Opens the port and waits for the device's hello sequence.
     *
     * <p>Callable again after any disconnect — this instance is reusable. It is not
     * callable after {@link #close()}, nor while a connection is already in progress
     * or established. After an <em>unexpected</em> disconnect, wait for
     * {@link MiniBoardListener#onDisconnected} before reconnecting: by the time it
     * fires the port is closed and the previous reader has exited.
     *
     * <p>The handshake is asynchronous, but this call itself does some work on the
     * calling thread before returning: it opens the port, and on a reconnect it may
     * first wait up to {@code READER_HANDOFF_TIMEOUT_MS} for the previous
     * connection's reader thread to exit. If that reader will not exit, the returned
     * future fails rather than reopening the port underneath it — reopening would let
     * the old reader consume this connection's bytes. Call from a thread that can
     * tolerate that pause.
     *
     * @return a future completing with the device identity, or failing with
     *         {@link MiniBoardException} if the handshake times out or the device
     *         reports a name other than "MiniBoard54"
     */
    public CompletableFuture<DeviceInfo> connect() {
        return engine.connect(null);
    }

    /**
     * Retargets this instance at a different port, then connects. Use after the
     * board re-enumerates on a new COM number.
     *
     * @param portName e.g. "COM7", from {@link DiscoveredPort#getPortName()}
     * @throws IllegalArgumentException if null or blank
     */
    public CompletableFuture<DeviceInfo> connect(String portName) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("portName is required");
        }
        return engine.connect(portName.trim());
    }

    /**
     * Drops the connection and fails any outstanding commands, leaving the instance
     * reconnectable via {@link #connect()}. Listeners are retained.
     *
     * <p>Blocks while the port closes and the reader thread exits — briefly in the
     * ordinary case, and also when another teardown is already in progress, in which
     * case it waits for that one rather than returning early. {@link #connect()} is
     * therefore safe to call the moment this returns. Idempotent, and a harmless
     * no-op once {@link #close()} has run.
     */
    public void disconnect() {
        engine.disconnect();
    }

    /**
     * Disconnects and releases this instance's two daemon threads. Idempotent, and
     * terminal: a closed instance can never reconnect.
     *
     * <p>Because a failed {@code connect()} now leaves the instance reusable, it no
     * longer disposes of it for you. Code that is finished with a board must close
     * it — try-with-resources is the easy way.
     */
    @Override
    public void close() {
        engine.close();
    }

    /** True once {@link #close()} has run. A closed instance can never reconnect. */
    public boolean isClosed() {
        return engine.isClosed();
    }

    public boolean isConnected() {
        return engine.isConnected();
    }

    /**
     * @return the identity from the most recent successful handshake, or null if
     *         there has never been one. Retained across a disconnect, so it may be
     *         stale when {@link #isConnected()} is false — which is what lets a UI
     *         still render "MiniBoard54 v1.0 — disconnected".
     */
    public DeviceInfo getDeviceInfo() {
        return engine.getDeviceInfo();
    }

    /** @return the port currently targeted, which {@link #connect(String)} may change. */
    public String getPortName() {
        return engine.getPortName();
    }

    /** Sets the event listener; null clears it. */
    public void setListener(MiniBoardListener listener) {
        engine.setListener(listener);
    }

    /** Sets the raw frame tap for debug logging; null clears it. */
    public void setRawFrameListener(RawFrameListener rawListener) {
        engine.setRawFrameListener(rawListener);
    }

    /**
     * Suppresses the disconnect error for the port loss that follows a REBOOT.
     * Called by {@code reboot()}; not useful otherwise.
     */
    void expectDisconnect() {
        engine.expectDisconnect();
    }

    // ----------------------------------------------------------- command engine

    /**
     * Submits a command. The single entry point for every command method.
     *
     * <p>A command is only queued once a connection is in progress or established.
     * Submitted while idle — before the first {@link #connect()}, or after any
     * disconnect — or after {@link #close()}, it is rejected outright: the returned
     * future fails with a {@link MiniBoardException} rather than waiting for a
     * connection that may never be asked for. Submitted during the handshake it is
     * queued, and its timeout clock starts when the handshake completes.
     *
     * @return a future completing with the response payload, or failing with
     *         {@link MiniBoardStatusException}, {@link MiniBoardTimeoutException},
     *         or {@link MiniBoardException} on disconnect
     */
    protected CompletableFuture<byte[]> send(Opcode opcode, byte[] data) {
        return engine.submit(opcode, data);
    }

    /** Bridges a future to the callback-style overloads. */
    protected static <T> void adapt(CompletableFuture<T> future,
                                    final MiniBoardCallback<T> callback) {
        if (callback == null) {
            return;
        }
        future.whenComplete(new BiConsumer<T, Throwable>() {
            @Override
            public void accept(T result, Throwable error) {
                if (error != null) {
                    Throwable unwrapped =
                            (error instanceof CompletionException && error.getCause() != null)
                                    ? error.getCause() : error;
                    callback.onError(unwrapped);
                } else {
                    callback.onSuccess(result);
                }
            }
        });
    }

    // ------------------------------------------------------- payload decoding

    /** Extracts a single unsigned byte from a response payload. */
    private static int oneByte(Opcode opcode, byte[] data) {
        if (data == null || data.length < 1) {
            throw new MiniBoardException(opcode
                    + " returned an empty payload; expected 1 byte");
        }
        return data[0] & 0xFF;
    }

    /** Validates that a value fits in one byte before it goes on the wire. */
    private static void requireByte(String name, int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException(name + " must be 0-255, got " + value);
        }
    }

    private static byte[] payload(int value) {
        return new byte[] { (byte) value };
    }

    private static byte[] payload(boolean value) {
        return new byte[] { (byte) (value ? 1 : 0) };
    }

    // ------------------------------------------------------------- commands

    /** GET_CONFIG_TYPE (0x80) — the board's config type identifier. */
    public CompletableFuture<Integer> getConfigType() {
        return send(Opcode.GET_CONFIG_TYPE, null)
                .thenApply(d -> oneByte(Opcode.GET_CONFIG_TYPE, d));
    }

    /** @see #getConfigType() */
    public void getConfigType(MiniBoardCallback<Integer> callback) {
        adapt(getConfigType(), callback);
    }

    /**
     * SET_CONFIG_TYPE (0x81) — overwrite the in-memory config type.
     * Not persisted until {@link #save()}.
     *
     * @return the value the device applied
     */
    public CompletableFuture<Integer> setConfigType(int configType) {
        requireByte("configType", configType);
        return send(Opcode.SET_CONFIG_TYPE, payload(configType))
                .thenApply(d -> oneByte(Opcode.SET_CONFIG_TYPE, d));
    }

    /** @see #setConfigType(int) */
    public void setConfigType(int configType, MiniBoardCallback<Integer> callback) {
        adapt(setConfigType(configType), callback);
    }

    /** GET_DEBOUNCE (0x82) — key debounce delay in milliseconds. */
    public CompletableFuture<Integer> getDebounce() {
        return send(Opcode.GET_DEBOUNCE, null)
                .thenApply(d -> oneByte(Opcode.GET_DEBOUNCE, d));
    }

    /** @see #getDebounce() */
    public void getDebounce(MiniBoardCallback<Integer> callback) {
        adapt(getDebounce(), callback);
    }

    /**
     * SET_DEBOUNCE (0x83) — key debounce delay in milliseconds. Takes effect
     * immediately; not persisted until {@link #save()}.
     */
    public CompletableFuture<Integer> setDebounce(int milliseconds) {
        requireByte("milliseconds", milliseconds);
        return send(Opcode.SET_DEBOUNCE, payload(milliseconds))
                .thenApply(d -> oneByte(Opcode.SET_DEBOUNCE, d));
    }

    /** @see #setDebounce(int) */
    public void setDebounce(int milliseconds, MiniBoardCallback<Integer> callback) {
        adapt(setDebounce(milliseconds), callback);
    }

    /**
     * GET_HID_ENABLE (0x84) — the <em>stored</em> HID output flag. The effective
     * runtime state may additionally be suppressed by
     * {@link #setHidDisableTemp(boolean)}, which this does not reflect.
     */
    public CompletableFuture<Boolean> getHidEnable() {
        return send(Opcode.GET_HID_ENABLE, null)
                .thenApply(d -> oneByte(Opcode.GET_HID_ENABLE, d) != 0);
    }

    /** @see #getHidEnable() */
    public void getHidEnable(MiniBoardCallback<Boolean> callback) {
        adapt(getHidEnable(), callback);
    }

    /**
     * SET_HID_ENABLE (0x85) — the persistent HID output flag.
     * Not written to flash until {@link #save()}.
     */
    public CompletableFuture<Boolean> setHidEnable(boolean enabled) {
        return send(Opcode.SET_HID_ENABLE, payload(enabled))
                .thenApply(d -> oneByte(Opcode.SET_HID_ENABLE, d) != 0);
    }

    /** @see #setHidEnable(boolean) */
    public void setHidEnable(boolean enabled, MiniBoardCallback<Boolean> callback) {
        adapt(setHidEnable(enabled), callback);
    }

    /** GET_TYPO_REJECT (0x86) — typo rejection flag. */
    public CompletableFuture<Boolean> getTypoReject() {
        return send(Opcode.GET_TYPO_REJECT, null)
                .thenApply(d -> oneByte(Opcode.GET_TYPO_REJECT, d) != 0);
    }

    /** @see #getTypoReject() */
    public void getTypoReject(MiniBoardCallback<Boolean> callback) {
        adapt(getTypoReject(), callback);
    }

    /** SET_TYPO_REJECT (0x87) — not persisted until {@link #save()}. */
    public CompletableFuture<Boolean> setTypoReject(boolean enabled) {
        return send(Opcode.SET_TYPO_REJECT, payload(enabled))
                .thenApply(d -> oneByte(Opcode.SET_TYPO_REJECT, d) != 0);
    }

    /** @see #setTypoReject(boolean) */
    public void setTypoReject(boolean enabled, MiniBoardCallback<Boolean> callback) {
        adapt(setTypoReject(enabled), callback);
    }

    /**
     * GET_KEY (0x88) — the key_id and HID key codes assigned to one key slot.
     *
     * @param index 0 to 53
     */
    public CompletableFuture<KeyMapping> getKey(int index) {
        if (index < 0 || index >= KeyMapping.KEY_COUNT) {
            throw new IllegalArgumentException("Key index must be 0-"
                    + (KeyMapping.KEY_COUNT - 1) + ", got " + index);
        }
        return send(Opcode.GET_KEY, payload(index)).thenApply(KeyMapping::fromResponse);
    }

    /** @see #getKey(int) */
    public void getKey(int index, MiniBoardCallback<KeyMapping> callback) {
        adapt(getKey(index), callback);
    }

    /**
     * SET_KEY (0x89) — write a key_id and HID key codes to a key slot.
     * Not persisted until {@link #save()}.
     *
     * @return the slot as the device stored it
     */
    public CompletableFuture<KeyMapping> setKey(KeyMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping is required");
        }
        return send(Opcode.SET_KEY, mapping.toRequestData())
                .thenApply(KeyMapping::fromResponse);
    }

    /** @see #setKey(KeyMapping) */
    public void setKey(KeyMapping mapping, MiniBoardCallback<KeyMapping> callback) {
        adapt(setKey(mapping), callback);
    }

    /**
     * GET_LED (0x8A) — the animation configuration for one LED.
     *
     * <p>A mode or final state newer firmware introduced degrades to a null
     * {@link LedConfig#getMode()} / {@link LedConfig#getFinalState()} rather than
     * failing the future; the wire byte survives in {@link LedConfig#getRawMode()}.
     *
     * @param index 0 to 3
     */
    public CompletableFuture<LedConfig> getLed(int index) {
        if (index < 0 || index >= LedConfig.LED_COUNT) {
            throw new IllegalArgumentException("LED index must be 0-"
                    + (LedConfig.LED_COUNT - 1) + ", got " + index);
        }
        return send(Opcode.GET_LED, payload(index)).thenApply(LedConfig::fromResponse);
    }

    /** @see #getLed(int) */
    public void getLed(int index, MiniBoardCallback<LedConfig> callback) {
        adapt(getLed(index), callback);
    }

    /**
     * SET_LED (0x8B) — configure and start an LED animation. Immediate; never
     * persisted to flash.
     *
     * <p>A completed animation reports through
     * {@link MiniBoardListener#onLedFinish(int)}, except for BLINK and PULSE with
     * {@code iterations = 0}, which loop forever and never report.
     */
    public CompletableFuture<LedConfig> setLed(LedConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        return send(Opcode.SET_LED, config.toRequestData())
                .thenApply(LedConfig::fromResponse);
    }

    /** @see #setLed(LedConfig) */
    public void setLed(LedConfig config, MiniBoardCallback<LedConfig> callback) {
        adapt(setLed(config), callback);
    }

    /** GET_LED_BRIGHTNESS (0x8C) — global brightness multiplier, 0 to 255. */
    public CompletableFuture<Integer> getLedBrightness() {
        return send(Opcode.GET_LED_BRIGHTNESS, null)
                .thenApply(d -> oneByte(Opcode.GET_LED_BRIGHTNESS, d));
    }

    /** @see #getLedBrightness() */
    public void getLedBrightness(MiniBoardCallback<Integer> callback) {
        adapt(getLedBrightness(), callback);
    }

    /**
     * SET_LED_BRIGHTNESS (0x8D) — global brightness multiplier.
     * Not persisted until {@link #save()}.
     *
     * @param brightness 0 (off) to 255 (full)
     */
    public CompletableFuture<Integer> setLedBrightness(int brightness) {
        requireByte("brightness", brightness);
        return send(Opcode.SET_LED_BRIGHTNESS, payload(brightness))
                .thenApply(d -> oneByte(Opcode.SET_LED_BRIGHTNESS, d));
    }

    /** @see #setLedBrightness(int) */
    public void setLedBrightness(int brightness, MiniBoardCallback<Integer> callback) {
        adapt(setLedBrightness(brightness), callback);
    }

    /**
     * SET_LED_OFF (0x8E) — extinguish one LED and cancel its animation.
     *
     * @param index 0 to 3
     * @return the index the device echoed
     */
    public CompletableFuture<Integer> setLedOff(int index) {
        if (index < 0 || index >= LedConfig.LED_COUNT) {
            throw new IllegalArgumentException("LED index must be 0-"
                    + (LedConfig.LED_COUNT - 1) + ", got " + index);
        }
        return send(Opcode.SET_LED_OFF, payload(index))
                .thenApply(d -> oneByte(Opcode.SET_LED_OFF, d));
    }

    /** @see #setLedOff(int) */
    public void setLedOff(int index, MiniBoardCallback<Integer> callback) {
        adapt(setLedOff(index), callback);
    }

    /**
     * GET_WRITES (0x8F) — how many times the config has been committed to flash.
     * Useful for estimating flash wear.
     *
     * <p>The counter lives in its own flash sector, separate from the firmware
     * image, so it survives UF2 flashing and factory resets. The payload is
     * <strong>little-endian</strong>, the same byte order as the LED duration
     * field.
     *
     * <p>The 32-bit wire value is decoded into a signed Java {@code int}, so a
     * count at or above 2^31 (~2.1 billion) would read as negative. That ceiling
     * is practically unreachable given realistic flash write-endurance figures
     * (tens to hundreds of thousands of cycles), but is worth stating explicitly:
     * the old 16-bit decode could never have overflowed, and this migration is
     * what introduces the theoretical ceiling.
     */
    public CompletableFuture<Integer> getWrites() {
        return send(Opcode.GET_WRITES, null).thenApply(d -> {
            if (d == null || d.length < 4) {
                throw new MiniBoardException(
                        "GET_WRITES returned " + (d == null ? "null" : d.length)
                                + " bytes; expected 4");
            }
            return (d[0] & 0xFF) | ((d[1] & 0xFF) << 8)
                    | ((d[2] & 0xFF) << 16) | ((d[3] & 0xFF) << 24); // lo byte first
        });
    }

    /** @see #getWrites() */
    public void getWrites(MiniBoardCallback<Integer> callback) {
        adapt(getWrites(), callback);
    }

    /**
     * SET_HID_DISABLE_TEMP (0x90) — session-scoped HID suppression that overrides
     * the stored flag for the life of this serial connection.
     *
     * <p>Never written to flash, and cleared automatically when the port closes or
     * the device reboots. HID output is suppressed if either this or the stored
     * flag disables it. Does not change what {@link #getHidEnable()} reports.
     *
     * @param disabled true to suppress HID for this session
     */
    public CompletableFuture<Boolean> setHidDisableTemp(boolean disabled) {
        return send(Opcode.SET_HID_DISABLE_TEMP, payload(disabled))
                .thenApply(d -> oneByte(Opcode.SET_HID_DISABLE_TEMP, d) != 0);
    }

    /** @see #setHidDisableTemp(boolean) */
    public void setHidDisableTemp(boolean disabled, MiniBoardCallback<Boolean> callback) {
        adapt(setHidDisableTemp(disabled), callback);
    }

    /**
     * SAVE (0x91) — persist the in-memory configuration to flash.
     *
     * <p>Flash endurance is finite; watch {@link #getWrites()} and never call this
     * in a loop. Uses a 5 second timeout.
     *
     * @return true if the configuration was actually written to flash; false if
     *         the write was skipped because nothing had changed since the last save
     */
    public CompletableFuture<Boolean> save() {
        return send(Opcode.SAVE, null).thenApply(d -> oneByte(Opcode.SAVE, d) != 0);
    }

    /** @see #save() */
    public void save(MiniBoardCallback<Boolean> callback) {
        adapt(save(), callback);
    }

    /**
     * RESET (0x92) — erase the stored configuration and restore factory defaults.
     *
     * <p>Unlike the SET commands, this takes effect immediately and needs no
     * {@link #save()}. Uses a 5 second timeout.
     */
    public CompletableFuture<Void> reset() {
        return send(Opcode.RESET, null).thenApply(d -> null);
    }

    /** @see #reset() */
    public void reset(MiniBoardCallback<Void> callback) {
        adapt(reset(), callback);
    }

    /**
     * REBOOT (0x93) — reboot the device.
     *
     * <p>The firmware replies and then resets, so the port drops immediately after.
     * That drop is expected: the returned future completes normally, the connection
     * ends, and {@link MiniBoardListener#onDisconnected} fires with a null cause.
     * The instance survives it. Once the device re-enumerates, call {@link #connect()}
     * on this same instance — or {@link #connect(String)} if Windows moved it to a
     * different COM number.
     *
     * <p>{@link RebootMode#USB_BOOT} is the exception: the board returns as a UF2
     * mass-storage device with no serial port, so there is nothing to reconnect to.
     *
     * @param mode {@link RebootMode#WATCHDOG} for a normal boot, or
     *             {@link RebootMode#USB_BOOT} for the UF2 bootloader
     */
    public CompletableFuture<Void> reboot(RebootMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        // Mark before sending: the port can drop the instant the reply is flushed.
        expectDisconnect();
        return send(Opcode.REBOOT, payload(mode.getValue())).thenApply(d -> null);
    }

    /** @see #reboot(RebootMode) */
    public void reboot(RebootMode mode, MiniBoardCallback<Void> callback) {
        adapt(reboot(mode), callback);
    }
}
