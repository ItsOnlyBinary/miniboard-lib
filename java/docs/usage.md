# miniboard-lib — Usage Guide

Host-side Java for talking to a MiniBoard54 over its USB CDC virtual serial port.

Every device operation is asynchronous and returns a `CompletableFuture`; every one
also has a callback overload. The lifecycle calls are the exception, and they do work
on the calling thread: `connect()` opens the port inline (and on a reconnect may first
wait for the previous connection's reader thread to exit), and `disconnect()` and
`close()` block while the port closes and the reader is joined.

This library targets the CDC protocol documented in
[`mb_cdc_interface.md`](mb_cdc_interface.md). There is no runtime version gate —
connecting to firmware that speaks a different protocol shape simply fails a command
with a `MiniBoardException` citing an unexpected payload length, most visibly on
`getKey()`/`setKey()`/`getLed()`/`setLed()`/`getWrites()`/`save()`.

**Contents**

1. [Requirements and installation](#requirements-and-installation)
2. [Finding a board](#finding-a-board)
3. [Connecting](#connecting)
4. [The connection lifecycle](#the-connection-lifecycle)
5. [Commands](#commands)
6. [Events](#events)
7. [Errors](#errors)
8. [Threads](#threads)
9. [Raw protocol log](#raw-protocol-log)
10. [Running the tests](#running-the-tests)

---

## Requirements and installation

- Java 8 or later. The library itself is compiled to Java 8 bytecode.
- Windows 7 or later for COM port *ranking*. Elsewhere the library still works, but
  every port is reported as `UNKNOWN` — see [Finding a board](#finding-a-board).

Building (see the [README](../README.md) for `build.ps1`) stages four jars:

```
build\dist\miniboard-lib-1.0.0.jar   the library
build\dist\libs\jna-3.5.1.jar
build\dist\libs\platform-3.5.1.jar
build\dist\libs\purejavacomm-0.0.29.jar
```

Put all four on your classpath. JNA and `platform` are needed only for Windows PnP
lookups during discovery; purejavacomm is the serial transport.

---

## Finding a board

Discovery lists **every** COM port, ranked most-likely-first. Nothing is filtered out,
and **no port is opened** — opening a port asserts DTR, which resets Arduino-class
boards and disturbs unrelated hardware.

```java
for (DiscoveredPort port : MiniBoardDiscovery.discover()) {
    System.out.println(port);
    // COM6 [CONFIRMED] MiniBoard CDC (USB\VID_2E8A&PID_104E&MI_00\6&295c3188&0&0000)
}

DiscoveredPort best = MiniBoardDiscovery.findBest();      // highest-ranked, or null
List<DiscoveredPort> likely = MiniBoardDiscovery.findLikely();  // CONFIRMED + PROBABLE only
```

| Tier | Meaning |
|---|---|
| `CONFIRMED` | USB vendor ID `0x2E8A`, product ID `0x104E`, **and** "MiniBoard" in the device's reported description |
| `PROBABLE` | Vendor ID `0x2E8A` and product ID `0x104E` only |
| `UNKNOWN` | No matching USB metadata |

`DiscoveredPort.isLikelyMiniBoard()` is true for `CONFIRMED` and `PROBABLE`.

`discover()` is synchronous — a few milliseconds of registry and PnP reads, no port
I/O. Use `discoverAsync()` to keep it off a UI thread.

### How identification actually works

The obvious approach — matching the USB product string in the registry — does not
work. On Windows 8.1+ the inbox `usbser.sys` driver overwrites `DeviceDesc`, `Mfg`
and `FriendlyName` with its own INF text, so a MiniBoard reports as
`USB Serial Device (COM6)` and the string `MiniBoard` appears **nowhere** in the
plain registry values.

The product string does survive, as the device property
`DEVPKEY_Device_BusReportedDeviceDesc`, which the library reads through the PnP
configuration manager. On a MiniBoard54 that yields:

| Device node | Reported description |
|---|---|
| the CDC interface (your COM port) | `MiniBoard CDC` |
| the HID interface | `MiniBoard Keyboard` |
| the composite parent | `MiniBoard54` |

That is what makes `CONFIRMED` reachable. The raw registry path for this property is
ACL-protected and denies access without elevation, so the PnP API is used instead —
the library never requires admin rights.

If the property is unavailable — an older Windows, a locked-down machine — discovery
degrades gracefully to VID-only matching and the board ranks `PROBABLE`. Nothing
breaks.

`PROBABLE` is not a failure: `0x2E8A` is Raspberry Pi Trading's registered vendor ID and
is shared across many RP2040-class boards, which is exactly why a VID+PID match alone is
not `CONFIRMED`.

Registry entries persist for every device ever attached, so results are intersected
with the live port list — an unplugged board never appears as a phantom COM port.

**Non-Windows:** every port is returned as `UNKNOWN`. The library still works; it
just cannot rank.

---

## Connecting

`connect()` completes once the device has sent all six hello events, the last of
which reports the result of the firmware's boot-time self-test.

```java
MiniBoard board = new MiniBoard("COM6");

board.connect().thenAccept(info -> {
    System.out.println(info.getName());          // MiniBoard54
    System.out.println(info.getVersionString()); // 1.0
    System.out.println(info.getSerialHex());     // DE6578774F4E7137
    System.out.println(info.getSide());          // RIGHT
    if (!info.isSelfTestPassed()) {
        System.out.println("led fault: " + info.isLedFault());
        for (int i = 0; i < KeyMapping.KEY_COUNT; i++) {
            if (info.isKeyStuck(i)) {
                System.out.println("key " + i + " stuck at boot");
            }
        }
    }
}).exceptionally(e -> {
    System.err.println("connect failed: " + e.getCause());
    return null;
});
```

A key the self-test finds stuck is excluded from `onKey` and HID output for the
rest of the session; the only recovery is a full power cycle. Check
`info.isKeyStuck(index)` at connect time to know which keys, if any, will never
fire — there is no command to clear the exclusion.

A port's identity is confirmed only here: `connect()` fails if the device does not
announce itself as `MiniBoard54`. Attempting a `PROBABLE` or `UNKNOWN` port is
therefore safe — you find out on a port you chose to open.

`connect()` returns a future, but it is not itself a purely asynchronous call: it
opens the port on the calling thread, and on a reconnect it may first wait up to one
second for the previous connection's reader thread to exit. Call it from a thread that
can tolerate that pause.

If that wait expires — the previous reader is still stuck inside a read that will not
return — `connect()` refuses rather than reopening the port underneath it. The future
fails with a `MiniBoardException` naming the port, and the port is not reopened. With
a real serial port this does not happen, because a close makes the pending read return;
it is reachable if you supply your own `Transport` (see below) whose `read()` can block
indefinitely.

Commands issued after `connect()` returns but before the handshake finishes are
**queued, not dropped** — the firmware ignores anything received before it finishes
announcing itself. Their timeout clock does not start until the handshake completes,
so an early `board.getDebounce()` will not expire waiting for the hello.

A command needs a connection in progress or established. Submitting one before the
first `connect()`, or after a disconnect, fails it immediately with a
`MiniBoardException` rather than queuing it — there would be no deadline armed and no
guarantee a `connect()` is coming.

`MiniBoard` implements `AutoCloseable`. Instances are independent, so several boards
can be driven at once.

There is a second constructor, `MiniBoard(String portName, Transport transport)`,
which takes any `Transport` implementation. That is how you drive a fake device: the
whole engine — handshake, queue, timeouts, events — runs unchanged against your own
in-memory transport, with no serial port and no hardware.

---

## The connection lifecycle

A `MiniBoard` instance is **reusable**. It is bound to a board, not to a single
connection: after a disconnect you call `connect()` again on the same object, and
your listeners are still attached.

```
IDLE ──connect()──▶ CONNECTING ──handshake ok──▶ READY
  ▲                     │                          │
  │   open failed       │                          │  disconnect()
  │   handshake failed  │                          │  device unplugged
  │   wrong device      │                          │  read or write error
  │   wrong board       │                          │
  └─────────────────────┴──────────────────────────┘

  any ──close()──▶ CLOSED   (terminal — cannot reconnect)
```

`isConnected()` is true only in `READY`; `isClosed()` is true only in `CLOSED`.

### `disconnect()` versus `close()`

| | `disconnect()` | `close()` |
|---|---|---|
| Port | closed | closed |
| Pending commands | failed | failed |
| Listeners | kept | kept |
| `onDisconnected` | fires, `cause == null` | fires, `cause == null` |
| The two daemon threads | kept | released |
| `connect()` afterwards | works | fails |

`onDisconnected` fires on either call only if a port was actually open at the time;
closing an already-disconnected instance notifies nothing.

`MiniBoard` is `AutoCloseable`, so `close()` is what try-with-resources calls, and it
still means *"done with this board"*.

### Reconnecting after an unexpected drop

Wait for `onDisconnected` before reconnecting. By the time it fires the port is
closed, the previous reader thread has exited, and the instance is already back in
`IDLE` — so reconnecting from inside the callback is safe.

```java
board.setListener(new MiniBoardAdapter() {
    @Override
    public void onDisconnected(Throwable cause) {
        if (cause == null) {
            return;            // we asked for this one
        }
        // Give the board time to re-enumerate, then try again. The library never
        // retries on its own; the policy is yours.
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                board.connect().whenComplete((info, error) -> { /* ... */ });
            }
        }, 2, TimeUnit.SECONDS);
    }
});
```

Reconnecting directly inside `onDisconnected`, rather than from a scheduled task as
above, is supported but occupies the single callback thread while the port opens, so
every later callback queues behind it.

`disconnect()` is synchronous — when it returns the port is closed and the reader has
been joined, so you may reconnect immediately with no wait.

### When the board moves to a different COM port

Windows does not guarantee a device keeps its COM number across re-enumeration.
Re-run discovery and retarget the same instance:

```java
DiscoveredPort port = MiniBoardDiscovery.findBest();
if (port != null) {
    board.connect(port.getPortName());   // retargets, then connects
}
```

`connect(String)` rejects a null or blank name with `IllegalArgumentException`, as the
constructor does. `getPortName()` afterwards reports the new target.

### One instance, one board

The first successful handshake binds the instance to that board's serial. A later
`connect()` that meets a **different** serial is refused, and the connection never
reaches `READY`:

```
The device now on COM7 has serial FEED, but this MiniBoard is bound to 01020304.
Refusing to reconnect to a different board; construct a new MiniBoard for it.
```

This is deliberate: without it, a shuffled COM number could quietly point a
`setKey()` or a `save()` at somebody else's keyboard. It also means `findBest()` is
safe to feed straight into `connect(String)` — picking the wrong board fails the
future instead of writing to it.

The refusal is retryable: the instance tears down to `IDLE` and can be pointed at
another port. That teardown runs on its own thread and finishes shortly *after* the
future fails, so wait for `onDisconnected` before the next attempt, exactly as with
any other drop. To talk to a genuinely different board, construct a second
`MiniBoard`.

### After `reboot()`

`reboot()` completes normally and then the port drops, reported as a clean
disconnect (`cause == null`). Once the device re-enumerates — a second or two — the
same instance reconnects:

```java
board.reboot(RebootMode.WATCHDOG).thenRun(() -> {
    // ... wait for re-enumeration, then:
    board.connect();
});
```

`RebootMode.USB_BOOT` is different: the board comes back as a UF2 mass-storage
device with no serial port at all, so there is nothing to reconnect to.

### You must close what you own

A failed `connect()` leaves the instance **usable**, so that you can retry once the
board finishes re-enumerating. That means it no longer disposes of itself on
failure, and code that walks a discovery list must close what it opens:

```java
for (DiscoveredPort p : MiniBoardDiscovery.findLikely()) {
    try (MiniBoard candidate = new MiniBoard(p.getPortName())) {
        DeviceInfo info = candidate.connect().get(5, TimeUnit.SECONDS);
        // ... use it ...
    } catch (Exception notThisOne) {
        // next port
    }
}
```

Forgetting leaves up to two daemon threads per instance — `miniboard-callback-N` and
`miniboard-dispatch-<port>`. They never block JVM exit, but they are a leak.

### Commands need a connection

A command submitted while idle or closed is rejected immediately with
`MiniBoardException` rather than queued. Commands submitted *during* the handshake
are queued and released when it completes, with their timeout starting then.

---

## Commands

Both forms exist for all twenty commands.

```java
// Future
board.getDebounce().thenAccept(ms -> System.out.println(ms + " ms"));

// Callback
board.getDebounce(new MiniBoardCallback<Integer>() {
    public void onSuccess(Integer ms) { System.out.println(ms + " ms"); }
    public void onError(Throwable e)  { e.printStackTrace(); }
});
```

Commands are **serialised**: exactly one is in flight at a time, because the protocol
has no sequence numbers and responses are matched only by echoed opcode. Issuing
several at once is safe — they queue, up to `MiniBoard.MAX_QUEUED_COMMANDS` (64),
beyond which submission fails fast rather than growing without bound.

```java
board.setKey(new KeyMapping(0, 0, 0x04))         // slot 0, key_id 0, 'A'
     .thenCompose(applied -> board.setDebounce(20))
     .thenCompose(ms -> board.save())            // persist to flash
     .thenAccept(written -> System.out.println(written ? "saved" : "already up to date"));
```

`SET` commands resolve with the value the **device applied**, not the one requested.
Changes live in RAM until `save()`; `reset()` restores factory defaults immediately
without needing `save()`. `save()` resolves with a `Boolean`: `true` if the
configuration was actually written to flash, `false` if the write was skipped because
nothing had changed.

`KeyMapping(int index, int keyId, int... keyCodes)` describes one key slot: `index`
(0-53), an opaque `keyId` (0-255, never sent over HID, stored and persisted exactly
like the HID codes), and up to six HID key codes. `getKey()`/`setKey()` round-trip a
`KeyMapping` exactly as the device stores it.

```java
board.setLed(new LedConfig(0, LedMode.PULSE, LedFinal.OFF,
                           255, 0, 0,   // target colour
                           3,           // 3 cycles; 0 would loop forever
                           250));       // 250 ms per step
```

`LedConfig.REQUEST_SIZE` (9 bytes) and `LedConfig.RESPONSE_SIZE` (13 bytes) name what
`toRequestData()` produces and `fromResponse()` consumes. Beyond the requested
animation, a `LedConfig` also reports live state: `getCurRed()`/`getCurGreen()`/
`getCurBlue()` give the colour the LED is *physically showing right now*, and
`isOverlayActive()`/`getFlags()` report whether a firmware notification overlay (e.g.
typo rejection) is currently covering it.

`getWrites()` decodes its 4-byte payload as a **little-endian** `uint32`.

Flash endurance is finite. Check `getWrites()` and never call `save()` in a loop.

Index arguments are validated client-side and throw `IllegalArgumentException`
immediately, rather than spending a round trip to be told `OUT_OF_RANGE`.

### Temporarily suppressing keystrokes

While remapping, stop the board typing into your application without changing its
stored configuration:

```java
board.setHidDisableTemp(true)
     .thenCompose(v -> board.setKey(new KeyMapping(5, 0, 0x1E)))
     .thenCompose(v -> board.save())
     .thenCompose(v -> board.setHidDisableTemp(false));
```

This flag is session-scoped: never written to flash, and cleared automatically when
the port closes.

### Rebooting

```java
board.reboot(RebootMode.USB_BOOT);   // into the UF2 bootloader
board.reboot(RebootMode.WATCHDOG);   // normal restart
```

The device replies and then resets, so the port drops immediately. That drop is
expected — the future completes normally and `onDisconnected` fires with a `null`
cause. The same instance reconnects once the device re-enumerates; see
[After `reboot()`](#after-reboot).

---

## Events

```java
board.setListener(new MiniBoardAdapter() {
    @Override public void onKey(int index, int keyId) {
        System.out.println("key " + index + " (id " + keyId + ")");
    }
    @Override public void onLedFinish(int led)   { System.out.println("led " + led + " done"); }
    @Override public void onTypoRejected()       { System.out.println("typo"); }
    @Override public void onMessage(String text) { System.out.println("fw: " + text); }
    @Override public void onSideChanged(Side s)  { System.out.println("side " + s); }
    @Override public void onDisconnected(Throwable cause) {
        System.out.println(cause == null ? "closed" : "lost: " + cause);
    }
    @Override public void onError(Throwable e)   { e.printStackTrace(); }
});
```

All callbacks run on one thread per board, so they never overlap — but a slow callback
delays every later one. Do heavy work elsewhere. An exception thrown by a listener is
routed to `onError` rather than killing the callback thread.

The listener belongs to the instance, not to the connection: it survives a disconnect
and receives the next connection's events without being reinstalled.

`onLedFinish` never fires for `BLINK` or `PULSE` configured with `iterations = 0`,
which loop forever.

`onLedFinish` may be called with **`-1`**. `mb_cdc_interface.md` contradicts itself on
LED_FINISH's payload — the summary table gives LEN 0, §8 documents a 1-byte `index` —
so the library reports `-1` when the event carries no index rather than inventing one.
Guard for it: indexing an array by `ledIndex` would otherwise throw
`ArrayIndexOutOfBoundsException` from inside your listener, which arrives at `onError`
as a mystery.

---

## Errors

All are unchecked and arrive through future completion.

| Exception | Cause |
|---|---|
| `MiniBoardException` | Handshake failure, connection loss, unexpected frame, queue full, malformed device payload, a command submitted while idle or closed, a reconnect landing on a different board, a reconnect refused because the previous reader would not exit |
| `MiniBoardStatusException` | Device replied with a non-OK `Status` |
| `MiniBoardTimeoutException` | No response within the command timeout |
| `MiniBoardIoException` | Serial port I/O failure |

The split is by *whose* fault it is. Anything the device did wrong — including a
`GET_KEY` payload of the wrong length or a `GET_LED` naming an LED that does not
exist — fails the future with a `MiniBoardException`, so branching on that one type
in `onError` catches every device-side failure. `IllegalArgumentException` means
*your* argument was wrong, and is thrown synchronously from the calling thread rather
than delivered to the future — including from the callback overloads, which validate
before they have anything to call back on.

One delivery wrinkle: `connect()` returns the engine's future directly, so a
`.whenComplete()`/`.exceptionally()` attached to it sees the raw exception. Every
command method instead returns a `.thenApply()`-derived future, so on that future
`.whenComplete()`/`.exceptionally()` see any failure — decode or otherwise — wrapped in
a `CompletionException`. `.get()` and the callback overloads are unaffected:
`ExecutionException` and `MiniBoard.adapt` both unwrap it.

Unknown enum values are not errors. A `LedMode` or `LedFinal` that this version of the
library does not recognise degrades to a `null` `getMode()` / `getFinalState()`, with
the wire byte preserved in `getRawMode()` / `getRawFinalState()` and re-emitted
unchanged by `toRequestData()`. Firmware that adds a new mode does not break `getLed()`.

Default timeouts: **1000 ms** per command, **5000 ms** for `save()` and `reset()`
(both write flash), **3000 ms** for the handshake.

The library never reconnects on its own. On an unexpected disconnect every pending
future fails and `onDisconnected` fires; call `connect()` again on the same instance
when you want it back — see
[The connection lifecycle](#the-connection-lifecycle).

---

## Threads

Per board:

| Thread | Role |
|---|---|
| `miniboard-reader-<port>-<n>` | Blocking reads, frame decoding. One per *connection*: `<n>` is that connection's generation number, so a reconnect starts a fresh reader |
| `miniboard-callback-N` | All listener callbacks and future completions. `N` counts instances across the JVM |
| `miniboard-dispatch-<port>` | Writes queued commands to the port. `<port>` is the name the instance was constructed with and is not renamed by `connect(String)` — cosmetic, but worth knowing when reading a thread dump |

Shared, or short-lived:

| Thread | Role |
|---|---|
| `miniboard-timeout` | Command timeouts. One per JVM, shared by every board |
| `miniboard-teardown-<port>` | Short-lived, spawned only to tear a connection down away from the reader, dispatch and timeout threads, none of which may block |
| `miniboard-complete-<port>` | Short-lived; only ever spawned to deliver a future completion that arrives after the callback thread has already been shut down, because a completion may never be dropped |

All are daemon threads. Futures complete on the callback thread, never the reader
thread, so a `thenApply` chain cannot stall frame decoding.

The only thread that can block on a write to the port is `miniboard-dispatch-<port>`,
and there is one per board. That is deliberate: `miniboard-timeout` is shared by every
board in the JVM, so a write wedged on a stalled device must not run there or one
unhealthy board would stop every other board timing its commands out. It also means at
most one thread is ever inside a write for a given board, so two frames can never
interleave on the wire.

`close()` releases the callback and dispatch threads. `disconnect()` does not — they
belong to the instance, which is still alive and reconnectable.

---

## Raw protocol log

```java
board.setRawFrameListener(new RawFrameListener() {
    public void onTxFrame(Frame f, long ts) { log("TX", f, ts); }
    public void onRxFrame(Frame f, long ts) { log("RX", f, ts); }

    public void onFrameError(byte[] discarded, FrameError reason, long ts) {
        System.out.printf(Locale.ROOT, "%d  !! %s  %s%n", ts, reason, hex(discarded));
    }

    void log(String dir, Frame f, long ts) {
        System.out.printf(Locale.ROOT, "%d  %s  %-52s %s%n", ts, dir, f, hex(f.getRawBytes()));
    }

    String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(java.util.Locale.ROOT, "%02X ", b & 0xFF));
        return sb.toString();
    }
});
```

`Frame` gives both a decoded view and `getRawBytes()` — the exact on-wire bytes
including SOF and CRC. `onFrameError` reports everything the decoder rejected: CRC
failures, unknown opcodes, resync garbage, and truncated frames. A log that silently
dropped those could not diagnose the problems it exists for.

Both directions are delivered on the same thread, so the log reflects true wire order.
The protocol classes (`Frame`, `Crc8`, `FrameDecoder`, `FrameEncoder`, `Opcode`,
`EventType`, `Status`) are public API, so a debug tool can re-decode captures
independently.

The raw frame listener, like the event listener, belongs to the instance and survives
a reconnect. Each connection decodes from a clean slate, so a half-frame left on the
wire by a vanishing device cannot corrupt the next connection's first frame.

---

## Running the tests

```powershell
.\gradlew.bat test                    # hardware tests skipped
.\gradlew.bat test -Phardware=true    # runs the hardware tests too
```

The hardware tests require a MiniBoard54 physically attached and are skipped by
default — the gate is the `miniboard.hardware` system property, which `-Phardware=true`
sets. They are strictly read-only: they issue no `SET`, `SAVE`, `RESET` or `REBOOT`,
so they cannot alter your key map or consume a flash write.

Everything else runs against `FakeTransport`, an in-memory `Transport` that the whole
engine drives unchanged — so the reconnect, handshake, queue and teardown behaviour
described above is tested without a board attached.
