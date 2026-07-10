package moe.chenxy.oppopods.utils.miuiStrongToast.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object MilinkSpatialAudioOptionSettings {
    fun resolveAndCache(
        context: Context?,
        localPrefsName: String,
        remotePrefs: SharedPreferences,
        reloadRemotePrefs: () -> Unit,
        intent: Intent? = null
    ): Boolean {
        val localPrefs = context?.getSharedPreferences(localPrefsName, Context.MODE_PRIVATE)
        val cached = localPrefs
            ?.takeIf { it.contains(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED) }
            ?.getBoolean(
                OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )

        val resolved = if (
            intent?.hasExtra(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED) == true
        ) {
            intent.getBooleanExtra(
                OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                cached ?: OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )
        } else {
            reloadRemotePrefs()
            val defaultValue = cached ?: OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            val remoteValue = runCatching {
                remotePrefs.getBoolean(
                    OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                    defaultValue
                )
            }.getOrDefault(defaultValue)
            if (
                cached == false &&
                remoteValue == OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            ) {
                false
            } else {
                remoteValue
            }
        }

        localPrefs?.edit()
            ?.putBoolean(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, resolved)
            ?.apply()
        return resolved
    }
}
