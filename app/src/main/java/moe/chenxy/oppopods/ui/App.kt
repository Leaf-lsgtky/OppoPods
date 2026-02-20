package moe.chenxy.oppopods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@Composable
fun App(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {}
) {
    val colorSchemeMode = when (themeMode.value) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    AppTheme(colorSchemeMode = colorSchemeMode) {
        MainUI(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
    }
}
