package moe.chenxy.oppopods.pods

import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import java.util.UUID

/**
 * 设备配置档的持久化与导入导出。
 * 所有档（含首次启动种入的 Enco X3）一视同仁地存在 prefs 的 [OppoPodsPrefsKey.DEVICE_PROFILES]
 * （JSON 数组），均可切换/删除；当前选中 id 存 [OppoPodsPrefsKey.ACTIVE_PROFILE_ID]。
 */
object DeviceProfileStore {
    private const val TAG = "OppoPods-ProfileStore"
    private const val SEED_ID = "default_enco_x3"
    private const val SEED_FREE4_ID = "default_enco_free4"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val listSerializer = ListSerializer(DeviceProfile.serializer())

    /** 内置种子档（OPPO Enco X3），仅在首次启动时种入，之后与普通档无异。 */
    private val SEED = DeviceProfile(
        id = SEED_ID,
        name = "OPPO Enco X3",
        adaptiveVisible = false,
        gameModeVisible = true,
        noiseLevelVisible = true,
        autoPlayPauseVisible = true,
        dualDeviceVisible = true,
        connectedDevicesVisible = true,
        commands = mapOf(
            ProfileKeys.ANC_OFF to PodCommand(Cmd.SET_ANC, payload = "01 01 01"),
            ProfileKeys.ANC_NC to PodCommand(Cmd.SET_ANC, payload = "01 01 02"),
            ProfileKeys.ANC_TRANSPARENCY to PodCommand(Cmd.SET_ANC, payload = "01 01 04"),
            ProfileKeys.ANC_ADAPTIVE to PodCommand(Cmd.SET_ANC, payload = "01 01 00 08"),
            ProfileKeys.GAME_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "28 01"),
            ProfileKeys.GAME_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "28 00"),
            ProfileKeys.SPATIAL_OFF to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "00"),
            ProfileKeys.SPATIAL_FIXED to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "01"),
            ProfileKeys.SPATIAL_HEAD to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "02"),
            ProfileKeys.QUERY_BATTERY to PodCommand(Cmd.QUERY_BATTERY, seq = 0xF0, payload = ""),
            ProfileKeys.QUERY_ANC to PodCommand(Cmd.QUERY_ANC_MODE, payload = "01 01"),
            ProfileKeys.QUERY_STATUS to PodCommand(
                Cmd.QUERY_STATUS, seq = 0x00,
                payload = "0B 05 04 0B 11 13 18 06 1B 1C 27 28"
            ),
            ProfileKeys.SET_NOISE_LEVEL_SMART to PodCommand(Cmd.SET_ANC, payload = "01 01 80"),
            ProfileKeys.SET_NOISE_LEVEL_LIGHT to PodCommand(Cmd.SET_ANC, payload = "01 01 40"),
            ProfileKeys.SET_NOISE_LEVEL_MEDIUM to PodCommand(Cmd.SET_ANC, payload = "01 01 20"),
            ProfileKeys.SET_NOISE_LEVEL_DEEP to PodCommand(Cmd.SET_ANC, payload = "01 01 10"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "04 01"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "04 00"),
            ProfileKeys.SET_DUAL_DEVICE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "11 01"),
            ProfileKeys.SET_DUAL_DEVICE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "11 00"),
        ),
        assets = mapOf(
            AssetKeys.HOME_IMAGE to "img_box.png",
            AssetKeys.ISLAND_LEFT to "img_left.png",
            AssetKeys.ISLAND_RIGHT to "img_right.png",
            AssetKeys.CONNECT_VIDEO to "boot_connected_state.mp4",
        ),
    )

    /** 内置种子档（OPPO Enco Free4），游戏模式使用低延迟 feature 0x06，自适应模式可见。 */
    private val SEED_FREE4 = DeviceProfile(
        id = SEED_FREE4_ID,
        name = "OPPO Enco Free4",
        adaptiveVisible = true,
        gameModeVisible = true,
        noiseLevelVisible = true,
        autoPlayPauseVisible = true,
        dualDeviceVisible = true,
        connectedDevicesVisible = true,
        commands = mapOf(
            ProfileKeys.ANC_OFF to PodCommand(Cmd.SET_ANC, payload = "01 01 01"),
            ProfileKeys.ANC_NC to PodCommand(Cmd.SET_ANC, payload = "01 01 02"),
            ProfileKeys.ANC_TRANSPARENCY to PodCommand(Cmd.SET_ANC, payload = "01 01 04"),
            ProfileKeys.ANC_ADAPTIVE to PodCommand(Cmd.SET_ANC, payload = "01 01 00 08"),
            ProfileKeys.GAME_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "06 01"),
            ProfileKeys.GAME_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "06 00"),
            ProfileKeys.SPATIAL_OFF to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "00"),
            ProfileKeys.SPATIAL_FIXED to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "01"),
            ProfileKeys.SPATIAL_HEAD to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "02"),
            ProfileKeys.QUERY_BATTERY to PodCommand(Cmd.QUERY_BATTERY, seq = 0xF0, payload = ""),
            ProfileKeys.QUERY_ANC to PodCommand(Cmd.QUERY_ANC_MODE, payload = "01 01"),
            ProfileKeys.QUERY_STATUS to PodCommand(
                Cmd.QUERY_STATUS, seq = 0x00,
                payload = "0B 05 04 0B 11 13 18 06 1B 1C 27 28"
            ),
            ProfileKeys.SET_NOISE_LEVEL_SMART to PodCommand(Cmd.SET_ANC, payload = "01 01 80"),
            ProfileKeys.SET_NOISE_LEVEL_LIGHT to PodCommand(Cmd.SET_ANC, payload = "01 01 40"),
            ProfileKeys.SET_NOISE_LEVEL_MEDIUM to PodCommand(Cmd.SET_ANC, payload = "01 01 20"),
            ProfileKeys.SET_NOISE_LEVEL_DEEP to PodCommand(Cmd.SET_ANC, payload = "01 01 10"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "04 01"),
            ProfileKeys.SET_AUTO_PLAY_PAUSE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "04 00"),
            ProfileKeys.SET_DUAL_DEVICE_ON to PodCommand(Cmd.SET_GAME_MODE, payload = "11 01"),
            ProfileKeys.SET_DUAL_DEVICE_OFF to PodCommand(Cmd.SET_GAME_MODE, payload = "11 00"),
        ),
        assets = mapOf(
            AssetKeys.HOME_IMAGE to "img_box.png",
            AssetKeys.ISLAND_LEFT to "img_left.png",
            AssetKeys.ISLAND_RIGHT to "img_right.png",
            AssetKeys.CONNECT_VIDEO to "boot_connected_state.mp4",
        ),
    )

    private fun loadStored(prefs: SharedPreferences): List<DeviceProfile> {
        val raw = prefs.getString(OppoPodsPrefsKey.DEVICE_PROFILES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(listSerializer, raw)
        }.onFailure { Log.w(TAG, "loadStored failed", it) }.getOrDefault(emptyList())
    }

    private fun saveStored(prefs: SharedPreferences, list: List<DeviceProfile>) {
        prefs.edit()
            .putString(OppoPodsPrefsKey.DEVICE_PROFILES, json.encodeToString(listSerializer, list))
            .apply()
    }

    /** 首次启动种入所有内置配置；后续启动补种缺失的内置配置。已删除的不会被重新种入。 */
    fun ensureSeeded(prefs: SharedPreferences) {
        val existing = loadStored(prefs)
        val existingIds = existing.map { it.id }.toSet()
        val seeds = listOf(SEED, SEED_FREE4)
        val missingSeeds = seeds.filter { it.id !in existingIds }
        if (missingSeeds.isNotEmpty()) {
            saveStored(prefs, existing + missingSeeds)
        }
        if (!prefs.contains(OppoPodsPrefsKey.ACTIVE_PROFILE_ID)) {
            setActive(prefs, SEED_ID)
        }
    }

    /** 恢复所有内置种子配置为代码中的最新定义，用户自定义配置保留。 */
    fun restoreSeeds(prefs: SharedPreferences) {
        val existing = loadStored(prefs)
        val seeds = listOf(SEED, SEED_FREE4)
        val seedIds = seeds.map { it.id }.toSet()
        val userProfiles = existing.filter { it.id !in seedIds }
        saveStored(prefs, seeds + userProfiles)
    }

    fun loadProfiles(prefs: SharedPreferences): List<DeviceProfile> = loadStored(prefs)

    fun activeId(prefs: SharedPreferences): String =
        prefs.getString(OppoPodsPrefsKey.ACTIVE_PROFILE_ID, null)
            ?: loadStored(prefs).firstOrNull()?.id
            ?: SEED_ID

    fun activeProfile(prefs: SharedPreferences): DeviceProfile {
        val list = loadStored(prefs)
        val id = activeId(prefs)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: SEED
    }

    fun setActive(prefs: SharedPreferences, id: String) {
        prefs.edit().putString(OppoPodsPrefsKey.ACTIVE_PROFILE_ID, id).apply()
    }

    /** 新增/替换一个档（同 id 覆盖）。 */
    fun addProfile(prefs: SharedPreferences, profile: DeviceProfile) {
        val list = loadStored(prefs).filter { it.id != profile.id } + profile
        saveStored(prefs, list)
    }

    /** 删除任意档；至少保留一个档；若删的是当前档则切到剩余首个。 */
    fun deleteProfile(prefs: SharedPreferences, id: String) {
        val list = loadStored(prefs)
        if (list.size <= 1) {
            Log.w(TAG, "Cannot delete the last profile")
            return
        }
        val wasActive = activeId(prefs) == id
        val rest = list.filter { it.id != id }
        saveStored(prefs, rest)
        if (wasActive) setActive(prefs, rest.first().id)
    }

    fun exportJson(profile: DeviceProfile): String =
        json.encodeToString(DeviceProfile.serializer(), profile)

    /** 解析单个配置 JSON（供跨进程接收使用）。 */
    fun parse(text: String): DeviceProfile =
        json.decodeFromString(DeviceProfile.serializer(), text)

    /** 解析导入的 JSON；id 为空则分配随机 id（同 id 视为更新覆盖）。 */
    fun importJson(text: String): DeviceProfile {
        val parsed = parse(text)
        val safeId = parsed.id.ifBlank { "profile_" + UUID.randomUUID().toString().take(8) }
        return parsed.copy(id = safeId)
    }
}
