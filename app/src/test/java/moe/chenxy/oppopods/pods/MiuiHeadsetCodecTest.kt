package moe.chenxy.oppopods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuiHeadsetCodecTest {
    private val manualLevels = listOf(NoiseLevel.LIGHT, NoiseLevel.MEDIUM, NoiseLevel.DEEP)

    @Test
    fun `mode translation follows semantics rather than button order`() {
        assertEquals(1, MiuiHeadsetCodec.oppoModeFromMiui(0))
        assertEquals(2, MiuiHeadsetCodec.oppoModeFromMiui(1))
        assertEquals(3, MiuiHeadsetCodec.oppoModeFromMiui(2))
        for (miuiMode in 0..2) {
            val oppoMode = MiuiHeadsetCodec.oppoModeFromMiui(miuiMode)!!
            assertEquals(miuiMode, MiuiHeadsetCodec.miuiModeFromOppo(oppoMode))
        }
    }

    @Test
    fun `unknown modes do not silently become off`() {
        for (mode in listOf(Int.MIN_VALUE, -1, 3, 4, Int.MAX_VALUE)) {
            assertNull(MiuiHeadsetCodec.oppoModeFromMiui(mode))
        }
        for (mode in listOf(Int.MIN_VALUE, -1, 0, 5, Int.MAX_VALUE)) {
            val state = MiuiHeadsetCodec.miuiAncState(mode, NoiseLevel.SMART)
            assertNull(state.miuiMode)
            assertEquals("-1", state.ancString)
            assertFalse(state.intelligentOn)
        }
    }

    @Test
    fun `adaptive and smart use noise cancellation plus separate intelligent state`() {
        val adaptive = MiuiHeadsetCodec.miuiAncState(4, NoiseLevel.DEEP)
        assertEquals(1, adaptive.miuiMode)
        assertEquals("0100", adaptive.ancString)
        assertTrue(adaptive.intelligentOn)
        val smart = MiuiHeadsetCodec.miuiAncState(2, NoiseLevel.SMART)
        assertEquals(1, smart.miuiMode)
        assertEquals("0100", smart.ancString)
        assertTrue(smart.intelligentOn)
        for (mode in listOf(1, 3)) {
            assertFalse(MiuiHeadsetCodec.miuiAncState(mode, NoiseLevel.SMART).intelligentOn)
        }
        for (level in manualLevels) {
            assertFalse(MiuiHeadsetCodec.miuiAncState(2, level).intelligentOn)
        }
    }

    @Test
    fun `ordinary ANC strings distinguish off transparency and manual anchors`() {
        assertEquals("0000", MiuiHeadsetCodec.miuiAncString(1, NoiseLevel.DEEP))
        assertEquals("0200", MiuiHeadsetCodec.miuiAncString(3, NoiseLevel.DEEP))
        assertEquals("0100", MiuiHeadsetCodec.miuiAncString(2, NoiseLevel.LIGHT))
        assertEquals("0109", MiuiHeadsetCodec.miuiAncString(2, NoiseLevel.MEDIUM))
        assertEquals("0113", MiuiHeadsetCodec.miuiAncString(2, NoiseLevel.DEEP))
        assertEquals("0100", MiuiHeadsetCodec.miuiAncString(2))
        assertEquals("0100", MiuiHeadsetCodec.miuiAncString(2, -1))
    }

    @Test
    fun `battery preserves every valid percent and charging bit on round trip`() {
        for (percent in 0..100) {
            for (charging in listOf(false, true)) {
                val encoded = MiuiHeadsetCodec.encodeBattery(percent, charging)
                assertEquals(percent, encoded and 0x7F)
                assertEquals(charging, encoded and 0x80 != 0)
                assertEquals(
                    MiuiHeadsetCodec.Battery(percent, charging),
                    MiuiHeadsetCodec.decodeBattery(encoded),
                )
            }
        }
        assertEquals(0, MiuiHeadsetCodec.encodeBattery(0))
        assertEquals(128, MiuiHeadsetCodec.encodeBattery(0, charging = true))
        assertEquals(228, MiuiHeadsetCodec.encodeBattery(100, charging = true))
    }

    @Test
    fun `missing disconnected and out of range batteries stay unknown`() {
        for (percent in listOf(null, Int.MIN_VALUE, -1, 101, 127, 128, 255, Int.MAX_VALUE)) {
            for (charging in listOf(false, true)) {
                assertEquals(255, MiuiHeadsetCodec.encodeBattery(percent, charging))
            }
        }
        assertEquals(255, MiuiHeadsetCodec.encodeBattery(50, charging = true, connected = false))
        for (encoded in listOf(Int.MIN_VALUE, -1, 101, 127, 229, 254, 255, 256, Int.MAX_VALUE)) {
            assertNull(MiuiHeadsetCodec.decodeBattery(encoded))
        }
    }

    @Test
    fun `all twenty manual strings round trip as hexadecimal`() {
        for (level in 0..19) {
            val encoded = MiuiHeadsetCodec.miuiManualAncString(level)!!
            assertEquals(4, encoded.length)
            assertEquals(level, MiuiHeadsetCodec.miuiManualLevel(encoded))
        }
        assertEquals("010A", MiuiHeadsetCodec.miuiManualAncString(10))
        assertEquals(10, MiuiHeadsetCodec.miuiManualLevel("010a"))
        assertEquals("0113", MiuiHeadsetCodec.miuiManualAncString(19))
        assertNull(MiuiHeadsetCodec.miuiManualAncString(-1))
        assertNull(MiuiHeadsetCodec.miuiManualAncString(20))
        assertNull(MiuiHeadsetCodec.miuiManualAncString(Int.MAX_VALUE))
    }

    @Test
    fun `manual decoder rejects unrelated states malformed values and out of range levels`() {
        for (encoded in listOf("", "-1", "0000", "0200", "010", "01000", "01+1", "01-1",
            "01 1", "01GG", "0114", "01FF", " 0100", "0100 ", "01０１")) {
            assertNull(encoded, MiuiHeadsetCodec.miuiManualLevel(encoded))
        }
    }

    @Test
    fun `manual quantization uses nearest light medium deep anchors with lighter ties`() {
        val expected = List(5) { NoiseLevel.LIGHT } + List(10) { NoiseLevel.MEDIUM } +
            List(5) { NoiseLevel.DEEP }
        for (level in 0..19) {
            assertEquals(
                "MIUI level $level",
                expected[level],
                MiuiHeadsetCodec.oppoNoiseLevelFromMiui(
                    MiuiHeadsetCodec.miuiManualAncString(level)!!,
                    manualLevels,
                ),
            )
        }
    }

    @Test
    fun `manual representative states round trip to their original OPPO level`() {
        for (level in manualLevels) {
            assertEquals(level, MiuiHeadsetCodec.oppoNoiseLevelFromMiui(
                MiuiHeadsetCodec.miuiAncString(2, level), manualLevels,
            ))
        }
    }

    @Test
    fun `all capability subsets return only supported levels and remain monotonic`() {
        for (mask in 0..7) {
            val supported = manualLevels.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            var previousRank = -1
            for (miuiLevel in 0..19) {
                val anc = MiuiHeadsetCodec.miuiManualAncString(miuiLevel)!!
                val mapped = MiuiHeadsetCodec.oppoNoiseLevelFromMiui(anc, supported)
                if (supported.isEmpty()) {
                    assertNull(mapped)
                } else {
                    val actual = requireNotNull(mapped)
                    assertTrue(actual in supported)
                    val rank = manualLevels.indexOf(actual)
                    assertTrue("subset $mask must not reverse at $miuiLevel", rank >= previousRank)
                    previousRank = rank
                    assertEquals(mapped, MiuiHeadsetCodec.oppoNoiseLevelFromMiui(anc, supported.reversed()))
                }
            }
        }
    }

    @Test
    fun `missing medium maps across supported endpoints and smart never participates`() {
        val endpoints = listOf(NoiseLevel.SMART, NoiseLevel.DEEP, NoiseLevel.LIGHT, NoiseLevel.LIGHT)
        assertEquals(NoiseLevel.LIGHT, MiuiHeadsetCodec.oppoNoiseLevelFromMiui("0109", endpoints))
        assertEquals(NoiseLevel.DEEP, MiuiHeadsetCodec.oppoNoiseLevelFromMiui("010A", endpoints))
        assertNull(MiuiHeadsetCodec.oppoNoiseLevelFromMiui("0100", listOf(NoiseLevel.SMART, -1)))
        assertNull(MiuiHeadsetCodec.oppoNoiseLevelFromMiui("0200", manualLevels))
    }

    @Test
    fun `manual command selection uses real profile keys only`() {
        val keys = setOf(ProfileKeys.SET_NOISE_LEVEL_LIGHT, ProfileKeys.SET_NOISE_LEVEL_DEEP,
            ProfileKeys.SET_NOISE_LEVEL_SMART, ProfileKeys.ANC_ADAPTIVE, ProfileKeys.ANC_NC)
        assertEquals(listOf(NoiseLevel.LIGHT, NoiseLevel.DEEP), MiuiHeadsetCodec.supportedManualNoiseLevels(keys))
        assertEquals(ProfileKeys.SET_NOISE_LEVEL_LIGHT, MiuiHeadsetCodec.manualAncCommandKey("0109", keys))
        assertEquals(ProfileKeys.SET_NOISE_LEVEL_DEEP, MiuiHeadsetCodec.manualAncCommandKey("010A", keys))
        assertNull(MiuiHeadsetCodec.manualAncCommandKey("0100", setOf(ProfileKeys.ANC_NC)))
        assertNull(MiuiHeadsetCodec.manualAncCommandKey("0114", keys))
        assertNull(MiuiHeadsetCodec.noiseLevelCommandKey(-1))
    }

    @Test
    fun `smart prefers its command and uses adaptive only when available and allowed`() {
        val both = setOf(ProfileKeys.SET_NOISE_LEVEL_SMART, ProfileKeys.ANC_ADAPTIVE)
        assertEquals(ProfileKeys.SET_NOISE_LEVEL_SMART, MiuiHeadsetCodec.smartAncCommandKey(both))
        assertEquals(ProfileKeys.SET_NOISE_LEVEL_SMART,
            MiuiHeadsetCodec.smartAncCommandKey(both, withAdaptiveFallback = false))
        assertEquals(ProfileKeys.ANC_ADAPTIVE,
            MiuiHeadsetCodec.smartAncCommandKey(setOf(ProfileKeys.ANC_ADAPTIVE)))
        assertNull(MiuiHeadsetCodec.smartAncCommandKey(
            setOf(ProfileKeys.ANC_ADAPTIVE), withAdaptiveFallback = false,
        ))
        assertNull(MiuiHeadsetCodec.smartAncCommandKey(setOf(ProfileKeys.ANC_NC, ProfileKeys.SET_NOISE_LEVEL_DEEP)))
        assertNull(MiuiHeadsetCodec.smartAncCommandKey(emptySet()))
    }

    @Test
    fun `ordinary status has exactly sixteen fields including trailing empty fields`() {
        val fields = MiuiHeadsetCodec.mainStatus(80, 168, 255, "0113").split(',')
        assertEquals(16, fields.size)
        assertEquals("80", fields[0])
        assertEquals("168", fields[1])
        assertEquals("255", fields[2])
        assertEquals("0113", fields[7])
        for (index in fields.indices.filter { it !in listOf(0, 1, 2, 7) }) {
            assertEquals("", fields[index])
        }
    }

    @Test
    fun `status template retains every unowned field without mutating caller state`() {
        val template = MutableList(16) { "field$it" }
        val original = template.toList()
        val fields = MiuiHeadsetCodec.mainStatus(0, 100, 228, "0200", template).split(',')
        assertEquals(original, template)
        for (index in fields.indices.filter { it !in listOf(0, 1, 2, 7) }) {
            assertEquals(original[index], fields[index])
        }
        assertEquals("-1", MiuiHeadsetCodec.mainStatus(255, 255, 255, "-1").split(',')[7])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `status rejects wrong template field count`() {
        MiuiHeadsetCodec.mainStatus(0, 0, 0, "0000", List(15) { "" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `status rejects embedded field delimiters`() {
        MiuiHeadsetCodec.mainStatus(0, 0, 0, "0000", List(16) { "unverified,value" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `status rejects malformed encoded battery values`() {
        MiuiHeadsetCodec.mainStatus(127, 0, 0, "0000")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `status rejects unrecognized ANC strings`() {
        MiuiHeadsetCodec.mainStatus(0, 0, 0, "0114")
    }
}
