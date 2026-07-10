package moe.chenxy.oppopods.utils.miuiStrongToast.data

import android.content.Intent

private const val KEY_STATUS = "status"
private const val KEY_LEFT_BATTERY = "left_battery"
private const val KEY_LEFT_CHARGING = "left_charging"
private const val KEY_LEFT_CONNECTED = "left_connected"
private const val KEY_RIGHT_BATTERY = "right_battery"
private const val KEY_RIGHT_CHARGING = "right_charging"
private const val KEY_RIGHT_CONNECTED = "right_connected"
private const val KEY_CASE_BATTERY = "case_battery"
private const val KEY_CASE_CHARGING = "case_charging"
private const val KEY_CASE_CONNECTED = "case_connected"

fun Intent.putBatteryStatus(status: BatteryParams, includeFlatExtras: Boolean = true) {
    putExtra(KEY_STATUS, status)
    if (!includeFlatExtras) return
    putExtra(KEY_LEFT_BATTERY, status.left?.battery ?: 0)
    putExtra(KEY_LEFT_CHARGING, status.left?.isCharging == true)
    putExtra(KEY_LEFT_CONNECTED, status.left?.isConnected == true)
    putExtra(KEY_RIGHT_BATTERY, status.right?.battery ?: 0)
    putExtra(KEY_RIGHT_CHARGING, status.right?.isCharging == true)
    putExtra(KEY_RIGHT_CONNECTED, status.right?.isConnected == true)
    putExtra(KEY_CASE_BATTERY, status.case?.battery ?: 0)
    putExtra(KEY_CASE_CHARGING, status.case?.isCharging == true)
    putExtra(KEY_CASE_CONNECTED, status.case?.isConnected == true)
}

@Suppress("DEPRECATION")
fun Intent.batteryStatusCompat(): BatteryParams? {
    runCatching { extras?.classLoader = BatteryParams::class.java.classLoader }
    return runCatching { getParcelableExtra(KEY_STATUS, BatteryParams::class.java) }.getOrNull()
        ?: runCatching { getParcelableExtra<BatteryParams>(KEY_STATUS) }.getOrNull()
        ?: batteryStatusFromFlatExtras()
}

private fun Intent.batteryStatusFromFlatExtras(): BatteryParams? {
    if (!hasExtra(KEY_LEFT_CONNECTED) && !hasExtra(KEY_RIGHT_CONNECTED) && !hasExtra(KEY_CASE_CONNECTED)) {
        return null
    }
    return BatteryParams(
        left = PodParams(
            getIntExtra(KEY_LEFT_BATTERY, 0),
            getBooleanExtra(KEY_LEFT_CHARGING, false),
            getBooleanExtra(KEY_LEFT_CONNECTED, false),
            0
        ),
        right = PodParams(
            getIntExtra(KEY_RIGHT_BATTERY, 0),
            getBooleanExtra(KEY_RIGHT_CHARGING, false),
            getBooleanExtra(KEY_RIGHT_CONNECTED, false),
            0
        ),
        case = PodParams(
            getIntExtra(KEY_CASE_BATTERY, 0),
            getBooleanExtra(KEY_CASE_CHARGING, false),
            getBooleanExtra(KEY_CASE_CONNECTED, false),
            0
        )
    )
}
