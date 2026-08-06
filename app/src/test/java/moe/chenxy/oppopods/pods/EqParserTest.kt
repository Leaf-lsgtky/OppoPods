package moe.chenxy.oppopods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqParserTest {

    @Test
    fun `current EQ response returns selected protocol index`() {
        val packet = OppoPackets.buildPacketFixedSeq(
            Cmd.EQ_RESPONSE,
            payload = byteArrayOf(0x00, 0x07)
        )

        assertEquals(7, EqParser.parseCurrent(packet))
    }

    @Test
    fun `all EQ response returns custom name and selected state`() {
        val name = "My EQ".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(
            0x00, 0x01, // status, count
            0x01, 0xFA.toByte(), 0x06, 0x09, name.size.toByte(),
        ) + name + byteArrayOf(
            0x02, // frequency count
            0x3E, 0x00, 0x02, // 62 Hz, +2 dB
            0xFA.toByte(), 0x00, 0xFF.toByte(), // 250 Hz, -1 dB
        )
        val packet = OppoPackets.buildPacketFixedSeq(
            Cmd.QUERY_EQ_ALL or 0x8000,
            payload = payload
        )

        val entries = EqParser.parseAll(packet)
        assertEquals(1, entries.size)
        assertEquals(9, entries.single().id)
        assertEquals("My EQ", entries.single().name)
        assertTrue(entries.single().selected)
    }

    @Test
    fun `save EQ packet follows official detail payload`() {
        val packet = OppoPackets.buildSaveEqualizer(
            id = 0,
            name = "My EQ",
            frequencies = listOf(62, 250),
            gains = listOf(10, -9),
            minValue = -6,
            maxValue = 6,
        )

        assertEquals(Cmd.SET_EQ_DETAIL, commandOf(packet))
        assertArrayEquals(
            byteArrayOf(
                0x01, 0xFA.toByte(), 0x06, 0x00, 0x05,
                'M'.code.toByte(), 'y'.code.toByte(), ' '.code.toByte(),
                'E'.code.toByte(), 'Q'.code.toByte(),
                0x02,
                0x3E, 0x00, 0x06,
                0xFA.toByte(), 0x00, 0xFA.toByte(),
            ),
            packet.copyOfRange(9, packet.size),
        )
    }

    @Test
    fun `minimal delete EQ packet uses manager fallback payload`() {
        val packet = OppoPackets.buildDeleteEqualizer(9)

        assertEquals(Cmd.SET_EQ_DETAIL, commandOf(packet))
        assertArrayEquals(
            byteArrayOf(0x03, 0xFA.toByte(), 0x06, 0x09, 0x00),
            packet.copyOfRange(9, packet.size),
        )
    }

    @Test
    fun `empty all EQ response is recognized as a valid response`() {
        val packet = OppoPackets.buildPacketFixedSeq(
            Cmd.QUERY_EQ_ALL or 0x8000,
            payload = byteArrayOf(0x00, 0x00),
        )

        assertTrue(EqParser.isAllResponse(packet))
        assertTrue(EqParser.parseAll(packet).isEmpty())
    }

    private fun commandOf(packet: ByteArray): Int =
        (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
}
