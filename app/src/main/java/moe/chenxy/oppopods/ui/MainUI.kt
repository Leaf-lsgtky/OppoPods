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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
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

sealed interface Screen : NavKey {
    data object Home : Screen
    data object Settings : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI() {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val context = LocalContext.current

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }

    // Auto game mode preference (persisted)
    val prefs = remember { context.getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE) }
    val autoGameMode = remember { mutableStateOf(prefs.getBoolean("auto_game_mode", false)) }

    val appController = remember { AppRfcommController() }
    val appConnState by appController.connectionState.collectAsState()
    val appBattery by appController.batteryParams.collectAsState()
    val appAnc by appController.ancMode.collectAsState()
    val appDeviceName by appController.deviceName.collectAsState()
    val appGameMode by appController.gameMode.collectAsState()

    val isStandaloneConnected = appConnState == AppRfcommController.ConnectionState.CONNECTED
    val isConnecting = appConnState == AppRfcommController.ConnectionState.CONNECTING
    val isError = appConnState == AppRfcommController.ConnectionState.ERROR
    val canShowDetailPage = hookConnected.value || isStandaloneConnected

    val displayBattery = if (isStandaloneConnected) appBattery else batteryParams.value
    val displayAnc = if (isStandaloneConnected) appAnc else ancMode.value
    val displayGameMode = if (isStandaloneConnected) appGameMode else gameMode.value
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
                            else -> NoiseControlMode.OFF
                        }
                    }

                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryParams.value =
                            p1.getParcelableExtra("status", BatteryParams::class.java)!!
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
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT))

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
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
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
            context.sendBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        appController.connect(device)
    }

    fun refreshStatus() {
        if (isStandaloneConnected) {
            appController.refreshStatus()
        } else if (hookConnected.value) {
            context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS))
        }
    }

    // Auto-enable game mode when connected
    LaunchedEffect(canShowDetailPage) {
        if (canShowDetailPage && autoGameMode.value) {
            delay(100)
            setGameMode(true)
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
                                modifier = Modifier.padding(start = 16.dp)
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
                                modifier = Modifier.padding(end = 16.dp)
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
                    Box(modifier = Modifier.padding(padding)) {
                        when (state) {
                            "detail" -> PodDetailPage(
                                batteryParams = displayBattery,
                                ancMode = displayAnc,
                                onAncModeChange = { setAncMode(it) },
                                gameMode = displayGameMode,
                                onGameModeChange = { setGameMode(it) }
                            )
                            "connecting" -> ConnectingPage()
                            "error" -> ErrorPage(onRetry = { appController.disconnect() })
                            else -> DevicePickerPage(onDeviceSelected = { onDeviceSelected(it) })
                        }
                    }
                }
            }
        }
        entry<Screen.Settings> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.settings),
                        navigationIcon = {
                            IconButton(
                                onClick = { backStack.removeLast() },
                                modifier = Modifier.padding(start = 16.dp)
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
                    modifier = Modifier.padding(padding),
                    autoGameMode = autoGameMode,
                    onAutoGameModeChange = {
                        autoGameMode.value = it
                        prefs.edit().putBoolean("auto_game_mode", it).apply()
                    }
                )
            }
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
