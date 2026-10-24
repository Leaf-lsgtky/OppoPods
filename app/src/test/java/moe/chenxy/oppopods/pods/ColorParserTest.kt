package moe.chenxy.oppopods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorParserTest {

    @Test
    fun buildsColorQuery() {
        val packet = OppoPackets.buildQueryColor()
        assertEquals(0xAA.toByte(), packet[0])
        assertEquals(0x0B, packet[4].toInt() and 0xFF)
        assertEquals(0x01, packet[5].toInt() and 0xFF)
        assertEquals(0, packet[7].toInt() and 0xFF)
        assertEquals(0, packet[8].toInt() and 0xFF)
    }

    @Test
    fun parsesColorResponse() {
        val packet = byteArrayOf(
            0xAA.toByte(), 0x0B, 0x00, 0x00,
            0x0B, 0x81.toByte(), 0x01, 0x02, 0x00,
            0x00, 0x03,
        )
        assertEquals(3, ColorParser.parse(packet))
    }

    @Test
    fun rejectsFailedStatusAndWrongCommand() {
        val failed = byteArrayOf(
            0xAA.toByte(), 0x0B, 0x00, 0x00,
            0x0B, 0x81.toByte(), 0x01, 0x02, 0x00,
            0x01, 0x03,
        )
        assertNull(ColorParser.parse(failed))

        val wrongCommand = byteArrayOf(
            0xAA.toByte(), 0x0B, 0x00, 0x00,
            0x03, 0x81.toByte(), 0x01, 0x02, 0x00,
            0x00, 0x03,
        )
        assertNull(ColorParser.parse(wrongCommand))
    }
}
