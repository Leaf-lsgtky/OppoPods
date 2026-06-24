package moe.chenxy.oppopods.pods

import kotlinx.serialization.Serializable

/**
 * 单条蓝牙指令：cmd + seq + payload(hex 串)。实际帧由 OppoPackets.buildPacket 组装。
 * payload 形如 "01 01 02"（可带/不带空格），空串表示无 payload。
 */
@Serializable
data class PodCommand(
    val cmd: Int,
    val seq: Int = 0xF0,
    val payload: String = ""
) {
    fun toPacket(): ByteArray = OppoPackets.buildPacket(cmd, seq, payload.hexToBytes())
}

/**
 * 设备配置档：一套机型相关的协议指令 + 功能显隐。
 * commands 用 [ProfileKeys] 的字符串键；flags 为未来扩展（佩戴检测/降噪深度等，本轮仅存储）。
 */
@Serializable
data class DeviceProfile(
    val id: String,
    val name: String,
    val adaptiveVisible: Boolean = false,
    val gameModeVisible: Boolean = false,
    val commands: Map<String, PodCommand> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val assets: Map<String, String> = emptyMap(),
) {
    /** 取某动作的整包；缺键则发空包（不崩），便于未来扩展。 */
    fun packet(key: String): ByteArray {
        val command = commands[key]
        if (command == null) {
            android.util.Log.w("DeviceProfile", "no command for key=$key, sending nothing")
            return ByteArray(0)
        }
        return command.toPacket()
    }

    /** ANC 整包，mode 用控制器的整型编码：1=关 2=降噪 3=通透 4=自适应。 */
    fun ancPacket(mode: Int): ByteArray = packet(
        when (mode) {
            2 -> ProfileKeys.ANC_NC
            3 -> ProfileKeys.ANC_TRANSPARENCY
            4 -> ProfileKeys.ANC_ADAPTIVE
            else -> ProfileKeys.ANC_OFF
        }
    )

    /** 空间音频整包，mode：0=关 1=固定 2=头部跟踪。 */
    fun spatialPacket(mode: Int): ByteArray = packet(
        when (mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)) {
            SpatialAudioMode.FIXED -> ProfileKeys.SPATIAL_FIXED
            SpatialAudioMode.HEAD_TRACKING -> ProfileKeys.SPATIAL_HEAD
            else -> ProfileKeys.SPATIAL_OFF
        }
    )

    /** 从 GAME_ON 命令 payload 提取游戏模式 feature ID（首个字节）。 */
    fun gameModeFeatureId(): Int {
        val payload = commands[ProfileKeys.GAME_ON]?.payload?.hexToBytes()
        return if (payload != null && payload.isNotEmpty()) payload[0].toInt() and 0xFF
        else GameModeFeature.MAIN
    }

    /** 游戏模式发包序列。 */
    fun gameModePackets(enabled: Boolean): List<ByteArray> {
        return listOf(packet(if (enabled) ProfileKeys.GAME_ON else ProfileKeys.GAME_OFF))
    }
}

/** assets 映射的标准键名（值为配置资源目录内的文件名；缺省则用内置资源）。 */
object AssetKeys {
    const val HOME_IMAGE = "home_image"        // 首页耳机图
    const val ISLAND_LEFT = "island_left"      // 连接临时超级岛左耳图
    const val ISLAND_RIGHT = "island_right"    // 连接临时超级岛右耳图
    const val CONNECT_VIDEO = "connect_video"  // 连接动画视频
}

/** commands 映射的标准键名。新增功能加键即可，无需改数据结构。 */
object ProfileKeys {
    const val ANC_OFF = "anc_off"
    const val ANC_NC = "anc_nc"
    const val ANC_TRANSPARENCY = "anc_transparency"
    const val ANC_ADAPTIVE = "anc_adaptive"
    const val GAME_ON = "game_on"
    const val GAME_OFF = "game_off"
    const val GAME_LL_ON = "game_ll_on"
    const val GAME_LL_OFF = "game_ll_off"
    const val SPATIAL_OFF = "spatial_off"
    const val SPATIAL_FIXED = "spatial_fixed"
    const val SPATIAL_HEAD = "spatial_head"
    const val QUERY_BATTERY = "query_battery"
    const val QUERY_ANC = "query_anc"
    const val QUERY_STATUS = "query_status"
}

/** 把 "01 01 02" / "010102" 这类 hex 串解析为字节数组（忽略空白）。 */
fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    if (clean.isEmpty()) return ByteArray(0)
    require(clean.length % 2 == 0) { "Invalid hex string: $this" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
