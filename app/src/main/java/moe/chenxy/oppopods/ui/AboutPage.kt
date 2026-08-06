package moe.chenxy.oppopods.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.CustomButtonFunction
import moe.chenxy.oppopods.pods.CustomButtonPosition
import moe.chenxy.oppopods.pods.RfcommConnectionMethod
import moe.chenxy.oppopods.ui.effect.BgEffectBackground
import moe.chenxy.oppopods.ui.effect.ColorBlendToken
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import kotlinx.coroutines.flow.onEach
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    showConnectionBatteryIsland: MutableState<Boolean> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND),
    onShowConnectionBatteryIslandChange: (Boolean) -> Unit = {},
    showConnectionNotification: MutableState<Boolean> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION),
    onShowConnectionNotificationChange: (Boolean) -> Unit = {},
    notificationIslandStyle: MutableState<Boolean> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE),
    onNotificationIslandStyleChange: (Boolean) -> Unit = {},
    onOpenAdvancedSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    debugMode: Boolean = false,
    onOpenDebug: () -> Unit = {}
) {
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme_title),
                    items = themeOptions,
                    selectedIndex = themeMode.value,
                    onSelectedIndexChange = { onThemeModeChange(it) }
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.show_connection_battery_island),
                    summary = stringResource(R.string.show_connection_battery_island_summary),
                    checked = showConnectionBatteryIsland.value,
                    onCheckedChange = { onShowConnectionBatteryIslandChange(it) }
                )
                SwitchPreference(
                    title = stringResource(R.string.show_connection_notification),
                    summary = stringResource(R.string.show_connection_notification_summary),
                    checked = showConnectionNotification.value,
                    onCheckedChange = { onShowConnectionNotificationChange(it) }
                )
                SwitchPreference(
                    title = stringResource(R.string.notification_island_style),
                    summary = stringResource(R.string.notification_island_style_summary),
                    checked = notificationIslandStyle.value,
                    onCheckedChange = { onNotificationIslandStyleChange(it) },
                    enabled = showConnectionNotification.value
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.device_profiles),
                    onClick = onOpenProfiles
                )
                ArrowPreference(
                    title = stringResource(R.string.advanced_settings),
                    onClick = onOpenAdvancedSettings
                )
                ArrowPreference(
                    title = stringResource(R.string.about),
                    onClick = onOpenAbout
                )
            }
        }

        if (debugMode) {
            item {
                Card(modifier = Modifier.padding(top = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.debug_mode),
                        onClick = onOpenDebug
                    )
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    openHeyTap: MutableState<Boolean> = mutableStateOf(false),
    onOpenHeyTapChange: (Boolean) -> Unit = {},
    rfcommConnectionMethod: MutableState<RfcommConnectionMethod> = mutableStateOf(RfcommConnectionMethod.UUID),
    onRfcommConnectionMethodChange: (RfcommConnectionMethod) -> Unit = {},
    adaptiveVisible: Boolean = true,
    spatialAudioVisible: Boolean = false,
    spatialSoundVisible: Boolean = false,
    showConnectionPopup: MutableState<Boolean> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP),
    onShowConnectionPopupChange: (Boolean) -> Unit = {},
    connectionPopupDismissSeconds: MutableState<Int> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS),
    onConnectionPopupDismissSecondsChange: (Int) -> Unit = {},
    milinkSpatialAudioOptionEnabled: MutableState<Boolean> =
        mutableStateOf(OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED),
    onMilinkSpatialAudioOptionEnabledChange: (Boolean) -> Unit = {},
    customButtonFunction: MutableState<CustomButtonFunction> =
        mutableStateOf(CustomButtonFunction.GAME_MODE),
    onCustomButtonFunctionChange: (CustomButtonFunction) -> Unit = {},
    customButtonPosition: MutableState<CustomButtonPosition> =
        mutableStateOf(CustomButtonPosition.UPPER),
    onCustomButtonPositionChange: (CustomButtonPosition) -> Unit = {}
) {
    val rfcommConnectionOptions = listOf(
        stringResource(R.string.rfcomm_connection_method_uuid),
        stringResource(R.string.rfcomm_connection_method_channel)
    )
    val customButtonFunctionOptions = mutableListOf(
        stringResource(R.string.custom_button_function_none),
        stringResource(R.string.custom_button_function_game_mode)
    ).apply {
        if (adaptiveVisible) add(stringResource(R.string.custom_button_function_adaptive))
        if (spatialSoundVisible) add(stringResource(R.string.custom_button_function_spatial_sound))
    }
    val customButtonPositionOptions = listOf(
        stringResource(R.string.custom_button_position_upper),
        stringResource(R.string.custom_button_position_lower)
    )
    val popupDismissSecondOptions = OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS
    val popupDismissSecondLabels = popupDismissSecondOptions.map {
        stringResource(R.string.connection_popup_duration_seconds, it)
    }
    val popupDismissSecondSelectedIndex = popupDismissSecondOptions
        .indexOf(connectionPopupDismissSeconds.value)
        .takeIf { it >= 0 }
        ?: popupDismissSecondOptions.indexOf(OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS)

    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.custom_button_function),
                    items = customButtonFunctionOptions,
                    selectedIndex = run {
                        val enumIndex = CustomButtonFunction.selectedIndexOf(customButtonFunction.value)
                        // Build list of visible enum indices in order
                        val visibleIndices = mutableListOf(0, 1) // NONE, GAME_MODE always visible
                        if (adaptiveVisible) visibleIndices.add(2) // ADAPTIVE
                        if (spatialSoundVisible) visibleIndices.add(3) // SPATIAL_SOUND
                        visibleIndices.indexOf(enumIndex).takeIf { it >= 0 } ?: 0
                    },
                    onSelectedIndexChange = { index ->
                        val visibleIndices = mutableListOf(0, 1)
                        if (adaptiveVisible) visibleIndices.add(2)
                        if (spatialSoundVisible) visibleIndices.add(3)
                        val enumIndex = visibleIndices.getOrElse(index) { index }
                        onCustomButtonFunctionChange(CustomButtonFunction.fromSelectedIndex(enumIndex))
                    }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.custom_button_position),
                    items = customButtonPositionOptions,
                    selectedIndex = CustomButtonPosition.selectedIndexOf(customButtonPosition.value),
                    onSelectedIndexChange = {
                        onCustomButtonPositionChange(CustomButtonPosition.fromSelectedIndex(it))
                    }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.rfcomm_connection_method),
                    items = rfcommConnectionOptions,
                    selectedIndex = RfcommConnectionMethod.selectedIndexOf(rfcommConnectionMethod.value),
                    onSelectedIndexChange = {
                        onRfcommConnectionMethodChange(RfcommConnectionMethod.fromSelectedIndex(it))
                    }
                )
                SwitchPreference(
                    title = stringResource(R.string.show_connection_popup),
                    summary = stringResource(R.string.show_connection_popup_summary),
                    checked = showConnectionPopup.value,
                    onCheckedChange = { onShowConnectionPopupChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.connection_popup_dismiss_duration),
                    items = popupDismissSecondLabels,
                    selectedIndex = popupDismissSecondSelectedIndex,
                    onSelectedIndexChange = {
                        onConnectionPopupDismissSecondsChange(popupDismissSecondOptions[it])
                    },
                    enabled = showConnectionPopup.value
                )
                SwitchPreference(
                    title = stringResource(R.string.open_heytap),
                    summary = stringResource(R.string.open_heytap_summary),
                    checked = openHeyTap.value,
                    onCheckedChange = { onOpenHeyTapChange(it) }
                )
                if (spatialAudioVisible) {
                    SwitchPreference(
                        title = stringResource(R.string.milink_spatial_audio_option),
                        checked = milinkSpatialAudioOptionEnabled.value,
                        onCheckedChange = { onMilinkSpatialAudioOptionEnabledChange(it) }
                    )
                }
            }
        }
    }
}

@Composable
fun MoreSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    autoPlayPauseVisible: Boolean = false,
    autoPlayPause: Boolean = false,
    onAutoPlayPauseChange: (Boolean) -> Unit = {},
    dualDeviceVisible: Boolean = false,
    dualDevice: Boolean = false,
    onDualDeviceChange: (Boolean) -> Unit = {},
    connectedDevicesVisible: Boolean = false,
    connectedDevices: List<moe.chenxy.oppopods.pods.ConnectedDevice> = emptyList(),
    connectedDevicesReceived: Boolean = false
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().scrollEndHaptic(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        overscrollEffect = null,
    ) {
        item {
            Card {
                if (autoPlayPauseVisible) {
                    SwitchPreference(
                        title = stringResource(R.string.auto_play_pause),
                        summary = stringResource(R.string.auto_play_pause_summary),
                        checked = autoPlayPause,
                        onCheckedChange = onAutoPlayPauseChange
                    )
                }
                if (dualDeviceVisible) {
                    SwitchPreference(
                        title = stringResource(R.string.dual_device),
                        summary = stringResource(R.string.dual_device_summary),
                        checked = dualDevice,
                        onCheckedChange = onDualDeviceChange
                    )
                }
                if (connectedDevicesVisible) {
                    if (!connectedDevicesReceived) {
                        // 等待耳机推送设备列表
                    } else if (connectedDevices.isEmpty()) {
                        BasicComponent(
                            title = stringResource(R.string.dual_device_no_device)
                        )
                    } else {
                        connectedDevices.forEach { device ->
                            val statusText = when {
                                device.active -> stringResource(R.string.dual_device_active)
                                device.connected -> stringResource(R.string.dual_device_connected)
                                else -> stringResource(R.string.dual_device_disconnected)
                            }
                            BasicComponent(
                                title = device.name.ifEmpty { device.mac },
                                summary = statusText
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    debugMode: Boolean = false,
    onDebugModeChanged: (Boolean) -> Unit = {}
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }
    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                val offset = lazyListState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }
    val barBlurBackdrop = rememberBlurBackdrop(enableBlur = true)
    val blurActive = barBlurBackdrop != null && scrollProgress == 1f
    val barColor = if (blurActive) {
        Color.Transparent
    } else {
        if (scrollProgress == 1f) MiuixTheme.colorScheme.surface else Color.Transparent
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = barBlurBackdrop, blurActive = blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.about),
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(
                        alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    ),
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (barBlurBackdrop != null) Modifier.layerBackdrop(barBlurBackdrop) else Modifier) {
            AboutContent(
                modifier = Modifier.fillMaxSize(),
                innerPadding = innerPadding,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                lazyListState = lazyListState,
                scrollProgress = scrollProgress,
                onLogoHeightChanged = { logoHeightPx = it },
                debugMode = debugMode,
                onDebugModeChanged = onDebugModeChanged
            )
        }
    }
}

@Composable
private fun AboutContent(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgress: Float,
    onLogoHeightChanged: (Int) -> Unit,
    debugMode: Boolean = false,
    onDebugModeChanged: (Boolean) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val context = LocalContext.current
    var debugClickCount by remember { mutableIntStateOf(0) }
    val backdrop = rememberLayerBackdrop()
    val isDark = isSystemInDarkTheme()
    val blurEnabled = isRenderEffectSupported()
    val effectBackground = remember(blurEnabled) {
        blurEnabled &&
            isRuntimeShaderSupported() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }
    val cardBlend = remember(isDark) {
        if (isDark) ColorBlendToken.Overlay_Thin_Light
        else ColorBlendToken.Pured_Regular_Light
    }
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    var logoAreaY by remember { mutableFloatStateOf(0f) }
    var iconY by remember { mutableFloatStateOf(0f) }
    var projectNameY by remember { mutableFloatStateOf(0f) }
    var versionCodeY by remember { mutableFloatStateOf(0f) }

    var iconProgress by remember { mutableFloatStateOf(0f) }
    var projectNameProgress by remember { mutableFloatStateOf(0f) }
    var versionCodeProgress by remember { mutableFloatStateOf(0f) }
    var initialLogoAreaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .onEach { offset ->
                if (lazyListState.firstVisibleItemIndex > 0) {
                    if (iconProgress != 1f) iconProgress = 1f
                    if (projectNameProgress != 1f) projectNameProgress = 1f
                    if (versionCodeProgress != 1f) versionCodeProgress = 1f
                    return@onEach
                }

                if (initialLogoAreaY == 0f && logoAreaY > 0f) {
                    initialLogoAreaY = logoAreaY
                }
                val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY

                val stage1TotalLength = refLogoAreaY - versionCodeY
                val stage2TotalLength = versionCodeY - projectNameY
                val stage3TotalLength = projectNameY - iconY

                val versionCodeDelay = stage1TotalLength * 0.5f
                versionCodeProgress =
                    ((offset.toFloat() - versionCodeDelay) / (stage1TotalLength - versionCodeDelay).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                projectNameProgress =
                    ((offset.toFloat() - stage1TotalLength) / stage2TotalLength.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                iconProgress =
                    ((offset.toFloat() - stage1TotalLength - stage2TotalLength) / stage3TotalLength.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
            }
            .collect { }
    }

    val scrollPadding = PaddingValues(
        top = innerPadding.calculateTopPadding(),
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )
    val logoPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )

    BgEffectBackground(
        dynamicBackground = effectBackground,
        modifier = modifier,
        bgModifier = Modifier.layerBackdrop(backdrop),
        isFullSize = true,
        effectBackground = effectBackground,
        alpha = { 1f - scrollProgress },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(layoutDirection),
                    end = logoPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (iconY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        iconY = y + size.height
                    }
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    modifier = Modifier
                        .requiredSize(160.dp)
                        .then(
                            if (blurEnabled) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 200f,
                                    colors = BlurColors(blendColors = logoBlend),
                                    contentBlendMode = BlendMode.DstIn,
                                    enabled = true,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                    contentDescription = stringResource(R.string.app_name),
                )
            }
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .onGloballyPositioned { coordinates ->
                        if (projectNameY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        projectNameY = y + size.height
                    }
                    .graphicsLayer {
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (blurEnabled) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                colors = BlurColors(blendColors = logoBlend),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            )
                        } else {
                            Modifier
                        }
                    ),
                text = stringResource(R.string.app_name),
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (versionCodeY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        versionCodeY = y + size.height
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = BuildConfig.VERSION_NAME,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateStartPadding(layoutDirection),
                end = scrollPadding.calculateEndPadding(layoutDirection),
            ),
            overscrollEffect = null,
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp +
                                52.dp +
                                logoPadding.calculateTopPadding() -
                                scrollPadding.calculateTopPadding() +
                                126.dp
                        )
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val iconSizePx = with(density) { 88.dp.toPx() }
                                val iconTopPx = with(density) { (40.dp + 52.dp).toPx() }
                                val spacerWidth = size.width.toFloat()
                                val iconLeft = (spacerWidth - iconSizePx) / 2
                                val iconRight = iconLeft + iconSizePx

                                if (offset.x in iconLeft..iconRight && offset.y in iconTopPx..(iconTopPx + iconSizePx)) {
                                    debugClickCount++
                                    if (debugClickCount >= 5) {
                                        debugClickCount = 0
                                        val newState = !debugMode
                                        onDebugModeChanged(newState)
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (newState) R.string.debug_mode_enabled
                                                else R.string.debug_mode_disabled
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                        .onSizeChanged { size -> onLogoHeightChanged(size.height) }
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            logoAreaY = y + size.height
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }

            item(key = "aboutContent") {
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(bottom = innerPadding.calculateBottomPadding() + 12.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                            .then(
                                if (blurEnabled) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        colors = BlurColors(blendColors = cardBlend),
                                        enabled = true,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.defaultColors(
                            if (blurEnabled) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.project_address),
                            onClick = { uriHandler.openUri("https://github.com/Leaf-lsgtky/OppoPods") }
                        )
                    }

                    SmallTitle(text = stringResource(R.string.author))

                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                            .then(
                                if (blurEnabled) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        colors = BlurColors(blendColors = cardBlend),
                                        enabled = true,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.defaultColors(
                            if (blurEnabled) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = "Leaf",
                            onClick = { uriHandler.openUri("https://github.com/Leaf-lsgtky/") }
                        )
                        ArrowPreference(
                            title = "zz1812",
                            onClick = { uriHandler.openUri("https://www.coolapk.com/u/2370747") }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.author_star_zero),
                            onClick = { uriHandler.openUri("https://www.coolapk.com/u/2380718") }
                        )
                        ArrowPreference(
                            title = "Zhaoyi-ya",
                            onClick = { uriHandler.openUri("https://github.com/Zhaoyi-ya") }
                        )
                    }

                    SmallTitle(text = stringResource(R.string.original_project))

                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                            .then(
                                if (blurEnabled) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        colors = BlurColors(blendColors = cardBlend),
                                        enabled = true,
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        colors = CardDefaults.defaultColors(
                            if (blurEnabled) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = "Art-Chen/HyperPods",
                            onClick = { uriHandler.openUri("https://github.com/Art-Chen/HyperPods") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
private fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.87f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
