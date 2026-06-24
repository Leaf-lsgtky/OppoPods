package moe.chenxy.oppopods.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.SpatialAudioMode
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    gameMode: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    spatialAudioMode: Int = SpatialAudioMode.OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    adaptiveModeEnabled: Boolean = true,
    gameModeVisible: Boolean = true,
    homeImageFile: java.io.File? = null
) {
    val spatialAudioModes = listOf(
        SpatialAudioMode.OFF,
        SpatialAudioMode.FIXED,
        SpatialAudioMode.HEAD_TRACKING
    )
    val spatialAudioOptions = listOf(
        stringResource(R.string.off),
        stringResource(R.string.spatial_audio_fixed),
        stringResource(R.string.spatial_audio_head_tracking)
    )
    val spatialAudioSelectedIndex = spatialAudioModes
        .indexOf(spatialAudioMode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING))
        .takeIf { it >= 0 }
        ?: 0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            val homeBitmap = remember(homeImageFile?.path) {
                homeImageFile?.let {
                    runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
                }
            }
            val imageModifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 16.dp)
            if (homeBitmap != null) {
                Image(
                    bitmap = homeBitmap,
                    contentDescription = "Earphones",
                    modifier = imageModifier,
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.img_box),
                    contentDescription = "Earphones",
                    modifier = imageModifier,
                    contentScale = ContentScale.FillWidth
                )
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                PodStatus(batteryParams, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                AncSwitch(ancMode, onAncModeChange, adaptiveModeEnabled = adaptiveModeEnabled)
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                if (gameModeVisible) {
                    SwitchPreference(
                        title = stringResource(R.string.game_mode),
                        summary = stringResource(R.string.game_mode_summary),
                        checked = gameMode,
                        onCheckedChange = onGameModeChange
                    )
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.spatial_audio),
                    items = spatialAudioOptions,
                    selectedIndex = spatialAudioSelectedIndex,
                    onSelectedIndexChange = { onSpatialAudioModeChange(spatialAudioModes[it]) }
                )
            }
        }
    }
}
