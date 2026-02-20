package moe.chenxy.oppopods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun AppTheme(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    content: @Composable () -> Unit
) {
    val controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode) }
    MiuixTheme(
        controller = controller,
        content = content
    )
}
