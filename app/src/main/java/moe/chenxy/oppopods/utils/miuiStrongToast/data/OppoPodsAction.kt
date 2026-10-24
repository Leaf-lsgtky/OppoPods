package moe.chenxy.oppopods.utils.miuiStrongToast.data

object OppoPodsAction {
    const val ACTION_PODS_UI_INIT = "chen.action.oppopods.ui_init"
    const val ACTION_PODS_CONNECTED = "chen.action.oppopods.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "chen.action.oppopods.pods_disconnected"
    /** 应用请求断开耳机连接（hook 模式下由蓝牙进程执行）。 */
    const val ACTION_PODS_DISCONNECT_REQUEST = "chen.action.oppopods.disconnect_request"
    const val ACTION_PODS_BATTERY_CHANGED = "chen.action.oppopods.pods_battery_changed"
    const val ACTION_ANC_SELECT = "chen.action.oppopods.anc_select"
    const val ACTION_PODS_ANC_CHANGED = "chen.action.oppopods.pods_anc_select"
    const val ACTION_PODS_COLOR_CHANGED = "chen.action.oppopods.pods_color_changed"
    const val EXTRA_DEVICE_COLOR = "device_color"
    const val ACTION_GET_PODS_MAC = "chen.action.oppopods.get_pods_mac"
    const val ACTION_PODS_MAC_RECEIVED = "chen.action.oppopods.pods_mac_received"
    const val ACTION_REFRESH_STATUS = "chen.action.oppopods.refresh_status"
    const val EXTRA_CALIBRATION_PROFILE = "calibration_profile_json"
    const val ACTION_GAME_MODE_SET = "chen.action.oppopods.game_mode_set"
    const val ACTION_PODS_GAME_MODE_CHANGED = "chen.action.oppopods.pods_game_mode_changed"
    const val ACTION_EQ_PRESET_SET = "chen.action.oppopods.eq_preset_set"
    const val ACTION_PODS_EQ_PRESET_CHANGED = "chen.action.oppopods.pods_eq_preset_changed"
    const val ACTION_EQ_PRESET_SAVE = "chen.action.oppopods.eq_preset_save"
    const val ACTION_EQ_PRESET_DELETE = "chen.action.oppopods.eq_preset_delete"
    const val ACTION_SPATIAL_AUDIO_SET = "chen.action.oppopods.spatial_audio_set"
    const val ACTION_PODS_SPATIAL_AUDIO_CHANGED = "chen.action.oppopods.pods_spatial_audio_changed"
    const val ACTION_SPATIAL_SOUND_SET = "chen.action.oppopods.spatial_sound_set"
    const val ACTION_PODS_SPATIAL_SOUND_CHANGED = "chen.action.oppopods.pods_spatial_sound_changed"
    const val ACTION_NOISE_LEVEL_SET = "chen.action.oppopods.noise_level_set"
    const val ACTION_PODS_NOISE_LEVEL_CHANGED = "chen.action.oppopods.pods_noise_level_changed"
    const val ACTION_PODS_SMART_ANC_LEVEL_CHANGED = "chen.action.oppopods.pods_smart_anc_level_changed"
    const val ACTION_AUTO_PLAY_PAUSE_SET = "chen.action.oppopods.auto_play_pause_set"
    const val ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED = "chen.action.oppopods.pods_auto_play_pause_changed"
    const val ACTION_DUAL_DEVICE_SET = "chen.action.oppopods.dual_device_set"
    const val ACTION_PODS_DUAL_DEVICE_CHANGED = "chen.action.oppopods.pods_dual_device_changed"
    const val ACTION_PODS_CONNECTED_DEVICES_CHANGED = "chen.action.oppopods.pods_connected_devices_changed"
    const val ACTION_CYCLE_ANC = "chen.action.oppopods.cycle_anc"
    const val ACTION_NOTIFICATION_SETTINGS_CHANGED = "chen.action.oppopods.notification_settings_changed"
    const val ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED = "chen.action.oppopods.milink_spatial_audio_option_changed"
    // 自定义按钮（MiLink 面板劫持控件）功能变更广播（App → com.milink.service）
    const val ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED = "chen.action.oppopods.custom_button_function_changed"
    // 自定义按钮位置变更广播（App → com.milink.service）
    const val ACTION_CUSTOM_BUTTON_POSITION_CHANGED = "chen.action.oppopods.custom_button_position_changed"
    // 当前设备配置档变更广播（App → com.android.bluetooth），携带 EXTRA_PROFILE_JSON
    const val ACTION_ACTIVE_PROFILE_CHANGED = "chen.action.oppopods.active_profile_changed"
    // RFCOMM 按 productId 精确识别后的配置档（com.android.bluetooth → App）
    const val ACTION_PODS_PROFILE_CHANGED = "chen.action.oppopods.pods_profile_changed"

    // 通透人声变体（App → com.android.bluetooth）
    const val ACTION_ANC_VARIANT_SELECT = "chen.action.oppopods.anc_variant_select"
    // 通透人声变体回报（com.android.bluetooth → App）
    const val ACTION_PODS_ANC_VARIANT_CHANGED = "chen.action.oppopods.pods_anc_variant_changed"

    // 贴合度 / 黄金听感命令（App → com.android.bluetooth）
    const val ACTION_CALIBRATION_COMMAND = "chen.action.oppopods.calibration_command"
    // 贴合度 / 黄金听感状态快照（com.android.bluetooth → App），JSON 见 EXTRA_CALIBRATION_JSON
    const val ACTION_PODS_CALIBRATION_STATE = "chen.action.oppopods.pods_calibration_state"

    const val EXTRA_PROFILE_JSON = "profile_json"
    const val EXTRA_EQ_ENTRIES_JSON = "eq_entries_json"
    const val EXTRA_ALLOW_RFCOMM_RECONNECT = "allow_rfcomm_reconnect"
    const val EXTRA_RFCOMM_CONNECTED = "rfcomm_connected"
    const val EXTRA_ANC_VARIANT = "anc_variant"
    const val EXTRA_CALIBRATION_JSON = "calibration_json"
    const val EXTRA_CALIBRATION_COMMAND = "calibration_command"
    const val EXTRA_CALIBRATION_HEARD = "calibration_heard"
    const val EXTRA_CALIBRATION_LEVEL = "calibration_level"
    const val EXTRA_CALIBRATION_RELEASED = "calibration_released"
    const val EXTRA_CALIBRATION_ENABLED = "calibration_enabled"
    const val EXTRA_CALIBRATION_NAME = "calibration_name"
    const val EXTRA_CALIBRATION_PREVIEW = "calibration_preview"

    const val ACTION_BT_LOG_ENTRY = "chen.action.oppopods.bt_log_entry"
    const val EXTRA_BT_LOG_IS_SEND = "is_send"
    const val EXTRA_BT_LOG_HEX = "hex"
    const val EXTRA_BT_LOG_LABEL = "label"
}
