package moe.chenxy.oppopods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState

@Composable
fun App(
    themeConfig: MutableState<AppThemeConfig>,
    onThemeConfigChange: (AppThemeConfig) -> Unit = {},
) {
    AppTheme(config = themeConfig.value) {
        MainUI(
            themeConfig = themeConfig,
            onThemeConfigChange = onThemeConfigChange,
        )
    }
}
