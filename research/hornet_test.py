"""Minimal Hornet 15i BLE test client: select presets by index without iAMP.

Usage: .venv/bin/python hornet_test.py 4 6 7 8
"""
import asyncio
import sys
import time

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"


def crc16_gsm(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc ^ 0xFFFF


def packet(cmd: int, payload: bytes = b"") -> bytes:
    body = (len(payload) + 1).to_bytes(2, "little") + bytes([cmd]) + payload
    return b"\xAA\x55" + body + crc16_gsm(body).to_bytes(2, "big")


def log(msg: str) -> None:
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {msg}", flush=True)


async def main(indices: list[int]) -> None:
    log("scanning...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: SERVICE_UUID in ad.service_uuids or "hornet" in (d.name or "").lower(),
        timeout=15,
    )
    if device is None:
        log("Hornet not found")
        return
    log(f"found {device.name} {device.address}")

    async with BleakClient(device) as client:
        log(f"connected, mtu={client.mtu_size}")
        await client.start_notify(NOTIFY_UUID, lambda _, data: log(f"RX {data.hex(' ')}"))
        log("notifications enabled")

        tx = packet(0x94)
        log(f"TX get-current {tx.hex(' ')}")
        await client.write_gatt_char(WRITE_UUID, tx, response=True)
        await asyncio.sleep(2)

        for index in indices:
            tx = packet(0x98, bytes([index]))
            log(f"TX select index={index} (bank {index // 4}{'ABCD'[index % 4]}) {tx.hex(' ')}")
            await client.write_gatt_char(WRITE_UUID, tx, response=True)
            await asyncio.sleep(4)

        tx = packet(0x94)
        log(f"TX get-current {tx.hex(' ')}")
        await client.write_gatt_char(WRITE_UUID, tx, response=True)
        await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main([int(x) for x in sys.argv[1:]]))
