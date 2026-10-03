package dev.hornetswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HornetProtocolTest {

    private fun hex(s: String): ByteArray = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    @Test
    fun selectPresetMatchesCapturedIampPackets() {
        val captured = mapOf(
            4 to "aa 55 02 00 98 04 c3 51",
            5 to "aa 55 02 00 98 05 d3 70",
            6 to "aa 55 02 00 98 06 e3 13",
            7 to "aa 55 02 00 98 07 f3 32",
            8 to "aa 55 02 00 98 08 02 dd",
            9 to "aa 55 02 00 98 09 12 fc",
            10 to "aa 55 02 00 98 0a 22 9f",
            11 to "aa 55 02 00 98 0b 32 be",
            39 to "aa 55 02 00 98 27 d7 50",
        )
        captured.forEach { (index, packet) ->
            assertEquals("index $index", packet, HornetProtocol.selectPreset(index).toHex())
        }
    }

    @Test
    fun otherCapturedCommandsMatch() {
        assertEquals("aa 55 01 00 94 0b f2", HornetProtocol.getCurrentPreset().toHex())
        assertEquals("aa 55 02 00 d6 00 ad 16", HornetProtocol.encode(0xD6, byteArrayOf(0)).toHex())
        assertEquals("aa 55 01 00 00 c8 cf", HornetProtocol.encode(0x00).toHex())
        assertEquals("aa 55 02 00 96 27 f4 5f", HornetProtocol.encode(0x96, byteArrayOf(39)).toHex())
        assertEquals(
            "aa 55 06 00 65 02 0f 00 0f 00 e3 7e",
            HornetProtocol.encode(0x65, hex("02 0f 00 0f 00")).toHex(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectPresetRejectsOutOfRange() {
        HornetProtocol.selectPreset(40)
    }

    @Test
    fun parsesAmpButtonNotification() {
        assertEquals(9, HornetProtocol.parseActivePreset(hex("aa 55 02 00 98 09 12 fc")))
    }

    @Test
    fun parsesCurrentPresetReply() {
        val firstChunk = hex("aa 55 02 01 a1 08 55 53 20 53 6f 6e 69 63 20 43 6c 65 61 6e 00")
        assertEquals(8, HornetProtocol.parseActivePreset(firstChunk))
    }

    @Test
    fun parsesBankStatus() {
        val frame = hex("aa 55 12 00 d2 02 00 01 03 04 08 09 0a 0b 00 00 00 00 00 01 02 03 76 e4")
        assertEquals(2, HornetProtocol.parseBankStatus(frame))
        assertNull(HornetProtocol.parseActivePreset(frame))
    }

    @Test
    fun ignoresHeartbeatAndContinuationChunks() {
        assertNull(HornetProtocol.parseActivePreset(hex("aa 55 03 00 bb 00 00 1f 23")))
        assertNull(HornetProtocol.parseBankStatus(hex("aa 55 03 00 bb 00 00 1f 23")))
        assertNull(HornetProtocol.parseActivePreset(hex("00 00 00 00 01 00 09 00 03 00")))
    }

    @Test
    fun readPresetMatchesCapturedPackets() {
        assertEquals("aa 55 02 00 96 06 c0 1c", HornetProtocol.readPreset(6).toHex())
        assertEquals("aa 55 02 00 96 27 f4 5f", HornetProtocol.readPreset(39).toHex())
    }

    @Test
    fun parsesPresetNames() {
        val readReply = hex("aa 55 02 01 97 06 42 72 69 74 38 30 30 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 02 00")
        assertEquals(6 to "Brit800", HornetProtocol.parsePresetName(readReply))
        assertNull(HornetProtocol.parseActivePreset(readReply))

        val activeReply = hex("aa 55 02 01 a1 05 42 61 73 73 4d 61 6e 20 42 72 65 61 6b 75 70 20 45 64 67 65 01 00")
        assertEquals(5 to "BassMan Breakup Edge", HornetProtocol.parsePresetName(activeReply))

        assertNull(HornetProtocol.parsePresetName(hex("aa 55 02 00 98 09 12 fc")))
    }

    @Test
    fun presetNames() {
        assertEquals("0A", HornetProtocol.presetName(0))
        assertEquals("1A", HornetProtocol.presetName(4))
        assertEquals("1C", HornetProtocol.presetName(6))
        assertEquals("2A", HornetProtocol.presetName(8))
        assertEquals("9D", HornetProtocol.presetName(39))
    }
}
