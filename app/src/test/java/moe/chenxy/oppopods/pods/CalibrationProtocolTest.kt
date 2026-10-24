package moe.chenxy.oppopods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贴合度 / 黄金听感的纯 JVM 协议回归。
 *
 * 只依赖 [OppoPackets] 的封包逻辑，不加载 Android 类，因此可在 `testDebugUnitTest` 直接跑。
 */
class CalibrationProtocolTest {

    private fun payloadOf(packet: ByteArray): ByteArray =
        CalibrationProtocol.payloadOf(packet) ?: error("invalid packet")

    @Test
    fun frequencyPayloadMatchesOfficialLayout() {
        val packet = CalibrationProtocol.buildFrequency(side = 1, type = 2, db = -19)
        assertEquals(CalibrationProtocol.CMD_HEARING_PARAMS, CalibrationProtocol.commandOf(packet))
        assertArrayEquals(
            byteArrayOf(0x03, 0x01, 0x01, 0x02, 0xED.toByte()),
            payloadOf(packet),
        )
    }

    @Test
    fun hearingSaveUsesBigEndianUidAndUtf8Name() {
        val levels = (1..2).flatMap { side ->
            (1..6).map { type -> HearingPoint(side, type, -19) }
        }
        val packet = CalibrationProtocol.buildHearingParameters(
            action = 3,
            levels = levels,
            uid = 0x01020304,
            name = "测试",
        )
        val payload = payloadOf(packet)
        assertEquals(3, payload[0].toInt())
        assertEquals(12, payload[1].toInt())
        assertEquals(0x01, payload[2 + 12 * 3].toInt())
        assertEquals(0x02, payload[3 + 12 * 3].toInt())
        assertEquals(0x03, payload[4 + 12 * 3].toInt())
        assertEquals(0x04, payload[5 + 12 * 3].toInt())
        assertEquals("测试", payload.copyOfRange(6 + 12 * 3, payload.size).toString(Charsets.UTF_8))
    }

    @Test
    fun earScanSaveUsesLittleEndianLength() {
        val scan = ByteArray(170) { it.toByte() }
        val payload = payloadOf(CalibrationProtocol.buildSaveEarScan(scan, 0x0A0B0C0D))
        assertEquals(0x03, payload[0].toInt())
        assertEquals(170, (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8))
        assertArrayEquals(scan, payload.copyOfRange(3, 3 + scan.size))
        assertEquals(0x0A, payload[3 + scan.size].toInt())
        assertEquals(0x0D, payload[6 + scan.size].toInt())
    }

    @Test
    fun fitEventParsesBothEarsAndRejectsUnknownStatus() {
        val packet = OppoPackets.buildPacket(
            0x0204,
            payload = byteArrayOf(0x04, 0x01, 0x01, 0x02, 0x06),
        )
        val result = CalibrationProtocol.parseFit(packet)
        assertNotNull(result)
        assertEquals(1, result!!.left)
        assertEquals(6, result.right)

        val invalid = OppoPackets.buildPacket(
            0x0204,
            payload = byteArrayOf(0x04, 0x01, 0x02, 0x02, 0x01),
        )
        assertNull(CalibrationProtocol.parseFit(invalid))
    }

    @Test
    fun wearResponseAndEventShareParser() {
        val response = OppoPackets.buildPacket(
            0x8109,
            payload = byteArrayOf(0x00, 0x02, 0x01, 0x02, 0x02, 0x02),
        )
        assertTrue(CalibrationProtocol.parseWear(response)!!.bothInEar)

        val event = OppoPackets.buildPacket(
            0x0204,
            payload = byteArrayOf(0x02, 0x02, 0x01, 0x02, 0x02, 0x00),
        )
        val wear = CalibrationProtocol.parseWearEvent(event)
        assertNotNull(wear)
        assertTrue(wear!!.leftInEar)
        assertEquals(false, wear.rightInEar)
    }

    @Test
    fun currentHearingRoundTrip() {
        val levels = (1..2).flatMap { side ->
            (1..6).map { type -> HearingPoint(side, type, -19 + type) }
        }
        val body = buildList {
            add(0x00)
            add(0x03)
            add(12)
            levels.forEach {
                add(it.side)
                add(it.type)
                add(it.db)
            }
            addAll(listOf(0x11, 0x22, 0x33, 0x44))
            addAll("我的黄金听感".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
        }.map { it.toByte() }.toByteArray()
        val packet = OppoPackets.buildPacket(0x8115, payload = body)

        val parsed = CalibrationProtocol.parseCurrentHearing(packet)
        assertNotNull(parsed)
        assertEquals(0x11223344, parsed!!.uid)
        assertEquals("我的黄金听感", parsed.name)
        assertEquals(levels, parsed.levels)
    }

    @Test
    fun earScanEventParsesActionBlobAndUid() {
        val scan = ByteArray(168) { (it * 3).toByte() }
        val body = buildList {
            add(0x03)
            add(scan.size and 0xFF)
            add((scan.size shr 8) and 0xFF)
            addAll(scan.map { it.toInt() and 0xFF })
            addAll(listOf(0x7F, 0x00, 0x00, 0x01))
        }.map { it.toByte() }.toByteArray()
        val packet = OppoPackets.buildPacket(0x0204, payload = byteArrayOf(0x0E) + body)

        val parsed = CalibrationProtocol.parseEarScanEvent(packet)
        assertNotNull(parsed)
        assertEquals(0x03, parsed!!.action)
        assertEquals(0x7F000001, parsed.uid)
        assertArrayEquals(scan, parsed.data)
    }

    @Test
    fun featureSwitchParsesStructuredAndFallbackLayouts() {
        val structured = OppoPackets.buildPacket(
            0x810D,
            payload = byteArrayOf(0x00, 0x02, 0x0B, 0x01, 0x04, 0x00),
        )
        assertEquals(true, CalibrationProtocol.parseFeatureSwitch(structured, 0x0B))
        assertEquals(false, CalibrationProtocol.parseFeatureSwitch(structured, 0x04))

        val flat = OppoPackets.buildPacket(
            0x810D,
            payload = byteArrayOf(0x00, 0x0B, 0x01),
        )
        assertEquals(true, CalibrationProtocol.parseFeatureSwitch(flat, 0x0B))
    }
}
