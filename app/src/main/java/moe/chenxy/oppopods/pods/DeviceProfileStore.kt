package moe.chenxy.oppopods.pods

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey

/**
 * 配置档的来源模式。
 *
 * 默认 [AUTO]：连接后向耳机查询 productId（0x0103），在内嵌的官方机型白名单里
 * 精确命中并即时生成配置，无需用户逐项填写。
 */
enum class ProfileMode(val preferenceValue: String) {
    /** 自动识别（productId 优先，蓝牙名回退）。 */
    AUTO("auto"),

    /** 手动指定白名单中的某个型号。 */
    MODEL("model");

    companion object {
        val DEFAULT = AUTO

        fun fromPreference(value: String?): ProfileMode =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}

/**
 * 配置档的来源解析。
 *
 * 配置不再由用户手写——机型能力全部来自内嵌白名单，由 [CapabilityProfileFactory]
 * 按 protocolIndex 生成指令。这里只负责「用哪个型号」这一个决定：自动识别，
 * 或用户手动指定。耳机素材（图片/视频）与配置无关，见 [PodImageStore]。
 */
object DeviceProfileStore {
    private const val TAG = "OppoPods-ProfileStore"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** 白名单不可用时的兜底配置：只保留全系通用的查询与 ANC 主档。 */
    private val FALLBACK = DeviceProfile(
        id = "fallback",
        name = "Unknown",
        gameModeVisible = true,
        noiseLevelVisible = true,
        autoPlayPauseVisible = true,
        dualDeviceVisible = true,
        connectedDevicesVisible = true,
        commands = mapOf(
            ProfileKeys.ANC_OFF to PodCommand(Cmd.SET_ANC, payload = "01 01 01"),
            ProfileKeys.ANC_NC to PodCommand(Cmd.SET_ANC, payload = "01 01 02"),
            ProfileKeys.ANC_TRANSPARENCY to PodCommand(Cmd.SET_ANC, payload = "01 01 04"),
            ProfileKeys.GAME_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "28 01"),
            ProfileKeys.GAME_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "28 00"),
            ProfileKeys.SET_NOISE_LEVEL_SMART to PodCommand(Cmd.SET_ANC, payload = "01 01 80"),
            ProfileKeys.SET_NOISE_LEVEL_LIGHT to PodCommand(Cmd.SET_ANC, payload = "01 01 40"),
            ProfileKeys.SET_NOISE_LEVEL_MEDIUM to PodCommand(Cmd.SET_ANC, payload = "01 01 20"),
            ProfileKeys.SET_NOISE_LEVEL_DEEP to PodCommand(Cmd.SET_ANC, payload = "01 01 10"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "04 01"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "04 00"),
            ProfileKeys.SET_DUAL_DEVICE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "11 01"),
            ProfileKeys.SET_DUAL_DEVICE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "11 00"),
            ProfileKeys.QUERY_BATTERY to PodCommand(Cmd.QUERY_BATTERY, seq = 0xF0, payload = ""),
            ProfileKeys.QUERY_ANC to PodCommand(Cmd.QUERY_ANC_MODE, payload = "01 01"),
            ProfileKeys.QUERY_EQ to PodCommand(Cmd.QUERY_EQ, payload = ""),
            ProfileKeys.QUERY_STATUS to PodCommand(
                Cmd.QUERY_STATUS, seq = 0x00,
                payload = "0B 05 04 0B 11 13 18 06 1B 1C 27 28"
            ),
        ),
    )

    /** 白名单未命中时使用的配置。 */
    fun fallbackProfile(): DeviceProfile = FALLBACK

    fun exportJson(profile: DeviceProfile): String =
        json.encodeToString(DeviceProfile.serializer(), profile)

    /** 跨进程广播设备端 EQ 详情，避免 Intent 逐项传递可变频段数组。 */
    fun exportEqEntries(entries: List<EqDevicePreset>): String =
        json.encodeToString(ListSerializer(EqDevicePreset.serializer()), entries)

    fun parseEqEntries(text: String?): List<EqDevicePreset> =
        text?.let {
            runCatching {
                json.decodeFromString(ListSerializer(EqDevicePreset.serializer()), it)
            }.getOrDefault(emptyList())
        } ?: emptyList()

    // ------------------------------------------------------- 配置来源模式

    /** 当前配置来源模式。 */
    fun profileMode(prefs: SharedPreferences): ProfileMode =
        ProfileMode.fromPreference(prefs.getString(OppoPodsPrefsKey.PROFILE_MODE, null))

    fun setProfileMode(prefs: SharedPreferences, mode: ProfileMode) {
        prefs.edit().putString(OppoPodsPrefsKey.PROFILE_MODE, mode.preferenceValue).apply()
    }

    /** 手动模式下选中的白名单 productId；未选则为 null。 */
    fun selectedModelId(prefs: SharedPreferences): String? =
        prefs.getString(OppoPodsPrefsKey.SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun setSelectedModelId(prefs: SharedPreferences, modelId: String) {
        prefs.edit().putString(OppoPodsPrefsKey.SELECTED_MODEL_ID, modelId).apply()
    }

    /**
     * 按当前模式解析出应使用的配置档：
     * - [ProfileMode.MODEL]：手动指定的白名单型号
     * - [ProfileMode.AUTO]：先用蓝牙名预判；精确识别在收到 0x8103 后由控制器完成
     *
     * 任一路径失败都回落到 [fallbackProfile]，保证永远有可用配置。
     */
    fun resolveProfile(
        context: Context,
        prefs: SharedPreferences,
        deviceName: String? = null,
    ): DeviceProfile {
        val caps = when (profileMode(prefs)) {
            ProfileMode.MODEL -> selectedModelId(prefs)
                ?.let { DeviceModelRegistry.byProductId(context, it) }
            ProfileMode.AUTO -> DeviceModelRegistry.byDeviceName(context, deviceName)
                ?.also { Log.d(TAG, "auto-matched by name '$deviceName' -> ${it.modelName}") }
        } ?: return FALLBACK
        return CapabilityProfileFactory.from(caps)
    }

    /**
     * 收到 0x8103 后按 productId 精确重建配置。仅在 [ProfileMode.AUTO] 下生效——
     * 用户显式指定型号时不应被设备上报覆盖。返回 null 表示不需要切换。
     */
    fun profileForProductId(
        context: Context,
        prefs: SharedPreferences,
        productId: String,
    ): DeviceProfile? {
        if (profileMode(prefs) != ProfileMode.AUTO) return null
        val caps = DeviceModelRegistry.byProductId(context, productId) ?: return null
        return CapabilityProfileFactory.from(caps)
    }

    /** 解析单个配置 JSON（供跨进程接收使用）。 */
    fun parse(text: String): DeviceProfile =
        json.decodeFromString(DeviceProfile.serializer(), text)
}
