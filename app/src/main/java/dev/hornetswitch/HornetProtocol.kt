package dev.hornetswitch

import java.util.UUID

/** MOOER Hornet 15i BLE frame codec. See PROTOCOL.md. */
object HornetProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val NOTIFY_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val WRITE_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val CMD_GET_CURRENT_PRESET = 0x94
    const val CMD_READ_PRESET = 0x96
    const val CMD_PRESET_INFO = 0x97
    const val CMD_SELECT_PRESET = 0x98
    const val CMD_PRESET_DATA = 0xA1
    const val CMD_BANK_STATUS = 0xD2

    private const val NAME_OFFSET = 6
    private const val NAME_LENGTH = 20

    const val BANK_COUNT = 10
    const val SLOT_COUNT = 4
    const val PRESET_COUNT = BANK_COUNT * SLOT_COUNT

    /**
     * CRC-16/GSM: poly 0x1021, init 0x0000, no reflection, xorout 0xFFFF.
     * @param data bytes to checksum
     * @return 16-bit checksum
     */
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc xor 0xFFFF
    }

    /**
     * Builds a frame: AA 55 | len u16 LE | cmd | payload | CRC16 BE over len..payload.
     * @param cmd command byte
     * @param payload command arguments
     * @return frame bytes
     */
    fun encode(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = payload.size + 1
        val body = byteArrayOf(length.toByte(), (length shr 8).toByte(), cmd.toByte()) + payload
        val crc = crc16(body)
        return byteArrayOf(0xAA.toByte(), 0x55) + body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    /**
     * @param index global preset index 0..39 (bank * 4 + slot)
     * @return select-preset frame
     */
    fun selectPreset(index: Int): ByteArray {
        require(index in 0 until PRESET_COUNT) { "preset index out of range: $index" }
        return encode(CMD_SELECT_PRESET, byteArrayOf(index.toByte()))
    }

    /** @return frame requesting the active preset (answered with CMD_PRESET_DATA) */
    fun getCurrentPreset(): ByteArray = encode(CMD_GET_CURRENT_PRESET)

    /**
     * @param index global preset index 0..39
     * @return frame requesting preset data (answered with CMD_PRESET_INFO)
     */
    fun readPreset(index: Int): ByteArray {
        require(index in 0 until PRESET_COUNT) { "preset index out of range: $index" }
        return encode(CMD_READ_PRESET, byteArrayOf(index.toByte()))
    }

    /**
     * Preset name from the first chunk of a 0x97 (read reply) or 0xA1 (active preset reply).
     * @param frame notification bytes
     * @return preset index to name, or null if the frame carries none
     */
    fun parsePresetName(frame: ByteArray): Pair<Int, String>? {
        val cmd = commandOf(frame) ?: return null
        if (cmd != CMD_PRESET_INFO && cmd != CMD_PRESET_DATA || frame.size < NAME_OFFSET + NAME_LENGTH) return null
        val index = frame[5].toInt() and 0xFF
        if (index >= PRESET_COUNT) return null
        val raw = frame.copyOfRange(NAME_OFFSET, NAME_OFFSET + NAME_LENGTH).takeWhile { it != 0.toByte() }.toByteArray()
        return index to String(raw, Charsets.UTF_8).trim()
    }

    /**
     * Active preset index reported by the amp: on its own button press (0x98) or as reply to 0x94 (0xA1).
     * @param frame notification bytes
     * @return preset index or null if the frame carries none
     */
    fun parseActivePreset(frame: ByteArray): Int? {
        val cmd = commandOf(frame) ?: return null
        if (cmd != CMD_SELECT_PRESET && cmd != CMD_PRESET_DATA) return null
        val index = frame[5].toInt() and 0xFF
        return index.takeIf { it < PRESET_COUNT }
    }

    /**
     * Bank status the amp sends after a preset change.
     * @param frame notification bytes
     * @return active bank or null if the frame is not a bank status
     */
    fun parseBankStatus(frame: ByteArray): Int? {
        if (commandOf(frame) != CMD_BANK_STATUS) return null
        return frame[5].toInt() and 0xFF
    }

    /**
     * @param index global preset index
     * @return iAMP-style name, e.g. 6 -> "1C"
     */
    fun presetName(index: Int): String = "${index / SLOT_COUNT}${"ABCD"[index % SLOT_COUNT]}"

    private fun commandOf(frame: ByteArray): Int? {
        if (frame.size < 6 || frame[0] != 0xAA.toByte() || frame[1] != 0x55.toByte()) return null
        return frame[4].toInt() and 0xFF
    }
}
