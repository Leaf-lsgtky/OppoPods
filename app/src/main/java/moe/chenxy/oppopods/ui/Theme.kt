package moe.chenxy.oppopods.ui

import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题设置的完整状态。
 *
 * 全部保存在 `oppopods_settings`，由 [MainActivity] 读写；[AppTheme] 只消费。
 * [keyColor] 为空时沿用 Miuix 的默认色板。
 */
data class AppThemeConfig(
    val mode: Int = 0,
    val floatingNavbar: Boolean = false,
    val liquidGlass: Boolean = false,
    val blurEnabled: Boolean = true,
) {
    fun toColorSchemeMode(): ColorSchemeMode = when (mode) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        3 -> ColorSchemeMode.MonetSystem
        4 -> ColorSchemeMode.MonetLight
        5 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.System
    }

    companion object {
        private const val KEY_MODE = "theme_mode"
        private const val KEY_FLOATING = "floating_navbar"
        private const val KEY_GLASS = "liquid_glass"
        private const val KEY_BLUR = "background_blur"

        fun load(prefs: SharedPreferences): AppThemeConfig = AppThemeConfig(
            mode = prefs.getInt(KEY_MODE, 0),
            floatingNavbar = prefs.getBoolean(KEY_FLOATING, false),
            liquidGlass = prefs.getBoolean(KEY_GLASS, false),
            blurEnabled = prefs.getBoolean(KEY_BLUR, true),
        )

        fun save(prefs: SharedPreferences, config: AppThemeConfig) {
            prefs.edit()
                .putInt(KEY_MODE, config.mode)
                .putBoolean(KEY_FLOATING, config.floatingNavbar)
                .putBoolean(KEY_GLASS, config.liquidGlass)
                .putBoolean(KEY_BLUR, config.blurEnabled)
                .apply()
        }
    }
}

@Composable
fun AppTheme(
    config: AppThemeConfig = AppThemeConfig(),
    content: @Composable () -> Unit,
) {
    val mode = config.toColorSchemeMode()
    val controller = remember(mode) { ThemeController(colorSchemeMode = mode) }

    when (mode) {
        ColorSchemeMode.Light, ColorSchemeMode.Dark -> {
            val nightMode = if (mode == ColorSchemeMode.Dark) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            val currentConfig = LocalConfiguration.current
            val overrideConfig = remember(currentConfig, nightMode) {
                Configuration(currentConfig).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            }
            CompositionLocalProvider(LocalConfiguration provides overrideConfig) {
                MiuixTheme(controller = controller, content = content)
            }
        }

        else -> MiuixTheme(controller = controller, content = content)
    }
}
