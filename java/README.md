# miniboard-lib

Java library for the MiniBoard54 keyboard over its USB CDC serial port.

> **Full usage guide:** [`docs/usage.md`](docs/usage.md) — discovery, the connection
> lifecycle and reconnecting, commands, events, errors, and the threading model.

- **Asynchronous.** Every device operation returns a `CompletableFuture` and has a
  callback overload.
- **Automatic COM port detection** from USB metadata — **no port is ever opened during
  discovery**.
- **Reusable instances.** After a disconnect the same `MiniBoard` reconnects, keeping
  its listeners and its board identity. `close()` is the only terminal step.
- **Frame-level raw hook** for building a protocol log.
- Java 8, Windows 7+.

---

## Building

```powershell
.\build.ps1            # build and stage build\dist\
.\build.ps1 -Clean     # clean first
.\build.ps1 -Offline   # no network
```

Gradle need not be installed — `build.ps1` runs the committed Gradle wrapper
(`gradlew.bat`), which downloads and verifies Gradle itself against the pinned
SHA-256 checksum in `gradle/wrapper/gradle-wrapper.properties`.

The build stages `miniboard-lib-1.0.0.jar` plus three dependency jars into
`build\dist\`; all four go on your classpath. See
[Requirements and installation](docs/usage.md#requirements-and-installation).

> **`purejavacomm:0.0.29` is not published to Maven Central** under any coordinate, so
> the jar is committed at `libs\purejavacomm-0.0.29.jar` to keep a clean clone
> buildable. It is the one deliberate exception to `.gitignore`'s `libs/*.jar` rule.
> If it is ever missing, `build.ps1` fails with a message naming that exact path. The
> other two dependencies resolve from Maven Central normally.

`.\gradlew.bat test` runs the suite; the hardware tests are skipped unless a board is
attached and `-Phardware=true` is passed. See
[Running the tests](docs/usage.md#running-the-tests).

---

## Quick start

```java
DiscoveredPort port = MiniBoardDiscovery.findBest();
if (port == null) {
    System.out.println("no MiniBoard found");
    return;
}

try (MiniBoard board = new MiniBoard(port.getPortName())) {
    board.setListener(new MiniBoardAdapter() {
        @Override
        public void onKey(int keyIndex, int keyId) {
            System.out.println("key " + keyIndex);
        }
    });

    DeviceInfo info = board.connect().get(5, TimeUnit.SECONDS);
    System.out.println(info.getName() + " v" + info.getVersionString()
            + ", serial " + info.getSerialHex());

    System.out.println("debounce " + board.getDebounce().get(2, TimeUnit.SECONDS) + " ms");
}   // close() drops the port and releases this instance's threads
```

`connect()` is what confirms a port really is a MiniBoard54, so attempting a
lower-ranked port is safe. To keep the instance after dropping the link, call
`disconnect()` instead of `close()` — then `connect()` again when you want it back.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/usage.md`](docs/usage.md) | Discovery and how identification works, connecting, the connection lifecycle, commands, events, errors, threads, raw frame logging, running the tests |
| [`docs/mb_cdc_interface.md`](docs/mb_cdc_interface.md) | The wire protocol |

---

## Known limitations

- **JNA 3.5.1 warnings on modern JDKs.** The pinned `jna`/`platform` 3.5.1 pair predates
  the JDK's restricted-method policy, so JDK 24+ prints warnings such as
  `A restricted method in java.lang.System has been called` /
  `Restricted methods will be blocked in a future release` on the first PnP lookup.
  They are noise today, but the wording is a schedule: when a JDK finally blocks
  restricted methods by default, discovery stops ranking and falls back to reporting
  every port as `UNKNOWN`. Silence them meanwhile with `--enable-native-access=ALL-UNNAMED`.
  Nothing else in the library uses JNA, so a future JNA bump is confined to
  `com.itsonlybinary.miniboard.win32`.
- **Windows-only ranking.** Elsewhere every port is returned as `UNKNOWN`; connecting
  still works, and `connect()` is what actually confirms a board's identity.
- **No automatic reconnect**, by design: the library never retries on its own. Manual
  reconnect on the same instance is supported — see
  [the lifecycle chapter](docs/usage.md#the-connection-lifecycle).

---

## Protocol reference

See `docs/mb_cdc_interface.md` for the wire format, all twenty
commands, the nine event types, and the CRC-8 definition.
