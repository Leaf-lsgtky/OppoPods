package moe.chenxy.oppopods.pods

/**
 * 一个可选的 ANC 模式项（按机型 noiseReductionMode 动态生成）。
 * 主模式可含若干子模式（如"降噪"下的智能/深度/中度/轻度）；无子模式则直接可发送。
 */
data class AncOption(
    val key: String,
    val protocolIndex: Int,
    val sendable: Boolean = true,
    val children: List<AncOption> = emptyList(),
)

/** ANC 模式的规范键名（与 [AncOption.key] 对应）。 */
object AncKeys {
    const val OFF = "Off"
    const val NC = "NC"
    const val TRANSPARENCY = "Transparency"
    const val ADAPTIVE = "Adaptive"
    const val SMART = "Smart"
    const val LIGHT = "Light"
    const val MEDIUM = "Medium"
    const val DEEP = "Deep"
}

/**
 * 一个 EQ 预设。内置预设来自型号白名单；耳机端自定义预设来自 0x0122 查询。
 * modeType 仅对内置预设有意义，设备端自定义预设使用 -1。
 */
@kotlinx.serialization.Serializable
data class EqPreset(
    val id: Int,
    val name: String,
    val modeType: Int = -1,
)

/**
 * 设备端 EQ 条目。0x0122 会同时回报内置和自定义预设；自定义条目额外包含
 * 频率、增益和设备允许的增益范围，供二级 EQ 页面编辑、保存和删除。
 */
@kotlinx.serialization.Serializable
data class EqDevicePreset(
    val id: Int,
    val name: String,
    val selected: Boolean = false,
    val minValue: Int = -6,
    val maxValue: Int = 6,
    val frequencies: List<Int> = emptyList(),
    val gains: List<Int> = emptyList(),
)

/** OPPO 自定义 EQ 没有随型号声明频段时使用的官方常见六段频率。 */
object EqDefaults {
    val FREQUENCIES = listOf(62, 250, 1000, 4000, 8000, 16000)
}

/**
 * 单个机型的能力集，由 [DeviceModelRegistry] 从内嵌白名单 JSON 推导。
 *
 * 这里只保留本项目 UI 实际会用到的字段；白名单里其余上百个能力键暂不解析，
 * 需要时按 `flagOn(func, "xxx")` 的方式补一行即可。
 */
data class DeviceCapabilities(
    val modelId: String = "",
    val modelName: String = "Unknown",
    val deviceName: String = "",
    val supportSpp: Boolean = true,
    val protocolType: Int = 1,
    val isSupported: Boolean = true,

    /** 白名单 function.spatialTypes：0=关闭 1=固定 2=头部跟踪。 */
    val spatialTypes: List<Int> = emptyList(),
    val hasDualDevice: Boolean = false,
    val hasWearDetection: Boolean = false,
    val hasGameMode: Boolean = false,
    val hasGameSound: Boolean = false,
    /** 型号是否声明支持自定义 EQ；用于决定是否查询耳机端预设列表。 */
    val hasCustomEq: Boolean = false,
    /** 型号白名单中的内置 EQ 预设（protocolIndex → modeType → 显示名）。 */
    val eqPresets: List<EqPreset> = emptyList(),
    /** 型号白名单声明的自定义 EQ 频率；为空时由 [EqDefaults] 兜底。 */
    val customEqFrequencies: List<Int> = emptyList(),
    /** 设备端可保存的自定义预设数上限；0 表示未声明。 */
    val customEqMaxPresets: Int = 0,
    val hasAdaptiveAnc: Boolean = false,
    /** NC 落在 idx0 且无子模式的老机型：降噪/通透语义与现代机型相反。 */
    val isLegacyAnc: Boolean = false,

    /** 按 JSON noiseReductionMode 构建的层级化 ANC 选项。 */
    val ancOptions: List<AncOption> = emptyList(),
    /** protocolIndex → 规范模式名（解析回报位图时用）。 */
    val ancIndexToName: Map<Int, String> = emptyMap(),
    /** 规范模式名 → protocolIndex（发送 ANC 时用）。 */
    val ancNameToIndex: Map<String, Int> = emptyMap(),
) {
    /** 空间音频三模式（0x0422 + 0x012A），需要白名单声明 headTracking。 */
    val hasSpatialAudio: Boolean get() = spatialTypes.contains(SpatialAudioMode.HEAD_TRACKING)

    /** 旧版空间音效开关（feature 0x1B），仅有开/关两态。 */
    val hasSpatialSound: Boolean get() = spatialTypes.isNotEmpty() && !hasSpatialAudio

    /** 游戏模式使用的 feature id：新设备 0x28 主开关，旧设备 0x06 低延迟。 */
    val gameModeFeatureId: Int
        get() = if (hasGameSound) GameModeFeature.MAIN else GameModeFeature.LOW_LATENCY

    /** 是否存在降噪等级细分（智能/轻/中/深）。 */
    val hasNoiseLevels: Boolean
        get() = ancOptions.any { option ->
            option.children.any { it.key in NOISE_LEVEL_KEYS }
        } ||
                ancNameToIndex.keys.any { it in NOISE_LEVEL_KEYS }

    companion object {
        private val NOISE_LEVEL_KEYS =
            setOf(AncKeys.SMART, AncKeys.LIGHT, AncKeys.MEDIUM, AncKeys.DEEP)
    }
}
