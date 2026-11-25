package moe.chenxy.oppopods.pods

/**
 * MIUI 耳机插件与 OPPO 状态之间的语义转换；不依赖 Android，也不组装或发送协议包。
 *
 * 命令选择函数只返回 [ProfileKeys] 中且实际存在于 `DeviceProfile.commands.keys` 的键。
 * 调用方仍须通过现有控制器 / DeviceProfile API 发包，不可把 MIUI 状态字符串作为 OPPO payload。
 */
object MiuiHeadsetCodec {
    const val INVALID_BATTERY = 255
    const val MAIN_STATUS_FIELD_COUNT = 16
    const val MANUAL_LEVEL_MAX = 19

    data class AncState(
        val miuiMode: Int?,
        val ancString: String,
        val intelligentOn: Boolean,
    )

    data class Battery(val percent: Int, val charging: Boolean)

    /** MIUI：0=关、1=降噪、2=通透；OPPO：1=关、2=降噪、3=通透。 */
    fun oppoModeFromMiui(miuiMode: Int): Int? = when (miuiMode) {
        0 -> 1
        1 -> 2
        2 -> 3
        else -> null
    }

    /** OPPO 自适应归入 MIUI 降噪；是否智能开启由 [miuiAncState] 单独表达。 */
    fun miuiModeFromOppo(oppoMode: Int): Int? = when (oppoMode) {
        1 -> 0
        2, 4 -> 1
        3 -> 2
        else -> null
    }

    fun miuiAncState(oppoMode: Int, noiseLevel: Int? = null): AncState = AncState(
        miuiMode = miuiModeFromOppo(oppoMode),
        ancString = miuiAncString(oppoMode, noiseLevel),
        intelligentOn = oppoMode == 4 || (oppoMode == 2 && noiseLevel == NoiseLevel.SMART),
    )

    /**
     * 插件普通状态：0000=关、0200=通透、01XX=降噪。
     * 手动代表点为轻 0、中 9、深 19（XX 是十六进制）。这是 UI 离散映射，不是 OPPO 协议档位。
     * 智能 / 自适应仍回报 0100，另通过 intelligentOn / getcommonconfig102 回报开关。
     * 未知模式返回 -1，避免把未知状态谎报为关闭；未知降噪深度只使用手动显示默认点 0。
     */
    fun miuiAncString(oppoMode: Int, noiseLevel: Int? = null): String = when (oppoMode) {
        1 -> "0000"
        3 -> "0200"
        4 -> "0100"
        2 -> miuiManualAncString(manualLevelAnchor(noiseLevel) ?: 0)!!
        else -> "-1"
    }

    /** 仅接受真实百分数；缺失、断连或越界电量统一编码为 255，不截断或钳制。 */
    fun encodeBattery(
        percent: Int?,
        charging: Boolean = false,
        connected: Boolean = true,
    ): Int {
        if (!connected || percent == null || percent !in 0..100) return INVALID_BATTERY
        return percent or if (charging) 0x80 else 0
    }

    /** 解码无符号 MIUI 电量值；255 或非法百分数没有有效电量语义。 */
    fun decodeBattery(encoded: Int): Battery? {
        if (encoded !in 0..255 || encoded == INVALID_BATTERY) return null
        val percent = encoded and 0x7F
        if (percent > 100) return null
        return Battery(percent, encoded and 0x80 != 0)
    }

    /** Pluto 的 20 档手动编码；非 0..19 的输入不生成状态。 */
    fun miuiManualAncString(level: Int): String? =
        if (level in 0..MANUAL_LEVEL_MAX) "01" + level.toString(16).padStart(2, '0').uppercase()
        else null

    /** 四字符 0100..0113（十六进制），不接受其他模式、符号、空白或越界档位。 */
    fun miuiManualLevel(ancString: String): Int? {
        if (ancString.length != 4 || !ancString.startsWith("01")) return null
        val suffix = ancString.substring(2)
        if (suffix.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return suffix.toIntOrNull(16)?.takeIf { it in 0..MANUAL_LEVEL_MAX }
    }

    /**
     * 将 20 档滑杆就近映射到设备真实支持的手动档；等距时选较轻档。
     * LIGHT / MEDIUM / DEEP 的 UI 锚点分别为 0 / 9 / 19。
     * 支持列表可直接来自 [supportedManualNoiseLevels]；SMART 不参加手动映射。
     * 无有效手动档或请求格式错误时返回 null，调用方不应发包。
     */
    fun oppoNoiseLevelFromMiui(
        ancString: String,
        supportedNoiseLevels: Collection<Int>,
    ): Int? {
        val requested = miuiManualLevel(ancString) ?: return null
        return manualLevels.filter { it in supportedNoiseLevels }
            .minByOrNull { kotlin.math.abs(manualLevelAnchor(it)!! - requested) }
    }

    /** 只由真实 command keys 决定支持情况；不根据 UI 显隐标志推断命令存在。 */
    fun supportedManualNoiseLevels(commandKeys: Collection<String>): List<Int> =
        manualLevels.filter {
            val key = noiseLevelCommandKey(it)
            key != null && key in commandKeys
        }

    fun noiseLevelCommandKey(noiseLevel: Int): String? = when (noiseLevel) {
        NoiseLevel.SMART -> ProfileKeys.SET_NOISE_LEVEL_SMART
        NoiseLevel.LIGHT -> ProfileKeys.SET_NOISE_LEVEL_LIGHT
        NoiseLevel.MEDIUM -> ProfileKeys.SET_NOISE_LEVEL_MEDIUM
        NoiseLevel.DEEP -> ProfileKeys.SET_NOISE_LEVEL_DEEP
        else -> null
    }

    /** 返回设备支持的既有 SET_NOISE_LEVEL_* 键，不创建新 payload。 */
    fun manualAncCommandKey(ancString: String, commandKeys: Collection<String>): String? =
        oppoNoiseLevelFromMiui(ancString, supportedManualNoiseLevels(commandKeys))
            ?.let(::noiseLevelCommandKey)

    /**
     * 智能开优先使用降噪 SMART；允许时再选独立 ANC_ADAPTIVE。
     * 两者都没有时返回 null，绝不把智能开降级为某个固定手动档。
     * 智能关应由调用方恢复上次手动档，再通过 [manualAncCommandKey] 选择实际可用命令。
     */
    fun smartAncCommandKey(
        commandKeys: Collection<String>,
        withAdaptiveFallback: Boolean = true,
    ): String? = when {
        ProfileKeys.SET_NOISE_LEVEL_SMART in commandKeys -> ProfileKeys.SET_NOISE_LEVEL_SMART
        withAdaptiveFallback && ProfileKeys.ANC_ADAPTIVE in commandKeys -> ProfileKeys.ANC_ADAPTIVE
        else -> null
    }

    /**
     * 构造插件的 16 字段普通状态，只写已证实的 0/1/2（左右盒 encoded 电量）和 7（ANC）。
     * 其他字段的语义由插件接入层负责确认；默认留空，或传入已验证的 16 字段 template。
     * 此函数不宣称默认空字段满足每一代插件对其他功能的解析要求，也不会猜造布尔 / 佩戴状态。
     */
    fun mainStatus(
        leftBattery: Int,
        rightBattery: Int,
        caseBattery: Int,
        ancString: String,
        template: List<String> = List(MAIN_STATUS_FIELD_COUNT) { "" },
    ): String {
        require(template.size == MAIN_STATUS_FIELD_COUNT) { "MIUI main status requires 16 fields" }
        require(template.none { ',' in it }) { "A MIUI main status field cannot contain a comma" }
        require(listOf(leftBattery, rightBattery, caseBattery).all {
            it == INVALID_BATTERY || decodeBattery(it) != null
        }) { "Battery fields must use encodeBattery values" }
        require(ancString == "-1" || ancString == "0000" || ancString == "0200" ||
            miuiManualLevel(ancString) != null) { "Invalid MIUI ANC state" }
        return template.toMutableList().apply {
            this[0] = leftBattery.toString()
            this[1] = rightBattery.toString()
            this[2] = caseBattery.toString()
            this[7] = ancString
        }.joinToString(",")
    }

    private val manualLevels = listOf(NoiseLevel.LIGHT, NoiseLevel.MEDIUM, NoiseLevel.DEEP)

    private fun manualLevelAnchor(noiseLevel: Int?): Int? = when (noiseLevel) {
        NoiseLevel.LIGHT -> 0
        NoiseLevel.MEDIUM -> 9
        NoiseLevel.DEEP -> MANUAL_LEVEL_MAX
        else -> null
    }
}
