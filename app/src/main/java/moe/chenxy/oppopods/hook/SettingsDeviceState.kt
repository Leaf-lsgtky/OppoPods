package moe.chenxy.oppopods.hook

/** 只保存当前进程收到的状态，不把上一次连接的电量当作新连接的实时数据。 */
internal data class SettingsDeviceState(
    val connected: Boolean? = null,
    val battery: SettingsBatterySnapshot? = null,
    val ancMode: Int? = null,
    val supportedAncModes: List<Int>? = null,
    val pendingAncMode: Int? = null,
) {
    // 尚未收到配置档时显示不可操作的占位控件；不默认假定设备具备 ANC 能力。
    val toggleModes: List<Int>
        get() = supportedAncModes?.takeIf { it.size >= 2 } ?: listOf(1, 3, 2)

    val showAnc: Boolean
        get() = supportedAncModes == null || supportedAncModes.size >= 2

    val canControlAnc: Boolean
        get() = connected == true && supportedAncModes != null && pendingAncMode == null &&
            supportedAncModes.size >= 2 && ancMode in supportedAncModes

    val selectedIndex: Int
        get() = toggleModes.indexOf(ancMode).coerceAtLeast(0)

    fun modeForSelection(index: Int): Int? =
        if (canControlAnc) toggleModes.getOrNull(index) else null

    fun withAncReport(mode: Int): SettingsDeviceState = copy(
        ancMode = mode,
        pendingAncMode = pendingAncMode?.takeUnless { it == mode },
    )

    fun withoutLiveConfirmation(): SettingsDeviceState =
        copy(connected = null, battery = null, ancMode = null, pendingAncMode = null)

    fun disconnected(): SettingsDeviceState =
        copy(connected = false, battery = null, ancMode = null, pendingAncMode = null)
}

internal data class SettingsBatterySnapshot(
    val left: Int,
    val right: Int,
    val case: Int,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
) {
    // 与样本 SettingsLib 一致：优先取有效左右耳的较小值，仅在左右耳都未知时用盒电量。
    val overall: Int
        get() = listOf(left, right).filter { it in 0..100 }.minOrNull()
            ?: case.takeIf { it in 0..100 } ?: -1

    companion object {
        fun level(battery: Int?, present: Boolean): Int =
            battery?.takeIf { present && it in 0..100 } ?: -1
    }
}
