package moe.chenxy.oppopods.utils.miuiStrongToast.data

object OppoPodsPrefsKey {
    const val EAR_DETECTION = "ear_detection"
    const val EAR_DETECTION_SWITCH_SPEAKER = "ear_detection_switch_speaker"
    const val SHOW_CONNECTION_BATTERY_ISLAND = "show_connection_battery_island"
    const val SHOW_CONNECTION_POPUP = "show_connection_popup"
    const val CONNECTION_POPUP_DISMISS_SECONDS = "connection_popup_dismiss_seconds"
    const val SHOW_CONNECTION_NOTIFICATION = "show_connection_notification"
    const val NOTIFICATION_ISLAND_STYLE = "notification_island_style"
    const val NOTIFICATION_SETTINGS_UPDATED_AT = "notification_settings_updated_at"
    const val NOTIFICATION_SETTINGS_CACHE_PREFS_NAME = "oppopods_notification_settings_cache"
    const val MILINK_SPATIAL_AUDIO_OPTION_ENABLED = "milink_spatial_audio_option_enabled"
    /** 配置来源模式：见 ProfileMode（auto / model）。 */
    const val PROFILE_MODE = "profile_mode"
    /** 手动指定型号时的白名单 productId。 */
    const val SELECTED_MODEL_ID = "selected_model_id"

    const val DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND = true
    const val DEFAULT_SHOW_CONNECTION_POPUP = false
    const val DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS = 8
    val CONNECTION_POPUP_DISMISS_SECOND_OPTIONS = listOf(3, 5, 8, 10, 15, 30)
    const val DEFAULT_SHOW_CONNECTION_NOTIFICATION = true
    const val DEFAULT_NOTIFICATION_ISLAND_STYLE = false
    const val DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED = true
}
