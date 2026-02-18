package moe.chenxy.oppopods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf


@Composable
fun App(
    showPopup: MutableState<Boolean> = mutableStateOf(false),
    onFinish: () -> Unit = {}
) {
    AppTheme {
        MainUI(showPopup = showPopup, onFinish = onFinish)
    }
}
