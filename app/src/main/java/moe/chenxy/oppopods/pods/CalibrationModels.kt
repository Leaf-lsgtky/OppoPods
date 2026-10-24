package moe.chenxy.oppopods.pods

import kotlinx.serialization.Serializable

/**
 * 贴合度 / 黄金听感共用的可选能力。
 *
 * 能力来自机型白名单 `function.fitDetection` / `hearingEnhancement` /
 * `hearingEnhancementNew` / `earScan` / `hearingJudgeDb`，不能按型号名猜测。
 */
data class CalibrationCapability(
    val fitDetection: Boolean = false,
    val hearingEnhancement: Boolean = false,
    val earScan: Boolean = false,
    val hearingJudgeDb: Int = 0,
) {
    val canCalibrate: Boolean get() = fitDetection || hearingEnhancement
}

/** 一次主观测听的点位：side 1=左 2=右，type 1..6，db 为设备侧增益。 */
@Serializable
data class HearingPoint(
    val side: Int,
    val type: Int,
    val db: Int,
)

/** 贴合度结果，协议值 0=一般 1=良好 6=较差。 */
object FitQuality {
    const val FAIR = 0
    const val GOOD = 1
    const val POOR = 6

    fun isKnown(value: Int): Boolean = value == FAIR || value == GOOD || value == POOR
}

/**
 * 两个流程共用的 UI 状态。
 *
 * 该对象会被序列化为 JSON，经 [moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction]
 * 在 Bluetooth 进程与应用进程之间同步，所以只放可序列化的轻量字段；
 * 耳扫原始 blob 仍由持有连接的控制器内存保存。
 */
@Serializable
data class CalibrationState(
    val mode: String = MODE_NONE,
    val stage: String = STAGE_IDLE,
    val message: String? = null,

    /** 贴合度左/右耳结果；null 表示尚未完成。 */
    val fitLeft: Int? = null,
    val fitRight: Int? = null,

    /** 黄金听感：是否支持耳道扫描，以及当前测听版本（1=旧版二分，2=滑块）。 */
    val supportsEarScan: Boolean = false,
    val version: Int = 2,

    /** 当前步骤 0..11。side/type 为当前测试耳与频点。 */
    val step: Int = 0,
    val totalSteps: Int = 12,
    val side: Int = 1,
    val frequencyType: Int = 1,
    val levelIndex: Int = 5,
    val decibels: Int = -19,

    /** 已完成步骤的点位。 */
    val levels: List<HearingPoint> = emptyList(),

    /** 耳机当前黄金听感开关（来自 0x010D feature 0x0B）。null=未知。 */
    val hearingEnabled: Boolean? = null,
    val currentName: String = "",
    val currentUid: Int? = null,

    /** 结果页/保存页使用的名称。 */
    val savedName: String = "",
    val canSave: Boolean = false,

    /**
     * 是否已取回完整的听感曲线（0x8116，耳扫机型还需 0x811F）。
     *
     * 官方在保存前会校验「测听已完成且已停止、曲线已就绪」；缺任意一条都不允许保存，
     * 否则写进去的方案没有 DSP 参数，耳机端不会生效。
     */
    val filtersReady: Boolean = false,

    /**
     * 刚保存成功的方案快照。
     *
     * 引擎在保存成功后填进来，UI 据此写入本机历史（供「听感配置」列表选择）。
     * 走 State 而不是回调，是为了 hook 模式下经 JSON 广播也能拿到同样内容。
     */
    val savedProfile: HearingProfile? = null,
) {
    val isBusy: Boolean
        get() = stage in setOf(
            STAGE_CHECKING_WEAR,
            STAGE_FITTING,
            STAGE_SCANNING,
            STAGE_HEARING,
            STAGE_PROCESSING,
            STAGE_SAVING,
        )

    companion object {
        const val MODE_NONE = "none"
        const val MODE_FIT = "fit"
        const val MODE_HEARING = "hearing"

        const val STAGE_IDLE = "idle"
        const val STAGE_CHECKING_WEAR = "checking_wear"
        const val STAGE_FITTING = "fitting"
        const val STAGE_FIT_RESULT = "fit_result"
        const val STAGE_SCANNING = "scanning"
        const val STAGE_HEARING = "hearing"
        const val STAGE_PROCESSING = "processing"
        const val STAGE_RESULT = "result"
        const val STAGE_SAVING = "saving"
        const val STAGE_DONE = "done"
        const val STAGE_ERROR = "error"

        const val HEARING_STEPS = 12

        /** 官方 V2 的 12 档增益，初始 index 5（-19 dB）。 */
        val V2_DECIBELS = listOf(-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17)
        const val V2_DEFAULT_LEVEL = 5

        /** X4 v116 机器码核对的频点；其他固件仅作显示。 */
        val FREQUENCY_HZ = listOf(500, 1000, 2000, 4000, 6000, 8000)
    }
}

/** 跨进程命令名；与 [OppoPodsAction.EXTRA_CALIBRATION_COMMAND] 配套。 */
object CalibrationCommand {
    const val START_FIT = "start_fit"
    const val START_HEARING = "start_hearing"
    const val ANSWER = "answer"
    const val SET_LEVEL = "set_level"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val RESUME = "resume"
    const val PAUSE = "pause"
    const val SAVE = "save"
    const val CANCEL = "cancel"
    const val SET_ENABLED = "set_enabled"
    const val PREVIEW = "preview"
    const val REFRESH = "refresh"
    /** 按本机历史切换方案；payload 是 HearingProfile 的 JSON。 */
    const val APPLY_PROFILE = "apply_profile"
}
