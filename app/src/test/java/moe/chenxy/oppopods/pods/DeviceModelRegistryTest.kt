package moe.chenxy.oppopods.pods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内嵌白名单本身的完整性，以及能力 → UI 显隐的推导规则。
 *
 * 这些断言的价值在于：白名单是外部数据，换一份新的云端配置进来时，
 * 这里会立刻告诉你哪些推导规则不再成立。
 */
class DeviceModelRegistryTest {

    private val whiteList: List<JSONObject> by lazy {
        val root = JSONObject(File("src/main/assets/device_models.json").readText())
        val array = root.getJSONArray("whiteList")
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun entryById(id: String): JSONObject =
        whiteList.firstOrNull { it.optString("id") == id }
            ?: error("model $id missing from bundled whitelist")

    private fun capsOf(id: String): DeviceCapabilities =
        DeviceModelRegistry.parse(entryById(id), "")

    // -------------------------------------------------------- 数据完整性

    @Test
    fun `bundled whitelist covers the expected fleet`() {
        assertTrue("expected 100+ models, got ${whiteList.size}", whiteList.size >= 100)
        // id 是 productId 主键，必须是 6 位大写 hex，否则 0x8103 精确匹配会失效
        val idPattern = Regex("^[0-9A-F]{6}$")
        val bad = whiteList.mapNotNull { entry ->
            val id = entry.optString("id")
            id.takeUnless { it.matches(idPattern) }
        }
        assertTrue("non-conforming productIds: $bad", bad.isEmpty())
    }

    @Test
    fun `every entry has a usable display name`() {
        val nameless = whiteList.filter { it.optString("name").isBlank() }
            .map { it.optString("id") }
        assertTrue("entries without a name: $nameless", nameless.isEmpty())
    }

    // ------------------------------------------------------------ 能力推导

    @Test
    fun `three mode spatial models expose spatial audio not the legacy switch`() {
        // Enco X3 的 spatialTypes 含 2（头部跟踪）→ 走 0x0422 三模式
        val caps = capsOf("067410")
        assertTrue(caps.spatialTypes.contains(SpatialAudioMode.HEAD_TRACKING))
        assertTrue(caps.hasSpatialAudio)
        assertFalse("three-mode models must not also show the on/off switch", caps.hasSpatialSound)
    }

    @Test
    fun `two mode spatial models expose the legacy switch only`() {
        // Free4 的 spatialTypes 是 [0,1]，没有头部跟踪 → 走 feature 0x1B 开关
        val caps = capsOf("068C10")
        assertFalse(caps.spatialTypes.contains(SpatialAudioMode.HEAD_TRACKING))
        assertFalse(caps.hasSpatialAudio)
        assertTrue(caps.hasSpatialSound)
    }

    @Test
    fun `spatial audio and spatial sound are mutually exclusive fleet wide`() {
        for (entry in whiteList) {
            val caps = DeviceModelRegistry.parse(entry, "")
            assertFalse(
                "${caps.modelName} (${caps.modelId}) claims both spatial protocols",
                caps.hasSpatialAudio && caps.hasSpatialSound
            )
        }
    }

    @Test
    fun `models without spatial types expose neither spatial feature`() {
        val caps = capsOf("063C10")  // Enco X2：白名单里没有 spatialTypes
        assertTrue(caps.spatialTypes.isEmpty())
        assertFalse(caps.hasSpatialAudio)
        assertFalse(caps.hasSpatialSound)
    }

    @Test
    fun `game mode falls back to supported when the whitelist is silent`() {
        // 137 条里仅 16 条声明游戏字段，而实测支持低延迟的 Free4 一条都没有，
        // 所以「未声明」必须按支持处理，真正的裁决交给 0x810D 探针。
        val free4 = entryById("068C10").getJSONObject("function")
        assertFalse("Free4 unexpectedly declares gameMode", free4.has("gameMode"))
        assertFalse("Free4 unexpectedly declares gameModeList", free4.has("gameModeList"))
        assertTrue(capsOf("068C10").hasGameMode)
    }

    @Test
    fun `game sound capable models use the main game switch`() {
        assertEquals(GameModeFeature.MAIN, capsOf("067410").gameModeFeatureId)
        assertEquals(GameModeFeature.LOW_LATENCY, capsOf("068C10").gameModeFeatureId)
    }

    @Test
    fun `visibility flags follow the declared capabilities`() {
        for (entry in whiteList) {
            val caps = DeviceModelRegistry.parse(entry, "")
            val profile = CapabilityProfileFactory.from(caps)

            assertEquals(
                "${caps.modelName}: autoPlayPause visibility must track wearDetection",
                caps.hasWearDetection, profile.autoPlayPauseVisible
            )
            assertEquals(
                "${caps.modelName}: dualDevice visibility must track multi-connect",
                caps.hasDualDevice, profile.dualDeviceVisible
            )
            // 显示了开关就必须有对应的指令，否则点了发空包
            if (profile.autoPlayPauseVisible) {
                assertTrue(
                    "${caps.modelName}: autoPlayPause visible but command missing",
                    profile.commands.containsKey(ProfileKeys.SET_AUTO_PLAY_PAUSE_ON)
                )
            }
            if (profile.dualDeviceVisible) {
                assertTrue(
                    "${caps.modelName}: dualDevice visible but command missing",
                    profile.commands.containsKey(ProfileKeys.SET_DUAL_DEVICE_ON)
                )
            }
            if (profile.spatialAudioVisible) {
                assertTrue(
                    "${caps.modelName}: spatial audio visible but command missing",
                    profile.commands.containsKey(ProfileKeys.SPATIAL_HEAD)
                )
            }
            if (profile.spatialSoundVisible) {
                assertTrue(
                    "${caps.modelName}: spatial sound visible but command missing",
                    profile.commands.containsKey(ProfileKeys.SPATIAL_SOUND_ON)
                )
            }
            if (profile.adaptiveVisible) {
                assertTrue(
                    "${caps.modelName}: adaptive visible but command missing",
                    profile.commands.containsKey(ProfileKeys.ANC_ADAPTIVE)
                )
            }
            if (profile.noiseLevelVisible) {
                assertTrue(
                    "${caps.modelName}: noise levels visible but no level command",
                    NOISE_LEVEL_KEYS.any { profile.commands.containsKey(it) }
                )
            }
        }
    }

    /** 每个型号都至少要能关降噪，否则 UI 上的「关闭」是个死键。 */
    @Test
    fun `models with anc always provide an off command`() {
        for (entry in whiteList) {
            val caps = DeviceModelRegistry.parse(entry, "")
            if (caps.ancOptions.isEmpty()) continue
            val profile = CapabilityProfileFactory.from(caps)
            assertTrue(
                "${caps.modelName} (${caps.modelId}) has ANC but no off command",
                profile.commands.containsKey(ProfileKeys.ANC_OFF)
            )
        }
    }

    @Test
    fun `eq presets come from the whitelist when declared`() {
        val caps = capsOf("067410")
        assertTrue("Enco X3 should expose built-in EQ presets", caps.eqPresets.isNotEmpty())
        // protocolIndex 是发送 0x0406 的实参，不能重复
        val ids = caps.eqPresets.map { it.id }
        assertEquals("duplicate EQ protocol indices: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `generated profile ids are stable and marked as generated`() {
        val profile = CapabilityProfileFactory.from(capsOf("067410"))
        assertEquals("auto_067410", profile.id)
        assertTrue(CapabilityProfileFactory.isGenerated(profile.id))
        assertEquals("067410", profile.modelId)
        assertFalse(CapabilityProfileFactory.isGenerated("fallback"))
    }

    // ------------------------------------------------------ productId 解析

    @Test
    fun `product id parses from the little endian payload`() {
        assertEquals("067410", productIdPacket(0x06, 0x74, 0x10))
        assertEquals("068C10", productIdPacket(0x06, 0x8C, 0x10))
        assertEquals("06F010", productIdPacket(0x06, 0xF0, 0x10))
    }

    @Test
    fun `product id rejects malformed payloads`() {
        // status 非 0 = 查询失败
        assertNull(ProductIdParser.parse(buildProductIdResponse(0x01, 0x10, 0x74, 0x06)))
        // 长度不足
        assertNull(
            ProductIdParser.parse(
                byteArrayOf(0xAA.toByte(), 0x09, 0, 0, 0x03, 0x81.toByte(), 0xF0.toByte(), 0x02, 0x00, 0x00, 0x10)
            )
        )
        // 命令号不匹配（电量响应）
        assertNull(
            ProductIdParser.parse(
                byteArrayOf(0xAA.toByte(), 0x0B, 0, 0, 0x06, 0x81.toByte(), 0xF0.toByte(), 0x04, 0x00, 0x00, 0x10, 0x74, 0x06)
            )
        )
    }

    /** 解析出来的 productId 必须真能在白名单里命中，否则自动识别形同虚设。 */
    @Test
    fun `parsed product ids resolve against the whitelist index`() {
        for (id in listOf("067410", "068C10", "06F010")) {
            val value = id.toInt(16)
            val parsed = productIdPacket(
                (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF
            )
            assertEquals(id, parsed)
            assertTrue(
                "whitelist has no entry for $id",
                whiteList.any { it.optString("id") == parsed }
            )
        }
    }

    private fun productIdPacket(high: Int, mid: Int, low: Int): String? =
        ProductIdParser.parse(buildProductIdResponse(0x00, low, mid, high))

    /** 组一个 0x8103 响应包：[status][productId 3 字节小端]。 */
    private fun buildProductIdResponse(status: Int, b0: Int, b1: Int, b2: Int): ByteArray =
        byteArrayOf(
            0xAA.toByte(), 0x0B, 0x00, 0x00,
            (Cmd.PRODUCT_ID_RESPONSE and 0xFF).toByte(),
            ((Cmd.PRODUCT_ID_RESPONSE shr 8) and 0xFF).toByte(),
            0xF0.toByte(), 0x04, 0x00,
            status.toByte(), b0.toByte(), b1.toByte(), b2.toByte(),
        )

    private companion object {
        val NOISE_LEVEL_KEYS = listOf(
            ProfileKeys.SET_NOISE_LEVEL_SMART,
            ProfileKeys.SET_NOISE_LEVEL_LIGHT,
            ProfileKeys.SET_NOISE_LEVEL_MEDIUM,
            ProfileKeys.SET_NOISE_LEVEL_DEEP,
        )
    }
}
