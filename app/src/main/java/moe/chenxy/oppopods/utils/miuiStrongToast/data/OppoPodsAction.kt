package moe.chenxy.oppopods.utils.miuiStrongToast.data

object OppoPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.oppopods.ui_init"
    const val ACTION_PODS_CONNECTED = "chen.action.oppopods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.oppopods.pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.oppopods.pods_battery_changed"
    const val ACTION_ANC_SELECT = "chen.action.oppopods.anc_select"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.oppopods.pods_anc_select"
    const val ACTION_GET_PODS_MAC = "chen.action.oppopods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.oppopods.pods_mac_received"
    const val ACTION_REFRESH_STATUS = "chen.action.oppopods.refresh_status"
    const val ACTION_GAME_MODE_SET = "chen.action.oppopods.game_mode_set"
    const val ACTION_PODS_GAME_MODE_CHANGED = "chen.action.oppopods.pods_game_mode_changed"
    const val ACTION_SPATIAL_AUDIO_SET = "chen.action.oppopods.spatial_audio_set"
    const val ACTION_PODS_SPATIAL_AUDIO_CHANGED = "chen.action.oppopods.pods_spatial_audio_changed"
    const val ACTION_CYCLE_ANC = "chen.action.oppopods.cycle_anc"
    const val ACTION_GAME_MODE_IMPLEMENTATION_CHANGED = "chen.action.oppopods.game_mode_implementation_changed"
    // Adaptive模式开关状态变更广播，用于跨进程同步偏好设置（App → com.android.bluetooth / com.xiaomi.bluetooth）
    const val ACTION_ADAPTIVE_MODE_CHANGED = "chen.action.oppopods.adaptive_mode_changed"
    const val ACTION_NOTIFICATION_SETTINGS_CHANGED = "chen.action.oppopods.notification_settings_changed"
    const val ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED = "chen.action.oppopods.milink_spatial_audio_option_changed"

    const val EXTRA_ALLOW_RFCOMM_RECONNECT = "allow_rfcomm_reconnect"
    const val EXTRA_RFCOMM_CONNECTED = "rfcomm_connected"
}
