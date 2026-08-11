# MiniBoard54 CDC Interface

This document is the reference for the `mb_cdc` serial protocol used to configure, control, and monitor the MiniBoard54 keyboard over USB CDC (virtual serial port). It covers the frame format, all supported commands, device-initiated events, and practical implementation guidance.

---

## Table of Contents

1. [Overview](#overview)
2. [Device Identification](#device-identification)
3. [Frame Formats](#frame-formats)
   - [Command Request Frame](#command-request-frame)
   - [Command Response Frame](#command-response-frame)
   - [Event Frame](#event-frame)
4. [CRC Algorithm](#crc-algorithm)
5. [Status Codes](#status-codes)
6. [Connection Sequence](#connection-sequence)
7. [Commands](#commands)
8. [Events](#events)
9. [LED Reference](#led-reference)
10. [Error Handling](#error-handling)
11. [Byte-Level Examples](#byte-level-examples)

---

## Overview

The protocol is binary and frame-based. There are two directions of communication:

- **Host → Device**: Command request frames sent by your application.
- **Device → Host**: Command response frames (replies to requests) and event frames (unsolicited notifications from the device).

All frames begin with the Start-of-Frame byte `0xAA`. Commands use opcode bytes in the range `0x80`–`0x9F`; event types use `0x01`–`0x0F` (hello events) or `0x10`–`0x7F` (runtime events).

---

## Device Identification

Use the following USB identifiers to locate the correct serial port before opening a connection:

| Field        | Value          |
|--------------|----------------|
| VID          | `0x2E8A`       |
| PID          | `0x104E`       |
| Product name | `"MiniBoard54"`|

> **Note:** VID `0x2E8A` is Raspberry Pi's registered vendor ID, and this PID is shared across multiple RP2040-based products. VID/PID alone is **not sufficient** to identify the device — also match the USB product string descriptor (`"MiniBoard54"`) before opening the port. This matches the `NAME` event sent during the [connection sequence](#connection-sequence).

---

## Frame Formats

### Command Request Frame

Sent by the host to issue a command.

```
┌──────┬──────┬──────┬─────────────┬──────┐
│ SOF  │ CMD  │ LEN  │    DATA     │ CRC  │
│ 0xAA │ 1 B  │ 1 B  │  LEN bytes  │ 1 B  │
└──────┴──────┴──────┴─────────────┴──────┘
```

| Field | Size      | Description                                      |
|-------|-----------|--------------------------------------------------|
| SOF   | 1 byte    | Always `0xAA`                                    |
| CMD   | 1 byte    | Command opcode (`0x80`–`0x9F`)                   |
| LEN   | 1 byte    | Number of DATA bytes that follow (0 if no data)  |
| DATA  | LEN bytes | Command-specific payload                         |
| CRC   | 1 byte    | CRC-8 over CMD + LEN + DATA (see [CRC Algorithm](#crc-algorithm)) |

> **Note:** When LEN is 0 there are zero DATA bytes; the CRC still covers CMD and LEN.

---

### Command Response Frame

Sent by the device in reply to every command request (including error responses).

```
┌──────┬──────┬────────┬──────┬─────────────┬──────┐
│ SOF  │ CMD  │ STATUS │ LEN  │    DATA     │ CRC  │
│ 0xAA │ 1 B  │ 1 B    │ 1 B  │  LEN bytes  │ 1 B  │
└──────┴──────┴────────┴──────┴─────────────┴──────┘
```

| Field  | Size      | Description                                          |
|--------|-----------|------------------------------------------------------|
| SOF    | 1 byte    | Always `0xAA`                                        |
| CMD    | 1 byte    | Echoes the command opcode from the request           |
| STATUS | 1 byte    | [Status code](#status-codes)                         |
| LEN    | 1 byte    | Number of DATA bytes that follow                     |
| DATA   | LEN bytes | Response payload (absent when STATUS ≠ OK or LEN=0)  |
| CRC    | 1 byte    | CRC-8 over CMD + STATUS + LEN + DATA                 |

> **Important:** The response frame has an **extra STATUS byte** compared to the request and event frames. A parser must handle these as distinct frame types.

---

### Event Frame

Sent **unsolicited** by the device to notify the host of hardware or state changes.

```
┌──────┬──────┬──────┬─────────────┬──────┐
│ SOF  │ EVT  │ LEN  │    DATA     │ CRC  │
│ 0xAA │ 1 B  │ 1 B  │  LEN bytes  │ 1 B  │
└──────┴──────┴──────┴─────────────┴──────┘
```

| Field | Size      | Description                                      |
|-------|-----------|--------------------------------------------------|
| SOF   | 1 byte    | Always `0xAA`                                    |
| EVT   | 1 byte    | Event type (`0x01`–`0x0F` hello / `0x10`–`0x7F` runtime) |
| LEN   | 1 byte    | Number of DATA bytes that follow                 |
| DATA  | LEN bytes | Event-specific payload                           |
| CRC   | 1 byte    | CRC-8 over EVT + LEN + DATA                      |

Event frames **do not have a STATUS byte**. They are distinguished from response frames by their opcode range (`0x01`–`0x7F` vs `0x80`–`0x9F`).

---

## CRC Algorithm

The CRC is CRC-8 with polynomial **0x07**, computed with an **initial value of 0xFF**.

```c
static uint8_t crc8_update(uint8_t crc, uint8_t byte) {
    crc ^= byte;
    for (uint8_t i = 0; i < 8; i++) {
        crc = (crc & 0x80) ? (crc << 1) ^ 0x07 : (crc << 1);
    }
    return crc;
}
```

**What bytes are covered (in order):**

| Frame Type | Bytes fed into CRC (in order)              |
|------------|--------------------------------------------|
| Request    | CMD, LEN, DATA[0..LEN-1]                  |
| Response   | CMD, STATUS, LEN, DATA[0..LEN-1]          |
| Event      | EVT, LEN, DATA[0..LEN-1]                 |

The SOF byte (`0xAA`) is **never** included in the CRC calculation.

---

## Status Codes

Returned in the STATUS byte of every command response.

| Value | Name                         | Description                          |
|-------|------------------------------|--------------------------------------|
| 0x00  | `MB_CDC_STATUS_OK`           | Command executed successfully        |
| 0x01  | `MB_CDC_STATUS_BAD_CMD`      | Unknown command opcode               |
| 0x02  | `MB_CDC_STATUS_BAD_LEN`      | Wrong number of data bytes           |
| 0x03  | `MB_CDC_STATUS_BAD_DATA`     | Data payload is invalid              |
| 0x04  | `MB_CDC_STATUS_CRC_FAILED`   | CRC mismatch in the received request |
| 0x05  | `MB_CDC_STATUS_OUT_OF_RANGE` | Index exceeds valid range            |
| 0x06  | `MB_CDC_STATUS_INTERNAL_ERR` | Internal firmware error              |

When STATUS is non-zero, the response DATA field is absent and LEN is 0.

---

## Connection Sequence

When the host opens the virtual serial port (asserts DTR), the device waits briefly (~10 ms) and then sends the following **hello events** in order before accepting any commands:

1. **NAME** (`0x01`) — fixed ASCII string `"MiniBoard54"`
2. **VERSION** (`0x02`) — firmware major and minor version bytes
3. **SERIAL** (`0x03`) — board unique ID (up to 16 bytes)
4. **SIDE** (`0x04`) — which USB connector is active
5. **TYPE** (`0x05`) — the board's config type identifier
6. **STATUS** (`0x06`) — boot self-test result (pass/fail, stuck keys, LED fault)

Event codes `0x07`–`0x0F` are reserved for future hello events.

The host should wait to receive all six hello events before sending commands. Any data received by the device before the hello sequence is complete is flushed and ignored.

When the serial port is **closed** (DTR de-asserted), the firmware automatically clears all session-scoped state, including the temporary HID disable flag set by `SET_HID_DISABLE_TEMP`.

---

## Commands

Commands are listed with their opcodes in firmware order. All SET commands echo back the new value in the response data.

### Summary Table

| #  | Command              | Opcode | Request LEN | Response LEN |
|----|----------------------|--------|-------------|--------------|
| 1  | GET_CONFIG_TYPE      | 0x80   | 0           | 1            |
| 2  | SET_CONFIG_TYPE      | 0x81   | 1           | 1            |
| 3  | GET_DEBOUNCE         | 0x82   | 0           | 1            |
| 4  | SET_DEBOUNCE         | 0x83   | 1           | 1            |
| 5  | GET_HID_ENABLE       | 0x84   | 0           | 1            |
| 6  | SET_HID_ENABLE       | 0x85   | 1           | 1            |
| 7  | GET_TYPO_REJECT      | 0x86   | 0           | 1            |
| 8  | SET_TYPO_REJECT      | 0x87   | 1           | 1            |
| 9  | GET_KEY              | 0x88   | 1           | 8            |
| 10 | SET_KEY              | 0x89   | 8           | 8            |
| 11 | GET_LED              | 0x8A   | 1           | 13           |
| 12 | SET_LED              | 0x8B   | 9           | 13           |
| 13 | GET_LED_BRIGHTNESS   | 0x8C   | 0           | 1            |
| 14 | SET_LED_BRIGHTNESS   | 0x8D   | 1           | 1            |
| 15 | SET_LED_OFF          | 0x8E   | 1           | 1            |
| 16 | GET_WRITES           | 0x8F   | 0           | 4            |
| 17 | SET_HID_DISABLE_TEMP | 0x90   | 1           | 1            |
| 18 | SAVE                 | 0x91   | 0           | 1            |
| 19 | RESET                | 0x92   | 0           | 0            |
| 20 | REBOOT               | 0x93   | 1           | 1            |

---

### 1. GET_CONFIG_TYPE (0x80)

Get the board's config type identifier.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field         | Description                                                  |
|------|---------------|--------------------------------------------------------------|
| 0    | `config_type` | Config type value read from the firmware preset (`uint8_t`)  |

---

### 2. SET_CONFIG_TYPE (0x81)

Overwrite the in-memory config type. Changes are **not** persisted until `SAVE` is sent.

**Request data (1 byte):**

| Byte | Field         | Description            |
|------|---------------|------------------------|
| 0    | `config_type` | New config type value  |

**Response data (1 byte):** echoes the new value.

---

### 3. GET_DEBOUNCE (0x82)

Get the current key debounce delay.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field   | Description                    |
|------|---------|--------------------------------|
| 0    | `value` | Debounce delay in milliseconds |

---

### 4. SET_DEBOUNCE (0x83)

Set the key debounce delay. Changes take effect immediately but are **not** persisted until `SAVE` is sent.

**Request data (1 byte):**

| Byte | Field   | Description                        |
|------|---------|-------------------------------------|
| 0    | `value` | New debounce delay in milliseconds  |

**Response data (1 byte):** echoes the new value.

---

### 5. GET_HID_ENABLE (0x84)

Get the persistent HID output enable flag.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field   | Description                             |
|------|---------|------------------------------------------|
| 0    | `value` | `0` = HID disabled, `1` = HID enabled   |

> **Note:** This reflects the **stored** setting. The effective runtime state may additionally be suppressed by `SET_HID_DISABLE_TEMP`.

---

### 6. SET_HID_ENABLE (0x85)

Enable or disable HID keyboard output. This is the **persistent** setting; use `SAVE` to write it to flash.

**Request data (1 byte):**

| Byte | Field   | Description                               |
|------|---------|-------------------------------------------|
| 0    | `value` | `0` = disable HID output, `1` = enable   |

**Response data (1 byte):** echoes the new value.

---

### 7. GET_TYPO_REJECT (0x86)

Get the typo rejection enable flag.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field   | Description                                        |
|------|---------|-----------------------------------------------------|
| 0    | `value` | `0` = typo rejection off, `1` = typo rejection on  |

---

### 8. SET_TYPO_REJECT (0x87)

Enable or disable typo rejection. Changes are not persisted until `SAVE` is sent.

**Request data (1 byte):**

| Byte | Field   | Description                  |
|------|---------|------------------------------|
| 0    | `value` | `0` = disable, `1` = enable  |

**Response data (1 byte):** echoes the new value.

---

### 9. GET_KEY (0x88)

Read the key_id and HID key codes assigned to a specific key slot.

**Request data (1 byte):**

| Byte | Field   | Description          |
|------|---------|----------------------|
| 0    | `index` | Key index (`0`–`53`) |

**Response data (8 bytes):**

| Byte | Field     | Description                                                        |
|------|-----------|---------------------------------------------------------------------|
| 0    | `index`   | Key index echoed back                                                |
| 1    | `key_id`  | Opaque per-key identifier. Never sent over HID.                     |
| 2–7  | `keys[6]` | Up to 6 USB HID key codes; unused slots = `0x00`                    |

Returns `MB_CDC_STATUS_OUT_OF_RANGE` if `index` ≥ 54.

---

### 10. SET_KEY (0x89)

Write key_id and HID key codes to a key slot. Changes are not persisted until `SAVE` is sent.

**Request data (8 bytes):**

| Byte | Field     | Description                                                        |
|------|-----------|-----------------------------------------------------------------------|
| 0    | `index`   | Key index (`0`–`53`)                                                  |
| 1    | `key_id`  | Opaque per-key identifier. Never sent over HID.                       |
| 2–7  | `keys[6]` | Up to 6 USB HID key codes; pad unused with `0x00`                     |

**Response data (8 bytes):** echoes the updated key slot (same layout as GET_KEY response).

Returns `MB_CDC_STATUS_OUT_OF_RANGE` if `index` ≥ 54.

---

### 11. GET_LED (0x8A)

Read the current LED animation configuration and live state for one LED.

**Request data (1 byte):**

| Byte | Field   | Description         |
|------|---------|---------------------|
| 0    | `index` | LED index (`0`–`3`) |

**Response data (13 bytes):**

| Bytes | Field         | Type       | Description                                                            |
|-------|---------------|------------|------------------------------------------------------------------------|
| 0     | `index`       | uint8      | LED index echoed back                                                  |
| 1     | `mode`        | uint8      | Animation mode — see [LED Modes](#led-modes)                           |
| 2     | `final`       | uint8      | Final state after animation — see [LED Final States](#led-final-states)|
| 3     | `r`           | uint8      | Target red component (0–255) — the colour the host requested          |
| 4     | `g`           | uint8      | Target green component (0–255) — the colour the host requested        |
| 5     | `b`           | uint8      | Target blue component (0–255) — the colour the host requested        |
| 6     | `iterations`  | uint8      | Number of animation cycles for BLINK/PULSE (0 = infinite). Ignored for TRANSITION — the animation always runs once. |
| 7–8   | `duration_ms` | uint16 LE  | Animation step duration in milliseconds (little-endian)                |
| 9     | `cur_r`       | uint8      | Red component physically lit right now (0–255)                         |
| 10    | `cur_g`       | uint8      | Green component physically lit right now (0–255)                       |
| 11    | `cur_b`       | uint8      | Blue component physically lit right now (0–255)                        |
| 12    | `flags`       | uint8      | Status flags — bit 0 = notification overlay active (bit 0 = 0x01)     |

**Colour note:** Bytes 3–5 show the *target* colour (what the host requested), while bytes 9–11 show what is physically lit. These differ when an animation is mid-transition or when a firmware notification overlay (e.g. typo rejection) is displayed on top.

Returns `MB_CDC_STATUS_OUT_OF_RANGE` if `index` ≥ 4.

---

### 12. SET_LED (0x8B)

Configure and start an LED animation. Takes effect immediately; not persisted to flash.

**Request data (9 bytes):**

| Bytes | Field         | Type      | Description                                        |
|-------|---------------|-----------|----------------------------------------------------|
| 0     | `index`       | uint8     | LED index (`0`–`3`)                                |
| 1     | `mode`        | uint8     | Animation mode (`0`–`3`)                           |
| 2     | `final`       | uint8     | Final state (`0`–`2`)                              |
| 3     | `r`           | uint8     | Target red (0–255)                                 |
| 4     | `g`           | uint8     | Target green (0–255)                               |
| 5     | `b`           | uint8     | Target blue (0–255)                                |
| 6     | `iterations`  | uint8     | Animation cycles for BLINK/PULSE (0 = infinite). Ignored for TRANSITION — the animation always runs once and always fires `LED_FINISH`. |
| 7–8   | `duration_ms` | uint16 LE | Step duration in ms (little-endian)                |

**Response data (13 bytes):** same as `GET_LED`, showing the configuration now active and the live colour / overlay state.

Returns `MB_CDC_STATUS_OUT_OF_RANGE` if `index` ≥ 4, `mode` ≥ 4, or `final` ≥ 3.

---

### 13. GET_LED_BRIGHTNESS (0x8C)

Get the global LED brightness multiplier.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field   | Description                       |
|------|---------|-----------------------------------|
| 0    | `value` | Brightness (0 = off, 255 = full)  |

---

### 14. SET_LED_BRIGHTNESS (0x8D)

Set the global LED brightness multiplier. Changes are not persisted until `SAVE` is sent.

**Request data (1 byte):**

| Byte | Field   | Description            |
|------|---------|------------------------|
| 0    | `value` | New brightness (0–255) |

**Response data (1 byte):** echoes the new value.

---

### 15. SET_LED_OFF (0x8E)

Immediately extinguish one LED and cancel any active animation on it.

**Request data (1 byte):**

| Byte | Field   | Description         |
|------|---------|---------------------|
| 0    | `index` | LED index (`0`–`3`) |

**Response data (1 byte):**

| Byte | Field   | Description           |
|------|---------|------------------------|
| 0    | `index` | LED index echoed back  |

Returns `MB_CDC_STATUS_OUT_OF_RANGE` if `index` ≥ 4.

---

### 16. GET_WRITES (0x8F)

Read the number of times the configuration sector has been erased to flash. Useful for estimating flash wear and wear-leveling performance.

**Request data:** none (LEN = 0)

**Response data (4 bytes, little-endian uint32):**

| Bytes | Field    | Description                                                          |
|-------|----------|----------------------------------------------------------------------|
| 0–3   | `writes` | Total write count (little-endian uint32). Incremented on every `SAVE` that actually wrote to flash, and on every `RESET`. |

**Persistent counter:** This counter lives in its own dedicated flash sector beyond the firmware image, so it survives UF2 flashing and factory resets. It is cleared only by a full-chip erase (e.g. `MiniBoard54_full_wipe.uf2` or `picotool erase`).

**Wear-detection note:** A board with a valid saved configuration but `writes == 0` has had its counter sector wiped independently of the configuration. This mismatch is itself a diagnostic signal and may indicate manual flash manipulation or a fault condition.

---

### 17. SET_HID_DISABLE_TEMP (0x90)

Apply a **session-scoped** HID disable that overrides the stored `enable_hid` setting for the duration of the current serial connection. This flag is **never saved to flash** and is **automatically cleared** when the serial port is closed or the device reboots.

Use this when your application wants to temporarily suppress hid keystrokes (e.g. when using keystrokes via serial) without altering the persisted configuration.

**Request data (1 byte):**

| Byte | Field   | Description                                           |
|------|---------|-------------------------------------------------------|
| 0    | `value` | `1` = disable HID for this session, `0` = re-enable  |

**Response data (1 byte):** echoes the new flag value.

> **Relationship with SET_HID_ENABLE:** `SET_HID_ENABLE` controls the persisted flag; `SET_HID_DISABLE_TEMP` adds a session-level override on top. HID output is suppressed if either flag disables it. The temp flag does not affect what `GET_HID_ENABLE` returns.

---

### 18. SAVE (0x91)

Persist the current in-memory configuration (config type, debounce, HID enable, typo rejection, key map, brightness) to flash.

**Request data:** none (LEN = 0)

**Response data (1 byte):**

| Byte | Field     | Description                                                        |
|------|-----------|---------------------------------------------------------------------|
| 0    | `written` | `1` = configuration was written to flash; `0` = skipped (no change) |

**Blocking behaviour:** `SAVE` blocks for the full flash erase duration (tens of milliseconds; worst case ~400 ms) with interrupts disabled. The host **must** use a generous read timeout and must **not** treat a delayed reply as a failure. USB HID reports may stall during the erase.

**Flash endurance:** Flash has finite write endurance. Use `GET_WRITES` to monitor sector wear. Avoid calling `SAVE` in a tight loop.

---

### 19. RESET (0x92)

Erase the persisted configuration in flash and restore the in-memory configuration to factory defaults. This takes effect immediately — unlike the other SET/RESET-adjacent commands, `SAVE` is **not** required to persist it.

**Request data:** none (LEN = 0)

**Response data:** none (LEN = 0, STATUS = OK)

---

### 20. REBOOT (0x93)

Reboot the device. The firmware clears HID state, sends the response, then reboots.

**Request data (1 byte):**

| Byte | Field  | Description                                                         |
|------|--------|---------------------------------------------------------------------|
| 0    | `mode` | `0` = watchdog reset (normal boot), `1` = USB boot (BOOTSEL/UF2)  |

**Response data (1 byte):** echoes `mode`. The response is flushed before the device resets, but the serial connection will drop shortly after.

---

## Events

Events are sent asynchronously by the device. They share the SOF byte with commands and must be demultiplexed by opcode range on the host.

### Summary Table

| #  | Event          | Opcode | Data Length     | Description                  |
|----|----------------|--------|-----------------|-------------------------------|
| 1  | NAME           | 0x01   | Variable        | Device name string            |
| 2  | VERSION        | 0x02   | 2               | Firmware version               |
| 3  | SERIAL         | 0x03   | Variable (≤16)  | Board unique ID                |
| 4  | SIDE           | 0x04   | 1               | Active USB connector side      |
| 5  | TYPE           | 0x05   | 1               | Board config type identifier   |
| 6  | STATUS         | 0x06   | 9               | Boot self-test result          |
| 7  | KEY            | 0x10   | 2               | Key press notification         |
| 8  | TYPO_REJECTED  | 0x11   | 0               | Keystroke rejected as a typo   |
| 9  | LED_FINISH     | 0x12   | 0               | LED animation completed        |
| 10 | MSG            | 0x13   | Variable        | Debug/status text message      |

---

### 1. NAME (0x01)

Sent once during the [connection sequence](#connection-sequence).

**Data:** ASCII string `"MiniBoard54"` (no null terminator). Length is given by the LEN field.

---

### 2. VERSION (0x02)

Sent once during the connection sequence.

**Data (2 bytes):**

| Byte | Field           | Description          |
|------|-----------------|----------------------|
| 0    | `version_major` | Major version number |
| 1    | `version_minor` | Minor version number |

---

### 3. SERIAL (0x03)

Sent once during the connection sequence.

**Data:** Board unique identifier, up to 16 bytes. The actual length is given by the LEN field (typically 8 bytes on RP2040).

---

### 4. SIDE (0x04)

Sent during the connection sequence and again whenever the active USB connector side changes.

**Data (1 byte):**

| Byte | Field  | Description                                        |
|------|--------|----------------------------------------------------|
| 0    | `side` | Bitmask of active USB connectors (see table below) |

| Value | Meaning                |
|-------|------------------------|
| 0x00  | No connector active    |
| 0x01  | Left connector active  |
| 0x02  | Right connector active |
| 0x03  | Both connectors active |

---

### 5. TYPE (0x05)

Sent once during the [connection sequence](#connection-sequence).

**Data (1 byte):**

| Byte | Field         | Description                                                  |
|------|---------------|--------------------------------------------------------------|
| 0    | `config_type` | Board config type identifier read from the firmware preset   |

---

### 6. STATUS (0x06)

Sent once during the [connection sequence](#connection-sequence), as the last hello event. Reports the result of the boot-time self-test that runs before the device enters normal operation.

**Boot self-test:** on every power-on, the firmware runs a self-test before the main loop starts: it confirms the LED driver's PIO/DMA resources were claimed successfully, drives all 4 LEDs one at a time in a 1-second white "chase" so a human watching the board can visually confirm each one lights, and scans the key matrix for any key that reads "pressed" on 3 consecutive samples (a stuck/shorted key). A stuck key found this way is excluded from key processing for the rest of the session (it will not appear in `KEY` events or HID output); recovery requires a full power cycle.

> **Self-test caveat:** WS2812-style LEDs are a one-wire, write-only protocol with no data-out or acknowledgment. The firmware can only confirm the PIO/DMA transfer completed, not that an LED physically lit. The chase gives a human observer a way to notice a dead LED (the sequence visibly stops partway through), but this is **not** automated or logged — a dead LED that nobody is watching for will not be detected or reported by `led_fault` below, and LED signaling of `led_fault` itself may not be visible if the fault is severe enough to also break LED output.

**Data (9 bytes for the current 54-key presets; size varies with key count, see below):**

| Offset  | Field                | Description                                                                    |
|---------|----------------------|----------------------------------------------------------------------------------|
| 0       | `result`             | `0x00` = self-test passed, `0x01` = failed (OR of the two fields below)         |
| 1..N    | `stuck_keys_bitmap`  | 1 bit per key, LSB-first; byte `i` covers key indices `8i`–`8i+7`; bit set = stuck |
| N+1     | `led_fault`          | `0x00` = ok, `0x01` = LED hardware-init or LED transmit check failed            |

`N = ceil(key_count / 8)`; for the current 54-key presets this is 7 bytes, giving the 9-byte total above. The payload is always sent at this fixed size regardless of how many keys failed — a board-wide fault (e.g. rain-shorted keys) sets many bits rather than growing the frame.

---

### 7. KEY (0x10)

Sent each time a key is pressed.

**Data (2 bytes):**

| Byte | Field     | Description                                      |
|------|-----------|---------------------------------------------------|
| 0    | `key_idx` | Index of the key pressed (0–53)                    |
| 1    | `key_id`  | The pressed key's opaque per-key identifier        |

---

### 8. TYPO_REJECTED (0x11)

Sent when the typo rejection logic blocks a keystroke.

**Data:** none (LEN = 0)

---

### 9. LED_FINISH (0x12)

Sent when a host-configured LED animation completes. Firmware-issued notification overlays (such as typo rejection) do **not** emit this event.

- **TRANSITION** — always fires once when the colour transition reaches its target, regardless of the `iterations` field.
- **BLINK / PULSE** — only fires when a finite `iterations` count (> 0) is set and all cycles have run. With `iterations = 0` (infinite loop), this event is never sent.

**Data (1 byte):**

| Byte | Field   | Description                  |
|------|---------|------------------------------|
| 0    | `index` | Index of the LED that finished |

---

### 10. MSG (0x13)

General-purpose text message from the firmware (e.g. debug output).

**Data:** Variable-length ASCII string. Length given by the LEN field.

---

## LED Reference

### LED Modes

| Value | Name                     | Description                                  |
|-------|--------------------------|----------------------------------------------|
| 0     | `MB_LED_MODE_SOLID`      | No animation; LED is driven to target colour (renamed from `MB_LED_MODE_NONE`; wire value unchanged) |
| 1     | `MB_LED_MODE_TRANSITION` | Smooth colour transition to target           |
| 2     | `MB_LED_MODE_BLINK`      | Alternating on/off blink                     |
| 3     | `MB_LED_MODE_PULSE`      | Sine-wave brightness pulse to target colour  |

### LED Final States

Applied when a finite-iteration animation completes.

| Value | Name                   | Description                                                        |
|-------|------------------------|--------------------------------------------------------------------|
| 0     | `MB_LED_FINAL_OFF`     | Turn LED off                                                       |
| 1     | `MB_LED_FINAL_ON`      | Leave LED on at target colour                                      |
| 2     | `MB_LED_FINAL_RESTORE` | Restore the LED to the solid colour it was displaying when the animation was configured (not to a whole previous animation state) |

---

## Error Handling

Every command response includes a STATUS byte. Always check it before processing DATA.

**Recommended approach:**

1. Verify `SOF == 0xAA` before parsing the rest of the frame.
2. Compute and verify the CRC on the received frame. If it mismatches, discard the frame.
3. Check STATUS. If non-zero, log the error and do not attempt to read DATA.
4. For index-based commands (`GET_KEY`, `SET_KEY`, `GET_LED`, etc.), validate the index before sending to avoid `MB_CDC_STATUS_OUT_OF_RANGE` responses.

**Common errors and their causes:**

| Error                        | Likely cause                                        |
|------------------------------|-----------------------------------------------------|
| `MB_CDC_STATUS_BAD_CMD`      | Unknown opcode — check the command byte being sent  |
| `MB_CDC_STATUS_BAD_LEN`      | Sent wrong number of bytes for a command            |
| `MB_CDC_STATUS_CRC_FAILED`   | Bit error or CRC computed incorrectly               |
| `MB_CDC_STATUS_OUT_OF_RANGE` | Key or LED index is ≥ the device maximum            |

---

## Byte-Level Examples

All values are in hexadecimal. `AA` is always the SOF byte. CRC bytes are shown as `XX` for brevity — compute them per the [CRC algorithm](#crc-algorithm).

### Reading the debounce delay

```
Request:  AA 82 00 XX
           │  │  │  └─ CRC of (0x82, 0x00)
           │  │  └──── LEN = 0 (no data)
           │  └─────── CMD = GET_DEBOUNCE (0x82)
           └────────── SOF

Response: AA 82 00 01 32 XX
           │  │  │  │  │  └─ CRC
           │  │  │  │  └──── value = 0x32 = 50 ms
           │  │  │  └─────── LEN = 1
           │  │  └────────── STATUS = OK (0x00)
           │  └───────────── CMD echoed
           └──────────────── SOF
```

### Setting key index 0 to HID key code 0x04 ('A'), key_id 0

```
Request:  AA 89 08 00 00 04 00 00 00 00 00 XX
           │  │  │  └─────────────────────┘  └─ CRC
           │  │  │  data: index=0, key_id=0, keys=[0x04, 0x00, 0x00, 0x00, 0x00, 0x00]
           │  │  └──── LEN = 8
           │  └─────── CMD = SET_KEY (0x89)
           └────────── SOF

Response: AA 89 00 08 00 00 04 00 00 00 00 00 XX
                  │  │  └─────────────────────┘  └─ CRC
                  │  └──── LEN = 8
                  └─────── STATUS = OK (0x00)
```

### Getting LED 1 configuration

```
Request:  AA 8A 01 01 XX
           │  │  │  │  └─ CRC
           │  │  │  └──── index = 1
           │  │  └─────── LEN = 1
           │  └────────── CMD = GET_LED (0x8A)
           └───────────── SOF

Response: AA 8A 00 0D 01 01 00 FF 00 00 01 64 00 FF 00 00 00 XX
                     │  │  │  │  │  │  │  │──│  │  │  │  │  └─ CRC
                     │  │  │  │  │  │  │  │──│  │  │  │  └────── flags = 0 (no overlay)
                     │  │  │  │  │  │  │  │──│  │  │  └────────── cur_b = 0xFF (blue lit)
                     │  │  │  │  │  │  │  │──│  │  └──────────── cur_g = 0x00
                     │  │  │  │  │  │  │  │──│  └─────────────── cur_r = 0x00
                     │  │  │  │  │  │  │  └──┴─ duration_ms = 0x0064 = 100 ms (LE: lo=0x64, hi=0x00)
                     │  │  │  │  │  │  └─────── iterations = 1
                     │  │  │  │  │  └────────── b = 0x00 (target)
                     │  │  │  │  └───────────── g = 0x00 (target)
                     │  │  │  └────────────────  r = 0xFF (target)
                     │  │  └─────────────────── final = 0 (MB_LED_FINAL_OFF)
                     │  └────────────────────── mode = 1 (MB_LED_MODE_TRANSITION)
                     └───────────────────────── index = 1
           LEN = 0x0D = 13
```

### Temporarily disabling HID for a remapping session

```
Request:  AA 90 01 01 XX    ← SET_HID_DISABLE_TEMP, value=1 (disable)
Response: AA 90 00 01 01 XX ← STATUS=OK, value echoed

... perform remapping operations (SET_KEY, SAVE, etc.) ...

Request:  AA 90 01 00 XX    ← SET_HID_DISABLE_TEMP, value=0 (re-enable)
Response: AA 90 00 01 00 XX ← STATUS=OK, value echoed
```

### Saving configuration to flash

```
Request:  AA 91 00 XX          ← SAVE (no data)
Response: AA 91 00 01 01 XX    ← STATUS=OK, LEN=1, written=1 (configuration was written)

Or if the config hadn't changed:
Response: AA 91 00 01 00 XX    ← STATUS=OK, LEN=1, written=0 (no write needed)
```

The response may be delayed by up to ~400 ms while the flash sector erases. USB HID may stall during this time.

### Rebooting into USB boot mode

```
Request:  AA 93 01 01 XX    ← REBOOT, mode=1 (USB boot)
Response: AA 93 00 01 01 XX ← STATUS=OK, mode echoed; device enters UF2 bootloader shortly after
```
