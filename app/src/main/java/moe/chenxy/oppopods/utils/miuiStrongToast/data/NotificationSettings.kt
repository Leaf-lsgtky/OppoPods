package moe.chenxy.oppopods.utils.miuiStrongToast.data

import android.content.Intent
import android.content.SharedPreferences

data class NotificationSettings(
    val showConnectionBatteryIsland: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND,
    val showConnectionPopup: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP,
    val connectionPopupDismissSeconds: Int = OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS,
    val showConnectionNotification: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION,
    val notificationIslandStyle: Boolean = OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE,
    val updatedAt: Long = 0L
) {
    val showNotificationAsIsland: Boolean
        get() = showConnectionNotification && notificationIslandStyle

    fun putExtras(intent: Intent) {
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, showConnectionBatteryIsland)
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_POPUP, showConnectionPopup)
        intent.putExtra(OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, connectionPopupDismissSeconds)
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        intent.putExtra(OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
        intent.putExtra(OppoPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, updatedAt)
    }

    fun writeToPrefs(prefs: SharedPreferences, commit: Boolean = false): Boolean {
        val editor = prefs.edit()
        editor.putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, showConnectionBatteryIsland)
        editor.putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_POPUP, showConnectionPopup)
        editor.putInt(OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, connectionPopupDismissSeconds)
        editor.putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        editor.putBoolean(OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
        editor.putLong(OppoPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, updatedAt)
        return if (commit) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
    }

    fun withUpdatedAtIfMissing(now: Long = System.currentTimeMillis()): NotificationSettings {
        return if (updatedAt > 0L) this else copy(updatedAt = now)
    }

    companion object {
        private val PREF_KEYS = listOf(
            OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
            OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
            OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
            OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
            OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
            OppoPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT
        )

        fun fromPrefs(prefs: SharedPreferences): NotificationSettings {
            return NotificationSettings(
                showConnectionBatteryIsland = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND
                ),
                showConnectionPopup = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP
                ),
                connectionPopupDismissSeconds = prefs.getInt(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
                ).normalizedPopupDismissSeconds(),
                showConnectionNotification = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION
                ),
                notificationIslandStyle = prefs.getBoolean(
                    OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
                ),
                updatedAt = prefs.getLong(OppoPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT, 0L)
            )
        }

        fun fromPrefsOrNull(prefs: SharedPreferences): NotificationSettings? {
            return if (PREF_KEYS.any { prefs.contains(it) }) fromPrefs(prefs) else null
        }

        fun fromIntent(intent: Intent?, fallback: NotificationSettings): NotificationSettings {
            if (intent == null) return fallback
            return NotificationSettings(
                showConnectionBatteryIsland = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    fallback.showConnectionBatteryIsland
                ),
                showConnectionPopup = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    fallback.showConnectionPopup
                ),
                connectionPopupDismissSeconds = intent.getIntExtra(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    fallback.connectionPopupDismissSeconds
                ).normalizedPopupDismissSeconds(),
                showConnectionNotification = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    fallback.showConnectionNotification
                ),
                notificationIslandStyle = intent.getBooleanExtra(
                    OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    fallback.notificationIslandStyle
                ),
                updatedAt = intent.getLongExtra(
                    OppoPodsPrefsKey.NOTIFICATION_SETTINGS_UPDATED_AT,
                    fallback.updatedAt
                )
            )
        }

        fun newerOf(first: NotificationSettings, second: NotificationSettings?): NotificationSettings {
            return if (second != null && second.updatedAt > first.updatedAt) second else first
        }

        private fun Int.normalizedPopupDismissSeconds(): Int {
            return if (this in OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS) {
                this
            } else {
                OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
            }
        }
    }
}
