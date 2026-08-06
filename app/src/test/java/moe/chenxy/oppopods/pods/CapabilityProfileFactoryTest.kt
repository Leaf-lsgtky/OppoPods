package moe.chenxy.oppopods.pods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验从官方机型白名单生成的 ANC payload 与本项目已在真机上验证过的硬编码值一致。
 *
 * 这是白名单接入的关键回归点：payload 不再手写，而是按 protocolIndex 位图算出来，
 * 一旦生成规则改错，所有机型会同时发错包，且症状是"降噪按了变通透"这类难查的问题。
 */
class CapabilityProfileFactoryTest {

    private val whiteList: List<JSONObject> by lazy {
        val file = File("src/main/assets/device_models.json")
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("whiteList")
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun entryById(id: String): JSONObject =
        whiteList.firstOrNull { it.optString("id") == id }
            ?: error("model $id missing from bundled whitelist")

    private fun profileOf(id: String): DeviceProfile =
        CapabilityProfileFactory.from(DeviceModelRegistry.parse(entryById(id), ""))

    private fun payloadOf(profile: DeviceProfile, key: String): String? =
        profile.commands[key]?.payload

    // ---------------------------------------------------------------- 位图

    @Test
    fun `anc payload sets the bit named by protocolIndex`() {
        assertEquals("01 01 01", CapabilityProfileFactory.ancPayload(0))
        assertEquals("01 01 02", CapabilityProfileFactory.ancPayload(1))
        assertEquals("01 01 04", CapabilityProfileFactory.ancPayload(2))
        assertEquals("01 01 80", CapabilityProfileFactory.ancPayload(7))
        // 跨字节：第 8 位起进入第二个 payload 字节
        assertEquals("01 01 00 01", CapabilityProfileFactory.ancPayload(8))
        assertEquals("01 01 00 08", CapabilityProfileFactory.ancPayload(11))
    }

    // ------------------------------------------------- 与既有种子配置对齐

    /** Enco X3 (067410) 的三个主档必须与此前硬编码的种子配置逐字节一致。 */
    @Test
    fun `enco X3 main modes match the hand-verified seed`() {
        val profile = profileOf("067410")
        assertEquals("01 01 01", payloadOf(profile, ProfileKeys.ANC_OFF))
        assertEquals("01 01 02", payloadOf(profile, ProfileKeys.ANC_NC))
        assertEquals("01 01 04", payloadOf(profile, ProfileKeys.ANC_TRANSPARENCY))
    }

    /** Enco Free4 (068C10) 含独立自适应档（protocolIndex 11）。 */
    @Test
    fun `enco Free4 main modes match the hand-verified seed`() {
        val profile = profileOf("068C10")
        assertEquals("01 01 01", payloadOf(profile, ProfileKeys.ANC_OFF))
        assertEquals("01 01 02", payloadOf(profile, ProfileKeys.ANC_NC))
        assertEquals("01 01 04", payloadOf(profile, ProfileKeys.ANC_TRANSPARENCY))
        assertEquals("01 01 00 08", payloadOf(profile, ProfileKeys.ANC_ADAPTIVE))
    }

    /** X2/X3 的自适应是通透档的子模式（idx9），不能生成顶层自适应开关。 */
    @Test
    fun `transparency adaptive child is not exposed as a main mode`() {
        for (id in listOf("063C10", "067410")) {
            val caps = DeviceModelRegistry.parse(entryById(id), "")
            val profile = CapabilityProfileFactory.from(caps)

            assertFalse("$id must not expose adaptive", caps.hasAdaptiveAnc)
            assertFalse("$id must not map adaptive as a command", caps.ancNameToIndex.containsKey(AncKeys.ADAPTIVE))
            assertEquals("$id adaptive child must belong to transparency", AncKeys.TRANSPARENCY, caps.ancIndexToName[9])
            assertFalse("$id must not generate adaptive payload", profile.commands.containsKey(ProfileKeys.ANC_ADAPTIVE))
        }
    }

    /** 通透子模式不能被误当成降噪等级，避免额外显示等级选择器。 */
    @Test
    fun `non noise child modes do not expose noise levels`() {
        val entry = JSONObject(
            """
            {
              "id":"synthetic",
              "name":"Synthetic",
              "function":{
                "noiseReductionMode":[
                  {"modeType":1,"protocolIndex":0},
                  {"modeType":2,"protocolIndex":2,
                   "childrenMode":[
                     {"modeType":2,"protocolIndex":8},
                     {"modeType":6,"protocolIndex":9}
                   ]}
                ]
              }
            }
            """.trimIndent()
        )

        assertFalse(DeviceModelRegistry.parse(entry, "").hasNoiseLevels)
    }

    /** Free4 的自适应位于顶层（idx11），应继续显示并生成独立命令。 */
    @Test
    fun `top level adaptive remains exposed`() {
        val caps = DeviceModelRegistry.parse(entryById("068C10"), "")

        assertTrue(caps.hasAdaptiveAnc)
        assertEquals(11, caps.ancNameToIndex[AncKeys.ADAPTIVE])
        assertTrue(CapabilityProfileFactory.from(caps).commands.containsKey(ProfileKeys.ANC_ADAPTIVE))
    }

    /** 降噪等级细分（智能/轻/中/深）来自子模式，全系一致。 */
    @Test
    fun `noise levels match the hand-verified seed`() {
        val profile = profileOf("067410")
        assertEquals("01 01 80", payloadOf(profile, ProfileKeys.SET_NOISE_LEVEL_SMART))
        assertEquals("01 01 40", payloadOf(profile, ProfileKeys.SET_NOISE_LEVEL_LIGHT))
        assertEquals("01 01 20", payloadOf(profile, ProfileKeys.SET_NOISE_LEVEL_MEDIUM))
        assertEquals("01 01 10", payloadOf(profile, ProfileKeys.SET_NOISE_LEVEL_DEEP))
    }

    /** 游戏模式 feature：Enco X3 用主开关 0x28，Free4 用低延迟 0x06。 */
    @Test
    fun `game mode feature id matches the hand-verified seeds`() {
        assertEquals("28 01", payloadOf(profileOf("067410"), ProfileKeys.GAME_ON))
        assertEquals("28 00", payloadOf(profileOf("067410"), ProfileKeys.GAME_OFF))
        assertEquals("06 01", payloadOf(profileOf("068C10"), ProfileKeys.GAME_ON))
        assertEquals("06 00", payloadOf(profileOf("068C10"), ProfileKeys.GAME_OFF))
    }

    /** 佩戴检测/双设备/空间音效均走 0x0403 通用开关，feature id 固定。 */
    @Test
    fun `feature switches use the documented feature ids`() {
        val profile = profileOf("068C10")
        assertEquals("04 01", payloadOf(profile, ProfileKeys.SET_AUTO_PLAY_PAUSE_ON))
        assertEquals("11 01", payloadOf(profile, ProfileKeys.SET_DUAL_DEVICE_ON))
        assertEquals("1B 01", payloadOf(profile, ProfileKeys.SPATIAL_SOUND_ON))
    }

    // ------------------------------------------------------ 反了的老机型

    /**
     * Enco Air4s 是 legacy 排布：NC 落在 idx0、关闭在 idx1，与现代机型对调。
     * 按索引生成能自动得到正确的位，这正是手写 payload 会翻车的地方。
     */
    @Test
    fun `legacy layout models get swapped bits automatically`() {
        val caps = DeviceModelRegistry.parse(entryById("06F010"), "")
        assertTrue("Air4s should be detected as legacy ANC layout", caps.isLegacyAnc)

        val profile = CapabilityProfileFactory.from(caps)
        // idx0=NC, idx1=Off, idx2=Transparency —— 与 Enco X3 的 Off/NC 正好相反
        assertEquals("01 01 01", payloadOf(profile, ProfileKeys.ANC_NC))
        assertEquals("01 01 02", payloadOf(profile, ProfileKeys.ANC_OFF))
        assertEquals("01 01 04", payloadOf(profile, ProfileKeys.ANC_TRANSPARENCY))
    }

    @Test
    fun `modern layout models are not marked legacy`() {
        assertFalse(DeviceModelRegistry.parse(entryById("067410"), "").isLegacyAnc)
        assertFalse(DeviceModelRegistry.parse(entryById("068C10"), "").isLegacyAnc)
    }

    /**
     * 索引表可用时不得再套用 legacy 交换，否则会二次翻转又变回反的。
     * 交换只是白名单未命中（索引表为空）时的静态兜底。
     */
    @Test
    fun `legacy swap does not double-correct when the index table is present`() {
        val caps = DeviceModelRegistry.parse(entryById("06F010"), "")
        val profile = CapabilityProfileFactory.from(caps)

        val packet = profile.commands.getValue(ProfileKeys.ANC_NC).toPacket().also {
            it[4] = (Cmd.ANC_MODE_RESPONSE and 0xFF).toByte()
            it[5] = ((Cmd.ANC_MODE_RESPONSE shr 8) and 0xFF).toByte()
        }

        val viaIndex = AncModeParser.parse(packet, caps.ancIndexToName, caps.isLegacyAnc)
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, viaIndex!!.mode)
    }

    /** 静态兜底路径：老机型的 NC/通透语义需要交换。 */
    @Test
    fun `static fallback swaps noise cancellation and transparency for legacy models`() {
        // 静态表里 0x10/0x00 表示降噪；legacy 机型上同样的字节实际是通透。
        val packet = byteArrayOf(
            0xAA.toByte(), 0x0B, 0x00, 0x00,
            (Cmd.ANC_MODE_RESPONSE and 0xFF).toByte(),
            ((Cmd.ANC_MODE_RESPONSE shr 8) and 0xFF).toByte(),
            0xF0.toByte(), 0x04, 0x00,
            0x01, 0x01, 0x10, 0x00,
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            AncModeParser.parse(packet, isLegacyAnc = false)!!.mode
        )
        assertEquals(
            NoiseControlMode.TRANSPARENCY,
            AncModeParser.parse(packet, isLegacyAnc = true)!!.mode
        )
    }

    /** 单字节位图（3 字节 payload）也必须能解析——低位模式没有第二个字节。 */
    @Test
    fun `parser accepts single byte bitmaps`() {
        val packet = byteArrayOf(
            0xAA.toByte(), 0x0A, 0x00, 0x00,
            (Cmd.ANC_MODE_RESPONSE and 0xFF).toByte(),
            ((Cmd.ANC_MODE_RESPONSE shr 8) and 0xFF).toByte(),
            0xF0.toByte(), 0x03, 0x00,
            0x01, 0x01, 0x02,
        )
        val parsed = AncModeParser.parse(packet, mapOf(1 to AncKeys.NC))
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, parsed!!.mode)
    }

    // ---------------------------------------------------------- 全量体检

    /** 全部带降噪数据的机型都应生成一致自洽的主档映射。 */
    @Test
    fun `every model with anc data produces distinct main mode bits`() {
        var checked = 0
        for (entry in whiteList) {
            val caps = DeviceModelRegistry.parse(entry, "")
            if (caps.ancOptions.isEmpty()) continue
            checked++

            val mainKeys = listOf(AncKeys.OFF, AncKeys.NC, AncKeys.TRANSPARENCY, AncKeys.ADAPTIVE)
                .filter { caps.ancNameToIndex.containsKey(it) }
            val indices = mainKeys.map { caps.ancNameToIndex.getValue(it) }
            assertEquals(
                "${caps.modelName} (${caps.modelId}) has duplicate main-mode indices: " +
                        mainKeys.zip(indices),
                indices.size,
                indices.toSet().size
            )
            assertTrue(
                "${caps.modelName} (${caps.modelId}) has no Off mode",
                caps.ancNameToIndex.containsKey(AncKeys.OFF)
            )
        }
        assertTrue("expected several models with ANC data, got $checked", checked >= 40)
    }

    /** 每个主档的位图都能被解析回同一个模式名，收发闭环。 */
    @Test
    fun `generated anc payloads round-trip through the parser`() {
        for (entry in whiteList) {
            val caps = DeviceModelRegistry.parse(entry, "")
            if (caps.ancOptions.isEmpty()) continue
            val profile = CapabilityProfileFactory.from(caps)

            for ((profileKey, expectedMode) in ROUND_TRIP_CASES) {
                val packet = profile.commands[profileKey]?.toPacket() ?: continue
                // 把设置包伪装成 0x810C 响应包再解析，验证位图与索引表自洽
                val response = packet.copyOf().also {
                    it[4] = (Cmd.ANC_MODE_RESPONSE and 0xFF).toByte()
                    it[5] = ((Cmd.ANC_MODE_RESPONSE shr 8) and 0xFF).toByte()
                }
                val parsed = AncModeParser.parse(response, caps.ancIndexToName, caps.isLegacyAnc)
                assertNotNull("${caps.modelName}: $profileKey did not parse back", parsed)
                assertEquals(
                    "${caps.modelName} (${caps.modelId}): $profileKey round-trip mismatch",
                    expectedMode,
                    parsed!!.mode
                )
            }
        }
    }

    private companion object {
        val ROUND_TRIP_CASES = listOf(
            ProfileKeys.ANC_OFF to NoiseControlMode.OFF,
            ProfileKeys.ANC_NC to NoiseControlMode.NOISE_CANCELLATION,
            ProfileKeys.ANC_TRANSPARENCY to NoiseControlMode.TRANSPARENCY,
            ProfileKeys.ANC_ADAPTIVE to NoiseControlMode.ADAPTIVE,
        )
    }
}
