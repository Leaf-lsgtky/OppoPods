package moe.chenxy.oppopods.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    autoGameMode: MutableState<Boolean> = mutableStateOf(false),
    onAutoGameModeChange: (Boolean) -> Unit = {},
    openHeyTap: MutableState<Boolean> = mutableStateOf(false),
    onOpenHeyTapChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val showHeyTapWarning = remember { mutableStateOf(false) }
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
    ) {
        item {
            Card {
                SuperDropdown(
                    title = stringResource(R.string.theme_title),
                    items = themeOptions,
                    selectedIndex = themeMode.value,
                    onSelectedIndexChange = { onThemeModeChange(it) }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                SuperSwitch(
                    title = stringResource(R.string.auto_game_mode),
                    checked = autoGameMode.value,
                    onCheckedChange = { onAutoGameModeChange(it) }
                )
                SuperSwitch(
                    title = stringResource(R.string.open_heytap),
                    summary = stringResource(R.string.open_heytap_summary),
                    checked = openHeyTap.value,
                    onCheckedChange = {
                        if (it) {
                            showHeyTapWarning.value = true
                        } else {
                            onOpenHeyTapChange(false)
                        }
                    }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = "OppoPods",
                    summary = "https://github.com/Leaf-lsgtky/OppoPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/Leaf-lsgtky/OppoPods")
                            context.startActivity(this)
                        }
                    }
                )
                BasicComponent(
                    title = stringResource(R.string.based_on),
                    summary = "HyperPods by Art_Chen"
                )
                BasicComponent(
                    title = "Github",
                    summary = "https://github.com/Art-Chen/HyperPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/Art-Chen/HyperPods")
                            context.startActivity(this)
                        }
                    }
                )
            }
        }
    }

    SuperDialog(
        title = stringResource(R.string.heytap_warning_title),
        summary = stringResource(R.string.heytap_warning),
        show = showHeyTapWarning,
        onDismissRequest = {
            showHeyTapWarning.value = false
        }
    ) {
        TextButton(
            text = stringResource(R.string.confirm),
            onClick = {
                showHeyTapWarning.value = false
                onOpenHeyTapChange(true)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}
