package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.AncOption
import moe.chenxy.oppopods.pods.SpatialAudioMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 功能页：主页剩下的声音、佩戴和「更多设置」。 */
@Composable
fun FeaturesPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gameMode: Boolean = false,
    onGameModeChange: (Boolean) -> Unit = {},
    gameModeVisible: Boolean = true,
    eqVisible: Boolean = false,
    eqCurrentName: String = "",
    onOpenEqualizer: () -> Unit = {},
    spatialAudioMode: Int = SpatialAudioMode.OFF,
    onSpatialAudioModeChange: (Int) -> Unit = {},
    spatialAudioVisible: Boolean = false,
    spatialSound: Boolean = false,
    onSpatialSoundChange: (Boolean) -> Unit = {},
    spatialSoundVisible: Boolean = false,
    fitDetectionVisible: Boolean = false,
    hearingEnhancementVisible: Boolean = false,
    hearingName: String = "",
    onOpenFitTest: () -> Unit = {},
    onOpenHearing: () -> Unit = {},
    autoPlayPauseVisible: Boolean = false,
    autoPlayPause: Boolean = false,
    onAutoPlayPauseChange: (Boolean) -> Unit = {},
    dualDeviceVisible: Boolean = false,
    dualDevice: Boolean = false,
    onDualDeviceChange: (Boolean) -> Unit = {},
    connectedDevicesVisible: Boolean = false,
    connectedDevices: List<moe.chenxy.oppopods.pods.ConnectedDevice> = emptyList(),
    connectedDevicesReceived: Boolean = false,
) {
    val spatialAudioModes = listOf(
        SpatialAudioMode.OFF,
        SpatialAudioMode.FIXED,
        SpatialAudioMode.HEAD_TRACKING,
    )
    val spatialAudioOptions = listOf(
        stringResource(R.string.off),
        stringResource(R.string.spatial_audio_fixed),
        stringResource(R.string.spatial_audio_head_tracking),
    )
    val spatialAudioSelectedIndex = spatialAudioModes
        .indexOf(spatialAudioMode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING))
        .takeIf { it >= 0 }
        ?: 0

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            androidx.compose.animation.AnimatedVisibility(
                visible = gameModeVisible || eqVisible || spatialAudioVisible || spatialSoundVisible,
                enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                Column {
                    SmallTitle(text = stringResource(R.string.features_section_sound))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        Column {
                            if (gameModeVisible) {
                                SwitchPreference(
                                    title = stringResource(R.string.game_mode),
                                    summary = stringResource(R.string.game_mode_summary),
                                    checked = gameMode,
                                    onCheckedChange = onGameModeChange,
                                )
                            }
                            if (eqVisible) {
                                ArrowPreference(
                                    title = stringResource(R.string.sound_effects),
                                    endActions = {
                                        if (eqCurrentName.isNotBlank()) {
                                            Text(text = eqCurrentName)
                                        }
                                    },
                                    onClick = onOpenEqualizer,
                                )
                            }
                            if (spatialAudioVisible) {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.spatial_audio),
                                    items = spatialAudioOptions,
                                    selectedIndex = spatialAudioSelectedIndex,
                                    onSelectedIndexChange = {
                                        onSpatialAudioModeChange(spatialAudioModes[it])
                                    },
                                )
                            }
                            if (spatialSoundVisible) {
                                SwitchPreference(
                                    title = stringResource(R.string.spatial_sound),
                                    summary = stringResource(R.string.spatial_sound_summary),
                                    checked = spatialSound,
                                    onCheckedChange = onSpatialSoundChange,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            androidx.compose.animation.AnimatedVisibility(
                visible = fitDetectionVisible || hearingEnhancementVisible || autoPlayPauseVisible,
                enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                Column {
                    SmallTitle(text = stringResource(R.string.features_section_wearing))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        Column {
                            if (fitDetectionVisible) {
                                ArrowPreference(
                                    title = stringResource(R.string.fit_detection),
                                    summary = stringResource(R.string.fit_detection_summary),
                                    onClick = onOpenFitTest,
                                )
                            }
                            if (hearingEnhancementVisible) {
                                ArrowPreference(
                                    title = stringResource(R.string.hearing_title),
                                    summary = hearingName.ifBlank {
                                        stringResource(R.string.hearing_summary)
                                    },
                                    onClick = onOpenHearing,
                                )
                            }
                            if (autoPlayPauseVisible) {
                                SwitchPreference(
                                    title = stringResource(R.string.auto_play_pause),
                                    summary = stringResource(R.string.auto_play_pause_summary),
                                    checked = autoPlayPause,
                                    onCheckedChange = onAutoPlayPauseChange,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (dualDeviceVisible || connectedDevicesVisible) {
            item {
                androidx.compose.animation.AnimatedVisibility(
                    visible = dualDeviceVisible || connectedDevicesVisible,
                    enter = androidx.compose.animation.expandVertically() +
                            androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() +
                            androidx.compose.animation.fadeOut(),
                ) {
                    SmallTitle(text = stringResource(R.string.features_section_devices))
                }
            }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Column {
                        if (dualDeviceVisible) {
                            SwitchPreference(
                                title = stringResource(R.string.dual_device),
                                summary = stringResource(R.string.dual_device_summary),
                                checked = dualDevice,
                                onCheckedChange = onDualDeviceChange,
                            )
                        }
                        if (connectedDevicesVisible && connectedDevicesReceived) {
                            if (connectedDevices.isEmpty()) {
                                top.yukonga.miuix.kmp.basic.BasicComponent(
                                    title = stringResource(R.string.dual_device_no_device),
                                )
                            } else {
                                connectedDevices.forEach { device ->
                                    val status = when {
                                        device.active -> stringResource(R.string.dual_device_active)
                                        device.connected -> stringResource(R.string.dual_device_connected)
                                        else -> stringResource(R.string.dual_device_disconnected)
                                    }
                                    top.yukonga.miuix.kmp.basic.BasicComponent(
                                        title = device.name.ifEmpty { device.mac },
                                        summary = status,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
