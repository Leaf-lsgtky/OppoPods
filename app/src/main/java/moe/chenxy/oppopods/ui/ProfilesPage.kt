package moe.chenxy.oppopods.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.DeviceProfile
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.pods.ProfileAssets
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun ProfilesPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    prefs: SharedPreferences,
    onActiveProfileChanged: (DeviceProfile) -> Unit = {},
) {
    val context = LocalContext.current

    var profiles by remember { mutableStateOf(DeviceProfileStore.loadProfiles(prefs)) }
    var activeId by remember { mutableStateOf(DeviceProfileStore.activeId(prefs)) }
    val importError = remember { mutableStateOf(false) }
    val showRestoreConfirm = remember { mutableStateOf(false) }

    fun reload() {
        profiles = DeviceProfileStore.loadProfiles(prefs)
        activeId = DeviceProfileStore.activeId(prefs)
    }

    fun currentActive(): DeviceProfile =
        profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val profile = context.contentResolver.openInputStream(uri)
                ?.use { ProfileAssets.importZip(context, it) } ?: error("empty")
            DeviceProfileStore.addProfile(prefs, profile)
            reload()
        }.onFailure { importError.value = true }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)
                ?.use { ProfileAssets.exportZip(context, currentActive(), it) }
        }
    }

    fun switchTo(profile: DeviceProfile) {
        DeviceProfileStore.setActive(prefs, profile.id)
        reload()
        onActiveProfileChanged(profile)
    }

    fun delete(profile: DeviceProfile) {
        DeviceProfileStore.deleteProfile(prefs, profile.id)
        ProfileAssets.deleteAssets(context, profile.id)
        reload()
        onActiveProfileChanged(currentActive())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            Card {
                ArrowPreference(
                    title = stringResource(R.string.profiles_import),
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                )
                ArrowPreference(
                    title = stringResource(R.string.profiles_export),
                    onClick = { exportLauncher.launch("oppopods_${currentActive().id}.zip") }
                )
                ArrowPreference(
                    title = stringResource(R.string.profiles_restore_defaults),
                    onClick = { showRestoreConfirm.value = true }
                )
            }
        }

        items(profiles, key = { it.id }) { profile ->
            Card(modifier = Modifier.padding(top = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { switchTo(profile) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = profile.name)
                        if (profile.id == activeId) {
                            Text(text = stringResource(R.string.profiles_in_use))
                        }
                    }
                    TextButton(
                        text = stringResource(R.string.profiles_delete),
                        enabled = profiles.size > 1,
                        onClick = { delete(profile) }
                    )
                }
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.profiles_import_failed),
        show = importError.value,
        onDismissRequest = { importError.value = false },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = { importError.value = false }
            )
        }
    }

    OverlayDialog(
        title = stringResource(R.string.profiles_restore_defaults),
        summary = stringResource(R.string.profiles_restore_confirm),
        show = showRestoreConfirm.value,
        onDismissRequest = { showRestoreConfirm.value = false },
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showRestoreConfirm.value = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    DeviceProfileStore.restoreSeeds(prefs)
                    reload()
                    onActiveProfileChanged(currentActive())
                    showRestoreConfirm.value = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}
