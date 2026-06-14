package moe.chenxy.oppopods.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.chenxy.oppopods.MainActivity
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.AppRfcommController
import moe.chenxy.oppopods.pods.GameModeImplementation
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.RfcommConnectionMethod
import moe.chenxy.oppopods.pods.SpatialAudioMode
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

sealed interface Screen : NavKey {
    data object Home : Screen
    data object Settings : Screen
    data object AdvancedSettings : Screen
    data object About : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {}
) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }
    val spatialAudioMode = remember { mutableStateOf(SpatialAudioMode.OFF) }

    val prefs = remember { context.getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE) }
    val openHeyTap = remember { mutableStateOf(prefs.getBoolean("open_heytap", false)) }
    val milinkSpatialAudioOptionEnabled = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )
        )
    }
    // Adaptive模式偏好设置（持久化存储），默认开启
    val adaptiveMode = remember { mutableStateOf(prefs.getBoolean("adaptive_mode", true)) }
    val rfcommConnectionMethod = remember {
        mutableStateOf(
            RfcommConnectionMethod.fromPreference(
                prefs.getString(RfcommConnectionMethod.PREF_KEY, null)
            )
        )
    }
    val gameModeImplementation = remember {
        mutableStateOf(
            GameModeImplementation.fromPreference(
                prefs.getString(GameModeImplementation.PREF_KEY, null)
            )
        )
    }
    val showConnectionBatteryIsland = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND
            )
        )
    }
    val showConnectionPopup = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
                OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP
            )
        )
    }
    val connectionPopupDismissSeconds = remember {
        mutableStateOf(
            prefs.getInt(
                OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
            ).takeIf { it in OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS }
                ?: OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
        )
    }
    val showConnectionNotification = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION
            )
        )
    }
    val notificationIslandStyle = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
            )
        )
    }

    val appController = remember { AppRfcommController() }
    val appConnState by appController.connectionState.collectAsState()
    val appBattery by appController.batteryParams.collectAsState()
    val appAnc by appController.ancMode.collectAsState()
    val appDeviceName by appController.deviceName.collectAsState()
    val appGameMode by appController.gameMode.collectAsState()
    val appSpatialAudioMode by appController.spatialAudioMode.collectAsState()

    val isStandaloneConnected = appConnState == AppRfcommController.ConnectionState.CONNECTED
    val isConnecting = appConnState == AppRfcommController.ConnectionState.CONNECTING
    val isError = appConnState == AppRfcommController.ConnectionState.ERROR
    val canShowDetailPage = hookConnected.value || isStandaloneConnected

    val displayBattery = if (isStandaloneConnected) appBattery else batteryParams.value
    val displayAnc = if (isStandaloneConnected) appAnc else ancMode.value
    val displayGameMode = if (isStandaloneConnected) appGameMode else gameMode.value
    val displaySpatialAudioMode = if (isStandaloneConnected) appSpatialAudioMode else spatialAudioMode.value
    val displayTitle = when {
        hookConnected.value -> mainTitle.value
        isStandaloneConnected -> appDeviceName
        isConnecting -> stringResource(R.string.connecting)
        else -> ""
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            else -> NoiseControlMode.OFF
                        }
                    }

                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        p1.getParcelableExtra("status", BatteryParams::class.java)?.let {
                            batteryParams.value = it
                        }
                    }

                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        spatialAudioMode.value = p1.getIntExtra("mode", SpatialAudioMode.OFF)
                            .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        mainTitle.value = deviceName ?: ""
                        hookConnected.value = true
                        Log.i("OppoPods", "pod connected via hook: $deviceName")
                    }

                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        hookConnected.value = false
                        if (p0 is MainActivity) {
                            p0.finish()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT).apply {
            setPackage("com.android.bluetooth")
        })
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage("com.android.bluetooth")
            putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, true)
        })

        onDispose {
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            appController.disconnect()
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        if (isStandaloneConnected) {
            appController.setANCMode(mode)
            return
        }
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setGameMode(enabled)
            return
        }
        gameMode.value = enabled
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        if (isStandaloneConnected) {
            appController.setSpatialAudioMode(normalizedMode)
            return
        }
        spatialAudioMode.value = normalizedMode
        Intent(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            this.putExtra("mode", normalizedMode)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        appController.connect(
            device = device,
            connectionMethod = rfcommConnectionMethod.value,
            gameModeImplementation = gameModeImplementation.value
        )
    }

    fun refreshStatus() {
        if (isStandaloneConnected) {
            appController.refreshStatus()
        } else if (hookConnected.value) {
            context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth")
                putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, true)
            })
        }
    }

    fun broadcastNotificationSettings(
        showConnectionBatteryIslandEnabled: Boolean,
        showConnectionPopupEnabled: Boolean,
        connectionPopupDismissSecondsValue: Int,
        showConnectionNotificationEnabled: Boolean,
        notificationIslandStyleEnabled: Boolean
    ) {
        val settings = NotificationSettings(
            showConnectionBatteryIsland = showConnectionBatteryIslandEnabled,
            showConnectionPopup = showConnectionPopupEnabled,
            connectionPopupDismissSeconds = connectionPopupDismissSecondsValue,
            showConnectionNotification = showConnectionNotificationEnabled,
            notificationIslandStyle = notificationIslandStyleEnabled
        )
        listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { targetPackage ->
            Intent(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED).apply {
                setPackage(targetPackage)
                settings.putExtras(this)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    fun broadcastMilinkSpatialAudioOption(enabled: Boolean) {
        listOf("com.milink.service", "com.android.settings").forEach { targetPackage ->
            Intent(OppoPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED).apply {
                setPackage(targetPackage)
                putExtra(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        }
    }

    // Each entry has its own Scaffold+TopAppBar so the full page transitions together
    val entryProvider = entryProvider<Screen> {
        entry<Screen.Home> {
            val homeTitle = mainTitle.value.ifEmpty { stringResource(R.string.app_name) }
            val topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = homeTitle,
                        largeTitle = homeTitle,
                        scrollBehavior = topAppBarScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { (context as? Activity)?.finish() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        },
                        actions = {
                            if (canShowDetailPage) {
                                IconButton(onClick = { refreshStatus() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Refresh,
                                        contentDescription = "Refresh"
                                    )
                                }
                            }
                            IconButton(
                                onClick = { backStack.add(Screen.Settings) },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Settings,
                                    contentDescription = "Settings"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                AnimatedContent(
                    targetState = when {
                        canShowDetailPage -> "detail"
                        isConnecting -> "connecting"
                        isError -> "error"
                        else -> "picker"
                    },
                    label = "MainPageAnim"
                ) { state ->
                    when (state) {
                        "detail" -> PodDetailPage(
                            modifier = Modifier
                                .overScrollVertical()
                                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                            contentPadding = padding,
                            batteryParams = displayBattery,
                            ancMode = displayAnc,
                            onAncModeChange = { setAncMode(it) },
                            gameMode = displayGameMode,
                            onGameModeChange = { setGameMode(it) },
                            spatialAudioMode = displaySpatialAudioMode,
                            onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                            adaptiveModeEnabled = adaptiveMode.value
                        )
                        "connecting" -> Box(Modifier.padding(padding).fillMaxSize()) { ConnectingPage() }
                        "error" -> Box(Modifier.padding(padding).fillMaxSize()) { ErrorPage(onRetry = { appController.disconnect() }) }
                        else -> Box(Modifier.padding(padding).fillMaxSize()) { DevicePickerPage(onDeviceSelected = { onDeviceSelected(it) }) }
                    }
                }
            }
        }
        entry<Screen.Settings> {
            val settingsScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.settings),
                        largeTitle = stringResource(R.string.settings),
                        scrollBehavior = settingsScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                SettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(settingsScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    adaptiveMode = adaptiveMode,
                    onAdaptiveModeChange = {
                        adaptiveMode.value = it
                        prefs.edit().putBoolean("adaptive_mode", it).apply()
                        // 广播 Adaptive 模式状态变更到蓝牙进程，确保跨进程实时同步
                        listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { targetPackage ->
                            Intent(OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED).apply {
                                setPackage(targetPackage)
                                putExtra("enabled", it)
                                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                context.sendBroadcast(this)
                            }
                        }
                        // 关闭Adaptive模式时，若当前处于Adaptive模式则自动切换至降噪模式
                        if (!it && displayAnc == NoiseControlMode.ADAPTIVE) {
                            setAncMode(NoiseControlMode.NOISE_CANCELLATION)
                        }
                    },
                    showConnectionBatteryIsland = showConnectionBatteryIsland,
                    onShowConnectionBatteryIslandChange = {
                        showConnectionBatteryIsland.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, it)
                            .commit()
                        broadcastNotificationSettings(
                            it,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    showConnectionNotification = showConnectionNotification,
                    onShowConnectionNotificationChange = {
                        showConnectionNotification.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            it,
                            notificationIslandStyle.value
                        )
                    },
                    notificationIslandStyle = notificationIslandStyle,
                    onNotificationIslandStyleChange = {
                        notificationIslandStyle.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            showConnectionPopup.value,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            it
                        )
                    },
                    onOpenAdvancedSettings = { backStack.add(Screen.AdvancedSettings) },
                    onOpenAbout = { backStack.add(Screen.About) }
                )
            }
        }
        entry<Screen.AdvancedSettings> {
            val advancedScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.advanced_settings),
                        largeTitle = stringResource(R.string.advanced_settings),
                        scrollBehavior = advancedScrollBehavior,
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { padding ->
                AdvancedSettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(advancedScrollBehavior.nestedScrollConnection),
                    contentPadding = padding,
                    openHeyTap = openHeyTap,
                    onOpenHeyTapChange = {
                        openHeyTap.value = it
                        prefs.edit().putBoolean("open_heytap", it).apply()
                    },
                    rfcommConnectionMethod = rfcommConnectionMethod,
                    onRfcommConnectionMethodChange = {
                        rfcommConnectionMethod.value = it
                        prefs.edit()
                            .putString(RfcommConnectionMethod.PREF_KEY, it.preferenceValue)
                            .apply()
                    },
                    gameModeImplementation = gameModeImplementation,
                    onGameModeImplementationChange = {
                        gameModeImplementation.value = it
                        appController.setGameModeImplementation(it)
                        prefs.edit()
                            .putString(GameModeImplementation.PREF_KEY, it.preferenceValue)
                            .apply()
                        Intent(OppoPodsAction.ACTION_GAME_MODE_IMPLEMENTATION_CHANGED).apply {
                            setPackage("com.android.bluetooth")
                            putExtra(GameModeImplementation.PREF_KEY, it.preferenceValue)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            context.sendBroadcast(this)
                        }
                    },
                    showConnectionPopup = showConnectionPopup,
                    onShowConnectionPopupChange = {
                        showConnectionPopup.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_POPUP, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            it,
                            connectionPopupDismissSeconds.value,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    connectionPopupDismissSeconds = connectionPopupDismissSeconds,
                    onConnectionPopupDismissSecondsChange = {
                        connectionPopupDismissSeconds.value = it
                        prefs.edit()
                            .putInt(OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            showConnectionPopup.value,
                            it,
                            showConnectionNotification.value,
                            notificationIslandStyle.value
                        )
                    },
                    milinkSpatialAudioOptionEnabled = milinkSpatialAudioOptionEnabled,
                    onMilinkSpatialAudioOptionEnabledChange = {
                        milinkSpatialAudioOptionEnabled.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, it)
                            .commit()
                        broadcastMilinkSpatialAudioOption(it)
                    }
                )
            }
        }
        entry<Screen.About> {
            AboutPage(
                onBack = { backStack.removeLast() }
            )
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        }
    )
}

@Composable
fun ConnectingPage() {
    val primaryColor = MiuixTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(48.dp)) {
                drawArc(
                    color = primaryColor,
                    startAngle = angle,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Text(
                stringResource(R.string.connecting),
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun ErrorPage(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.connect_failed),
                color = Color(0xFFFF3B30)
            )
            TextButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
