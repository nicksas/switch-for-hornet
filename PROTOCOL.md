# MOOER Hornet 15i BLE Protocol

This document covers the part of the protocol needed for **selecting presets** and reading their names.
Every byte below was observed in traffic sent by the official MOOER iAMP app (v1.6.1) and then verified
on a real amplifier with independent clients (Python/bleak on macOS and the Switch for Hornet Android app),
without iAMP running.

> Unofficial, reverse-engineered for interoperability. Not affiliated with or endorsed by MOOER Audio.

## Device

| Parameter | Value |
|---|---|
| Model | MOOER Hornet 15i |
| Advertised BLE name | `DH15iHornet 15i` |
| BLE address | public, stable across power cycles |
| Firmware | V1.1.8 (iAMP reports product `DH15i`) |
| ATT MTU | phone requests 517, Hornet answers **508** |

## GATT service

Service `0000FFF0-0000-1000-8000-00805F9B34FB`, handles `0x0003`–`0x000B`.

## Characteristics

| UUID | Value handle | Properties | Purpose | Confidence |
|---|---|---|---|---|
| `FFF1` | `0x0005` | `0x18` (write, notify) | not used by iAMP | — |
| `FFF2` | `0x0008` | `0x12` (read, notify) | **Hornet → phone** notifications; CCCD `0x0009` | high |
| `FFF3` | `0x000B` | `0x08` (write with response) | **phone → Hornet** commands | high |

## Connection sequence

The minimal sequence, verified with Switch for Hornet (Android) and `research/hornet_test.py` (macOS/bleak):

1. Scan for a device whose name contains `Hornet` (on macOS the FFF0 service is also visible in the advertisement).
2. `connectGatt` (LE transport).
3. `requestMtu(517)` → 508 (optional, long replies then arrive in bigger chunks).
4. `discoverServices`.
5. Enable FFF2 notifications: write `01 00` to CCCD `0x0009`.
6. Optionally send `0x94` to learn the active preset.

**No handshake is required**: the select-preset command works right after subscribing to FFF2.

## Notification subscription

CCCD `0x0009` ← `0x0100`. After subscribing, the Hornet sends a heartbeat roughly once per second:
`AA 55 03 00 BB 00 00 1F 23`.

## Initialization / handshake

On connect iAMP additionally sends the following (not needed for switching; Switch for Hornet only uses `96` and `94`):

| Packet | Probable meaning |
|---|---|
| `AA 55 02 00 D6 00 AD 16` | mode / firmware version request (iAMP then logs "application mode" and "V1.1.8") |
| `AA 55 01 00 00 C8 CF` | unknown |
| `AA 55 02 00 96 <n> CRC` × 40 | read preset `n` = 0…39, answered with `97` (see Responses) — used for preset names |
| `AA 55 01 00 94 0B F2` | get active preset |
| `AA 55 06 00 65 02 01 00 0A 00 …` | request the list of "gnr files" 1…10 |
| `AA 55 01 00 D9 …`, `AA 55 02 00 D6 01 …`, `AA 55 01 00 C6 …` | unknown |

## Select-preset command

```
AA 55 02 00 98 <index> <crc_hi> <crc_lo>
```

`index` is the global preset number 0…39 (see [Preset numbering](#preset-numbering)). Write type: Write Request (with response).

After selecting, iAMP also sends `AA 55 06 00 65 02 <fid> 00 <fid> 00 CRC` with `fid = index + 11`, which requests
the preset's "gnr file" for its UI. **It is not needed for switching** (verified: without it the Hornet switches and acknowledges).

## Packet format

| Offset | Size | Meaning | Example | Confidence |
|---:|---:|---|---|---|
| 0 | 2 | Sync `AA 55` | `AA 55` | high |
| 2 | 2 | `len` = 1 + payload length, uint16 **little-endian** | `02 00` | high (seen with len 1, 2, 6, 0x0102) |
| 4 | 1 | Command | `98` | high |
| 5 | len−1 | Payload | `06` | high |
| 4+len | 2 | CRC-16, **big-endian** | `E3 13` | high |

Replies from the Hornet use the same format. Long replies (e.g. `A1`, len = 0x0102 = 258) arrive as several
consecutive notifications; only the first one carries the `AA 55` header.

## Preset numbering

40 presets: 10 banks × 4 slots. iAMP numbers banks **from zero**: `0A … 9D`.

```
index = bank * 4 + slot        bank 0..9, slot A=0 B=1 C=2 D=3
```

| Preset | Index | Name on the test unit | Verified by |
|---|---:|---|---|
| 0A | 0 | Plexi Crunch | `0x96` read |
| 1A | 4 | Twin Reverb Comp | iAMP + client without iAMP |
| 1B | 5 | BassMan Breakup Edge | iAMP |
| 1C | 6 | Brit800 | iAMP + client without iAMP |
| 1D | 7 | Brit900 | iAMP + client without iAMP |
| 2A | 8 | US Sonic Clean | iAMP + client without iAMP + `0x94` reply |
| 2B…2D | 9…11 | US Sonic Drive, Dual boost, Koche Lead | iAMP + amp's own preset button |
| 3B | 13 | Powebell Hi Gain | client without iAMP + `0x94` reply |
| 9D | 39 | Another Day | iAMP + `0x94` reply |

The amp's indicator colour matches the slot: **A blue, B green, C purple, D red**.

## Checksum / CRC

**CRC-16/GSM**: poly `0x1021`, init `0x0000`, refin/refout = false, xorout `0xFFFF`.
Computed over bytes from offset 2 (`len`) to the end of the payload, stored big-endian.

The parameters were found by brute force over 16 unique packets and then checked against **all 65** unique iAMP packets
(commands `00`, `65`, `94`, `96`, `98`, `C6`, `D6`, `D9`) and **all 17** unique complete Hornet replies
(`98`, `B0`, `B5`, `B7`, `BA`, `BB`, `C7`, `C9`, `D0`, `D2`, `D7`) — zero mismatches.
There is no sequence counter: sending the same preset twice produces identical bytes.

## Responses / acknowledgements

| Command | Hornet reply (FFF2) | Meaning |
|---|---|---|
| `98 <index>` (from phone) | `AA 55 12 00 D2 <bank> 00 01 03 04 08 09 0A 0B 00 … CRC` after ~70–130 ms | status after a preset change; `<bank>` is the active bank, the slot is not included |
| `94` | `AA 55 02 01 A1 <index> <name, 20 bytes ASCII> <parameters…>` (258 bytes, 4 notifications) | active preset |
| `96 <n>` | `AA 55 02 01 97 <n> <name, 20 bytes ASCII> <parameters…>` (258 bytes, 4 notifications) after ~90 ms | name and parameters of preset `n` |
| — (preset button pressed on the amp) | `AA 55 02 00 98 <index> CRC`, then `D2 <bank> …` | the amp reports the new preset by itself |
| — | `AA 55 03 00 BB 00 00 1F 23` every ~1 s | heartbeat |

Preset name (in `97` and `A1`): bytes 6…25, ASCII, zero-padded, at most 20 characters (`BassMan Breakup Edge` uses all 20).
Verified by reading all 40 presets.

After an `A1` reply the amp keeps sending status packets `B0`, `B7`, `D2`, `D0`, `BA`, `BB`, `C7`, `C9`, `D7`, `B5`
for about a second (not decoded). During that burst `96` gets no reply and `98` is processed with up to ~0.5–0.9 s delay,
so Switch for Hornet starts reading names 1.5 s after connecting.

Acknowledgement strategy in Switch for Hornet: after `98 <index>` wait for `D2` with `bank == index / 4`
(1.5 s timeout); the exact preset on connect and after a manual change on the amp comes from `A1` / `98`.

## Captured examples

iAMP traffic captured from its own debug log, session 2026-10-03:

| Preset | Packet 1 (select) | Packet 2 (gnr, not needed) | Reply |
|---|---|---|---|
| 1A | `AA 55 02 00 98 04 C3 51` | `AA 55 06 00 65 02 0F 00 0F 00 E3 7E` | `D2 01` |
| 1B | `AA 55 02 00 98 05 D3 70` | `AA 55 06 00 65 02 10 00 10 00 3F 7A` | `D2 01` |
| 1C | `AA 55 02 00 98 06 E3 13` | `AA 55 06 00 65 02 11 00 11 00 7A FF` | `D2 01` |
| 1D | `AA 55 02 00 98 07 F3 32` | `AA 55 06 00 65 02 12 00 12 00 B4 70` | `D2 01` |
| 2A | `AA 55 02 00 98 08 02 DD` | `AA 55 06 00 65 02 13 00 13 00 F1 F5` | `D2 02` |
| 2B | `AA 55 02 00 98 09 12 FC` | `AA 55 06 00 65 02 14 00 14 00 39 4F` | `D2 02` |
| 2C | `AA 55 02 00 98 0A 22 9F` | `AA 55 06 00 65 02 15 00 15 00 7C CA` | `D2 02` |
| 2D | `AA 55 02 00 98 0B 32 BE` | `AA 55 06 00 65 02 16 00 16 00 B2 45` | `D2 02` |
| 9D | `AA 55 02 00 98 27 D7 50` | `AA 55 06 00 65 02 32 00 32 00 85 D8` | — |

Raw logs: `research/iamp_tx_capture.txt` (every command iAMP sent), `research/bleak_test1.txt`, `research/bleak_listen1.txt`,
`research/bleak_read_names.txt`.

## Error behavior

- While iAMP is connected the Hornet **stops advertising**, so a second client cannot find it.
  Two simultaneous clients were not tested and are not supported.
- When the amp is powered off, the phone detects the loss after ~5 s (`status 8`, supervision timeout).
- Right after power-on the Hornet drops the first connection once (~8 s after connecting) and does not answer `0x94`;
  the next connection is stable.
- No error replies (NACK) were seen for the commands above. Unknown commands were never sent (no fuzzing).

## Unknown fields

- Bytes 6…21 of the `D2` reply (`00 01 03 04 08 09 0A 0B 00 … 01 02 03`).
- The parameter block after the name in `A1` / `97` (looks like 7 effect blocks of 12 values plus the chain order).
- Commands `00`, `C6`, `D6`, `D9`, `65` and status packets `B0`, `B5`, `B7`, `BA`, `C7`, `C9`, `D0`, `D7`.
- Characteristic FFF1.

## Confidence / limitations

- Frame format, CRC, command `98`, numbering 0…39, replies `98` / `A1` / `97` / `D2` — **high** (verified live).
- Verified on one Hornet 15i unit with firmware V1.1.8. Other firmware versions and models (Hornet 05i/30i etc.) are untested.
- iAMP is a Flutter app; its BLE layer uses the `flutter_blue_plus` plugin. iAMP's protocol code was not decompiled:
  the bytes come from iAMP's own debug output and from live verification.

## Method

1. An Android bug report gave the GATT table, handles, write lengths and write type. HCI snoop logs on the test phone
   contained only an empty header, so no payloads could be taken from them.
2. The installed iAMP app logs every command it writes (as a byte array) and its decoded replies to the system log —
   this was the main source of bytes.
3. Independent verification: `research/hornet_test.py` (Python + bleak, macOS) and the Switch for Hornet Android app.

## Existing public research

Searched on 2026-10-03 ("MOOER Hornet 15i bluetooth protocol", "mooer iamp BLE protocol github",
"github mooer bluetooth reverse engineering"):

| Link | What it is | Relevant to Hornet 15i | Used |
|---|---|---|---|
| [mooeraudio.com — Hornet 15i](https://www.mooeraudio.com/products/190.html) | product specs: Bluetooth 5.0, control via iAMP | yes, no protocol details | no |
| [Manuals+ — Hornet 15i manual](https://manuals.plus/mooer/hornet-15i-watt-combo-manual) | user manual, no protocol details | yes, no protocol details | no |
| [ThijsWithaar/MooerManager](https://github.com/ThijsWithaar/MooerManager) | controls the Mooer GE-200 over **USB** | no (different device and transport) | no |
| [sidekickDan/mooerMoConvert](https://github.com/sidekickDan/mooerMoConvert) | GE200 → GE150 preset file converter | no | no |

No public description of the Hornet 15i / iAMP BLE protocol was found.
