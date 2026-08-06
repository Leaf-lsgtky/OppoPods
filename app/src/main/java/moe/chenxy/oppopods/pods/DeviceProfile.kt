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
    fun toPacket(): ByteArray = OppoPackets.buildPacketFixedSeq(cmd, seq, payload.hexToBytes())
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
    val noiseLevelVisible: Boolean = false,
    val autoPlayPauseVisible: Boolean = false,
    val dualDeviceVisible: Boolean = false,
    val connectedDevicesVisible: Boolean = false,
    val spatialAudioVisible: Boolean = false,
    val spatialSoundVisible: Boolean = false,
    /** 型号白名单中的内置 EQ 预设；耳机端自定义预设由连接状态另行回传。 */
    val eqPresets: List<EqPreset> = emptyList(),
    val customEqVisible: Boolean = false,
    val customEqFrequencies: List<Int> = emptyList(),
    val customEqMaxPresets: Int = 0,
    val commands: Map<String, PodCommand> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    /**
     * ANC 位图位号 → 规范模式名（见 [AncKeys]）。由白名单机型数据生成，
     * 用于解析耳机回报的位图；为空时回退到 [AncModeParser] 的静态字节表。
     */
    val ancIndexToName: Map<Int, String> = emptyMap(),
    /** 老机型（NC 落在 idx0 且无子模式）：静态表回退时需交换降噪/通透语义。 */
    val isLegacyAnc: Boolean = false,
    /** 白名单 productId（6 位大写 hex），自动识别得到；手写配置为空。 */
    val modelId: String = "",
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

    /** 降噪等级整包。 */
    fun noiseLevelPacket(level: Int): ByteArray = packet(
        when (level) {
            NoiseLevel.SMART -> ProfileKeys.SET_NOISE_LEVEL_SMART
            NoiseLevel.LIGHT -> ProfileKeys.SET_NOISE_LEVEL_LIGHT
            NoiseLevel.MEDIUM -> ProfileKeys.SET_NOISE_LEVEL_MEDIUM
            else -> ProfileKeys.SET_NOISE_LEVEL_DEEP
        }
    )

    /** 佩戴检测整包。 */
    fun autoPlayPausePacket(enabled: Boolean): ByteArray =
        packet(if (enabled) ProfileKeys.SET_AUTO_PLAY_PAUSE_ON else ProfileKeys.SET_AUTO_PLAY_PAUSE_OFF)

    /** 空间音效整包。 */
    fun spatialSoundPacket(enabled: Boolean): ByteArray =
        packet(if (enabled) ProfileKeys.SPATIAL_SOUND_ON else ProfileKeys.SPATIAL_SOUND_OFF)

    /** EQ 预设整包，id 为型号 JSON 或设备端 0x8122 回报的 protocolIndex。 */
    fun eqPacket(id: Int): ByteArray = OppoPackets.buildSetEqualizer(id)

    /** 双设备连接整包。 */
    fun dualDevicePacket(enabled: Boolean): ByteArray =
        packet(if (enabled) ProfileKeys.SET_DUAL_DEVICE_ON else ProfileKeys.SET_DUAL_DEVICE_OFF)
}

/** 降噪等级（仅在降噪模式下有效）。 */
object NoiseLevel {
    const val SMART = 0x80
    const val LIGHT = 0x40
    const val MEDIUM = 0x20
    const val DEEP = 0x10
    val ALL = listOf(SMART, LIGHT, MEDIUM, DEEP)
}

/** commands 映射的标准键名。新增功能加键即可，无需改数据结构。 */
object ProfileKeys {
    const val ANC_OFF = "anc_off"
    const val ANC_NC = "anc_nc"
    const val ANC_TRANSPARENCY = "anc_transparency"
    const val ANC_ADAPTIVE = "anc_adaptive"
    const val GAME_ON = "game_on"
    const val GAME_OFF = "game_off"
    const val SPATIAL_OFF = "spatial_off"
    const val SPATIAL_FIXED = "spatial_fixed"
    const val SPATIAL_HEAD = "spatial_head"
    const val SPATIAL_SOUND_ON = "spatial_sound_on"
    const val SPATIAL_SOUND_OFF = "spatial_sound_off"
    const val QUERY_BATTERY = "query_battery"
    const val QUERY_ANC = "query_anc"
    const val QUERY_EQ = "query_eq"
    const val QUERY_EQ_ALL = "query_eq_all"
    const val QUERY_STATUS = "query_status"
    const val SET_NOISE_LEVEL_SMART = "set_noise_level_smart"
    const val SET_NOISE_LEVEL_LIGHT = "set_noise_level_light"
    const val SET_NOISE_LEVEL_MEDIUM = "set_noise_level_medium"
    const val SET_NOISE_LEVEL_DEEP = "set_noise_level_deep"
    const val SET_AUTO_PLAY_PAUSE_ON = "set_auto_play_pause_on"
    const val SET_AUTO_PLAY_PAUSE_OFF = "set_auto_play_pause_off"
    const val SET_DUAL_DEVICE_ON = "set_dual_device_on"
    const val SET_DUAL_DEVICE_OFF = "set_dual_device_off"
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
