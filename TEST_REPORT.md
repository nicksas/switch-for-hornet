# Test report — Switch for Hornet

## Environment

| Parameter | Value |
|---|---|
| Date | 2026-10-03 |
| Phone | OPPO Find X9 Pro (CPH2791) |
| Android / ColorOS | Android 16 (SDK 36), ColorOS 16.1 |
| Amplifier | MOOER Hornet 15i, firmware V1.1.8, BLE name `DH15iHornet 15i` |
| App | Switch for Hornet 0.1.0 (versionCode 1), debug build |
| Desktop client | Python 3 + bleak, `research/hornet_test.py` |

Latencies are taken from the app's own diagnostic log, not estimated by eye:
`touch->ack` is the time from the screen tap to the Hornet's `D2` notification; `touch->tx` was 1–3 ms in every case.
`D2` confirms the bank; the exact preset was checked against the amp's indicator colour and the `0x94` reply.

## Protocol checks (without iAMP)

| Test | How | Result |
|---|---|---|
| P1 repeatability | iAMP 1C/1D/1C/1D and the app 59× 1C/1D | the same preset always produces identical bytes, no counter ✅ |
| P2 neighbouring slots 1A–1D | iAMP capture + desktop client 1A→1C→1D | index 4…7 ✅ |
| P3 bank boundary 1D→2A | desktop client: `98 07` → `98 08`, replies `D2 01` → `D2 02`, then `0x94` returned `A1 08 "US Sonic Clean"` | ✅ |
| P4 bank 2 (2A–2D) | iAMP capture (indices 8–11) + the amp's own button produced `98 09/0A/0B/08` with green/purple/red/blue | ✅ |
| P5 distant presets | 9D = index 39 (iAMP + `0x94` reply "Another Day"); 3B = 13 and 2D = 11 sent by the app | ✅ (there is no bank 10: banks are 0…9) |
| Without iAMP | desktop client switched 1A→1C→1D→2A; colour change confirmed on the amp | ✅ |

## App checks

| Test | Presets | Attempts | OK | Failed | Latency touch→ack | Notes |
|---|---|---:|---:|---:|---|---|
| A first connection | — | 1 | 1 | 0 | start→READY 1.8 s | permission requested, Hornet found automatically; the first build got stuck in SCANNING — fixed (see below) |
| B two presets | 1C ⇄ 1D | 59 | 59 | 0 | median 98 ms, max 128 ms | strict 1C/1D alternation, no stray presets |
| C across banks | 1C ⇄ 2D, then 1C ⇄ 3B | 19 | 19 | 0 | median ~103 ms, max 134 ms | the 1D→2A boundary is also covered by P3 |
| D amp power cycle | 1C ⇄ 3B | 1 | 1 | 0 | — | loss detected after ~5 s, automatic reconnect after power-on; the Hornet drops the first connection once while booting, the second attempt is stable; READY ~13 s after power-on |
| E Bluetooth off/on | 1C ⇄ 3B | 1 (+7 taps) | 1 | 0 | first tap 902 ms, then ~97 ms | "Bluetooth off" shown immediately; READY 1.6 s after Bluetooth is back, no user action |
| F background/foreground | 1C ⇄ 3B | 1 (+10 taps) | 1 | 0 | median 98 ms | connection kept in background, status correct |
| G conflict with iAMP | 1C ⇄ 3B | 1 | 1 | 0 | first tap 320 ms | while iAMP is connected: "Hornet not found… close MOOER iAMP", retries every ~13 s without hanging; READY 1.4 s after closing iAMP, active preset (1D) read from the amp |
| H fast taps | 1C ⇄ 1D | burst of ~10 | — | 0 | — | no queue build-up: taps while waiting for an ack or within 300 ms are ignored; minimum interval between commands 314 ms |
| I latency | all | 100 | 100 | 0 | **median 100 ms, max 902 ms** | max is the first tap right after reconnecting; steady state max 134 ms |
| J setup change | Metal: 3C → 3D | 1 | 1 | 0 | — | choosing another setup in Settings switches the amp to its first preset on return |
| K preset names | all 40 | 40 | 40 | 0 | ~3.5 s for all | read in the background after connecting, switching keeps working meanwhile (70–118 ms) |
| L forget amp (after hardening) | — | 1 | 1 | 0 | — | connection dropped, nearest Hornet found and remembered only after it answered `0x94`; all 40 names re-read |
| M amp power cycle (after hardening) | 9A ⇄ 2C | 1 (+4 taps) | 1 | 0 | 94–106 ms | while booting the amp accepted the connection but never confirmed a write; the new write watchdog dropped it after 4.5 s and the next attempt was READY with the active preset read |
| N setup switching (after hardening) | 9A ⇄ 2C, 9A ⇄ 9A | 3 | 3 | 0 | ~100 ms | switching setups jumps to the first preset only when it is not already active; cycling continues correctly |

Total: **100 of 100** commands acknowledged by the amp, no timeouts or write errors.

## Defects found and fixed during testing

1. **Endless scanning.** Every advertisement from the Hornet postponed the end of the scan, so the status stayed SCANNING. Fixed: the timer is only rescheduled for a newly found device.
2. **Connection never completed.** Inside the anonymous `BluetoothGattCallback`, calling `onConnectionStateChange(...)` resolved to the callback itself instead of the client's method, creating an endless loop through `handler.post`. Fixed by renaming the client handlers to `handle*`.
3. **Duplicate main screen.** Launching the app while it was already open could create a second main screen with its own BLE client that could not find the (already connected) amp. Fixed with `launchMode="singleTask"`.

## Hardening after code review

Five independent reviews (two Claude, three Codex) were run before the public release. Fixes applied:

- A saved amp is never silently replaced by another Hornet; a new amp is remembered only after it answers `0x94`; devices are recognised by name, not by the generic `FFF0` UUID.
- One watchdog covers the whole connect → ready sequence; `requestMtu` / notification setup results are checked; a lost write callback or a missing heartbeat drops and re-establishes the connection.
- Commands tapped during the amp's post-`A1` status burst are delayed until it ends; a press of the amp's own button cancels a pending request; an unconfirmed switch is re-synchronised with `0x94`.
- Reconnects back off exponentially (up to 30 s) to avoid Android scan throttling.
- Preset names are cleared when the amp is forgotten; failed name reads are retried.
- Fixed a crash when Bluetooth was off and the permission had been denied; permission denial no longer jumps to system settings by itself.
- The pedal screen is no longer recreated (and disconnected) by theme / font / display changes.
- Corrupted saved setups fall back to defaults instead of crashing; exported logs are size-limited.
- Setup editor: a cancelled gesture no longer deletes a preset; previews tapped while the amp is busy are sent once it is free.
- Release builds without the private key are produced unsigned instead of debug-signed.

## Not tested / limitations

- Screen off / locked and sessions longer than ~10 minutes.
- One Hornet 15i unit, one firmware version, one phone model.
