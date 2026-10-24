package moe.chenxy.oppopods.pods

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机保存的一份黄金听感方案。
 *
 * 欢律的做法是「耳机里只保留当前一份，历史方案存在手机侧」，主界面的「听感配置」
 * 列表就是这份本地历史 + 耳机当前方案。切换时把历史里的测听数据重新写回耳机，
 * 而不是让耳机自己存多份。
 */
@Serializable
data class HearingProfile(
    val uid: Int,
    val name: String,
    val localIndex: Int = 1,
    val version: Int = 2,
    /** 12 个测听点，重新下发时原样写回。 */
    val levels: List<HearingPoint> = emptyList(),
    /** 耳道扫描 blob（0..255）；不支持的机型为空。 */
    val scan: List<Int> = emptyList(),
    /** 0x0411 的方案描述编号 1..20。 */
    val descriptionId: Int = 1,
    val savedAt: Long = 0L,
) {
    /** 能否重新写回耳机：测听点必须完整；需要耳扫的机型还必须有扫描数据。 */
    fun canApply(requiresEarScan: Boolean): Boolean =
        levels.size == CalibrationState.HEARING_STEPS && (!requiresEarScan || scan.isNotEmpty())

    fun scanBytes(): ByteArray = ByteArray(scan.size) { scan[it].toByte() }
}

/**
 * 按设备分别保存黄金听感历史，最多保留 [MAX_PROFILES] 份（与官方一致）。
 *
 * 存在应用私有目录下的单个 JSON 文件里；设备键用蓝牙地址，未连接时退回型号 id，
 * 避免换耳机后历史串台。
 */
object HearingProfileStore {
    private const val MAX_PROFILES = 10
    private const val FILE_NAME = "hearing_profiles.json"

    @Serializable
    private data class Store(val devices: Map<String, List<HearingProfile>> = emptyMap())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    fun nextLocalIndex(context: Context, deviceKey: String): Int = synchronized(lock) {
        if (deviceKey.isBlank()) return 1
        val existing = read(context).devices[deviceKey].orEmpty()
        val used = existing.map { it.localIndex }.toSet()
        generateSequence(1) { it + 1 }.first { it !in used }
    }

    fun load(context: Context, deviceKey: String): List<HearingProfile> = synchronized(lock) {
        if (deviceKey.isBlank()) return emptyList()
        read(context).devices[deviceKey].orEmpty().sortedByDescending { it.savedAt }
    }

    /** 新增或覆盖同一 uid 的方案，超出上限时丢弃最旧的。 */
    fun upsert(
        context: Context,
        deviceKey: String,
        profile: HearingProfile,
    ): List<HearingProfile> = synchronized(lock) {
        if (deviceKey.isBlank()) return emptyList()
        val store = read(context)
        val existing = store.devices[deviceKey].orEmpty()
        val kept = existing.filterNot { it.uid == profile.uid }
        val usedIndices = kept.map { it.localIndex }.toSet()
        val assignedIndex = if (profile.localIndex in usedIndices || profile.localIndex <= 0) {
            generateSequence(1) { it + 1 }.first { it !in usedIndices }
        } else {
            profile.localIndex
        }
        val finalName = profile.name.ifBlank {
            val ts = if (profile.savedAt > 0) profile.savedAt else System.currentTimeMillis()
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
        val resolvedProfile = profile.copy(localIndex = assignedIndex, name = finalName)
        val merged = (listOf(resolvedProfile) + kept)
            .sortedByDescending { it.savedAt }
            .take(MAX_PROFILES)
        write(context, store.copy(devices = store.devices + (deviceKey to merged)))
        merged
    }

    fun remove(context: Context, deviceKey: String, uid: Int): List<HearingProfile> = synchronized(lock) {
        if (deviceKey.isBlank()) return emptyList()
        val store = read(context)
        val merged = store.devices[deviceKey].orEmpty().filterNot { it.uid == uid }
        write(context, store.copy(devices = store.devices + (deviceKey to merged)))
        merged
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun read(context: Context): Store {
        val f = file(context)
        if (!f.isFile) return Store()
        return runCatching {
            json.decodeFromString(Store.serializer(), f.readText())
        }.getOrDefault(Store())
    }

    private fun write(context: Context, store: Store) {
        runCatching {
            file(context).writeText(json.encodeToString(Store.serializer(), store))
        }
    }
}
