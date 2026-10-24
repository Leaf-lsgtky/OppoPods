package moe.chenxy.oppopods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import moe.chenxy.oppopods.MainActivity
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.AppRfcommController
import moe.chenxy.oppopods.pods.BtLogStore
import moe.chenxy.oppopods.pods.CalibrationCommand
import moe.chenxy.oppopods.pods.DeviceAppearanceStore
import moe.chenxy.oppopods.pods.ModelColorCatalog
import moe.chenxy.oppopods.pods.OfficialArtworkRepository
import moe.chenxy.oppopods.pods.CalibrationState
import moe.chenxy.oppopods.pods.CustomButtonFunction
import moe.chenxy.oppopods.pods.CustomButtonPosition
import moe.chenxy.oppopods.pods.DeviceProfile
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.pods.EqDevicePreset
import moe.chenxy.oppopods.pods.EqPreset
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.pods.OfficialArtworkStore
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.RfcommConnectionMethod
import moe.chenxy.oppopods.pods.SpatialAudioMode
import moe.chenxy.oppopods.utils.SaveOnHideEffect
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import moe.chenxy.oppopods.ui.components.DisabledContent
import moe.chenxy.oppopods.ui.components.PrimaryPageScaffold
import moe.chenxy.oppopods.ui.components.SecondaryPageScaffold
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.serialization.json.Json
import moe.chenxy.oppopods.pods.HearingProfile
import moe.chenxy.oppopods.pods.HearingProfileStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Home : Screen
    @Serializable data object Theme : Screen
    @Serializable data object AdvancedSettings : Screen
    @Serializable data object Profiles : Screen
    @Serializable data object About : Screen
    @Serializable data object Equalizer : Screen
    @Serializable data object FitTest : Screen
    @Serializable data object Hearing : Screen
    @Serializable data object Debug : Screen
    @Serializable data object DebugLog : Screen
    /** 设备选择页：从首页顶栏的「+」进入，也可以从「立刻连接」跳转。 */
    @Serializable data object DevicePicker : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    themeConfig: MutableState<AppThemeConfig> = mutableStateOf(AppThemeConfig()),
    onThemeConfigChange: (AppThemeConfig) -> Unit = {}
) {
    val backStack = rememberNavBackStack<Screen>(Screen.Home)
    val context = LocalContext.current

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }
    val hookEqPresetId = remember { mutableStateOf(-1) }
    val hookDeviceEqPresets = remember { mutableStateOf<List<EqDevicePreset>>(emptyList()) }
    val spatialAudioMode = remember { mutableStateOf(SpatialAudioMode.OFF) }
    val spatialSound = remember { mutableStateOf(false) }
    val noiseLevel = remember { mutableStateOf(moe.chenxy.oppopods.pods.NoiseLevel.DEEP) }
    val smartAncLevel = remember { mutableStateOf(-1) }
    val autoPlayPause = remember { mutableStateOf(false) }
    val dualDevice = remember { mutableStateOf(false) }
    val hookConnectedDevices = remember { mutableStateOf<List<moe.chenxy.oppopods.pods.ConnectedDevice>>(emptyList()) }
    val hookConnectedDevicesReceived = remember { mutableStateOf(false) }

    val prefs = remember {
        context.getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE)
    }
    val openHeyTap = remember { mutableStateOf(prefs.getBoolean("open_heytap", false)) }
    val milinkSpatialAudioOptionEnabled = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED,
                OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
            )
        )
    }
    val rfcommConnectionMethod = remember {
        mutableStateOf(
            RfcommConnectionMethod.fromPreference(
                prefs.getString(RfcommConnectionMethod.PREF_KEY, null)
            )
        )
    }
    val customButtonFunction = remember {
        mutableStateOf(
            CustomButtonFunction.fromPreference(
                prefs.getString(CustomButtonFunction.PREF_KEY, null)
            )
        )
    }
    val customButtonPosition = remember {
        mutableStateOf(
            CustomButtonPosition.fromPreference(
                prefs.getString(CustomButtonPosition.PREF_KEY, null)
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
    val temporaryBatteryIslandDurationSeconds = remember {
        mutableStateOf(
            prefs.getInt(
                OppoPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS,
                OppoPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
            ).takeIf {
                it in OppoPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECOND_OPTIONS
            } ?: OppoPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
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
    val showHeadsetStatusBarIcon = remember {
        mutableStateOf(
            prefs.getBoolean(
                OppoPodsPrefsKey.SHOW_HEADSET_STATUS_BAR_ICON,
                OppoPodsPrefsKey.DEFAULT_SHOW_HEADSET_STATUS_BAR_ICON
            )
        )
    }

    // 未连接时展示「上一次连接过的耳机」的页面，所以地址、名称与 productId 都要持久化。
    // 只在用户主动选中设备或设备上报 productId 时写入，避免失败目标覆盖记录。
    val lastDeviceAddress = remember {
        mutableStateOf(prefs.getString(OppoPodsPrefsKey.LAST_DEVICE_ADDRESS, null))
    }
    val lastDeviceName = remember {
        mutableStateOf(prefs.getString(OppoPodsPrefsKey.LAST_DEVICE_NAME, null))
    }
    val lastProductId = remember {
        mutableStateOf(prefs.getString(OppoPodsPrefsKey.LAST_DEVICE_PRODUCT_ID, null))
    }

    // Hook 连接路径不会经过 onDeviceSelected；先按当前配置模式解析一次，连接广播
    // 到达后再按实际蓝牙名称刷新，避免旧的持久化种子配置残留可见性开关。
    // 未连接时必须用上次连接的耳机回推能力，否则会落到 FALLBACK 配置——功能页
    // 只剩游戏模式等少数几项，而不是这台耳机实际支持的全部功能。
    val activeProfile = remember {
        mutableStateOf(
            lastProductId.value
                ?.let { DeviceProfileStore.profileForProductId(context, prefs, it) }
                ?: DeviceProfileStore.resolveProfile(context, prefs, lastDeviceName.value)
        )
    }
    val navRailExpanded = remember { mutableStateOf(prefs.getBoolean(OppoPodsPrefsKey.NAV_RAIL_EXPANDED, false)) }

    val debugMode = remember { mutableStateOf(prefs.getBoolean("debug_mode", false)) }
    val loggingEnabled = remember { mutableStateOf(prefs.getBoolean("bt_logging_enabled", false)) }
    BtLogStore.isEnabled = loggingEnabled.value
    val appController = remember { AppRfcommController() }
    // 收到 0x8103 后按 productId 在内嵌白名单里精确命中并重建配置（仅自动模式生效）。
    // 顺便持久化，供下次未连接时还原同样的能力集合。
    SideEffect {
        appController.productIdResolver = { productId ->
            prefs.edit()
                .putString(OppoPodsPrefsKey.LAST_DEVICE_PRODUCT_ID, productId)
                .commit()
            lastProductId.value = productId
            DeviceProfileStore.profileForProductId(context, prefs, productId)
                ?.also { activeProfile.value = it }
        }
    }
    val appConnState by appController.connectionState.collectAsState()
    val appBattery by appController.batteryParams.collectAsState()
    val appAnc by appController.ancMode.collectAsState()
    val appDeviceName by appController.deviceName.collectAsState()
    val appGameMode by appController.gameMode.collectAsState()
    val appEqPresets by appController.eqPresets.collectAsState()
    val appEqDevicePresets by appController.eqDevicePresets.collectAsState()
    val appEqPresetId by appController.eqPresetId.collectAsState()
    val appSpatialAudioMode by appController.spatialAudioMode.collectAsState()
    val appSpatialSound by appController.spatialSound.collectAsState()
    val appNoiseLevel by appController.noiseLevel.collectAsState()
    val appSmartAncLevel by appController.smartAncLevel.collectAsState()
    val appAutoPlayPause by appController.autoPlayPause.collectAsState()
    val appDualDevice by appController.dualDevice.collectAsState()
    val appConnectedDevices by appController.connectedDevices.collectAsState()
    val appConnectedDevicesReceived by appController.connectedDevicesReceived.collectAsState()
    val appAncVariant by appController.ancVariant.collectAsState()
    val appDeviceColor by appController.deviceColor.collectAsState()
    val appCalibration by appController.calibration.state.collectAsState()

    val hookAncVariant = remember { mutableIntStateOf(-1) }
    val hookDeviceColor = remember {
        mutableStateOf(
            DeviceAppearanceStore.getColor(context, lastDeviceAddress.value, activeProfile.value.id)
        )
    }
    val hookCalibration = remember { mutableStateOf(CalibrationState()) }

    val isStandaloneConnected = appConnState == AppRfcommController.ConnectionState.CONNECTED
    val isConnecting = appConnState == AppRfcommController.ConnectionState.CONNECTING
    val isError = appConnState == AppRfcommController.ConnectionState.ERROR
    val canShowDetailPage = hookConnected.value || isStandaloneConnected
    // 未连接时不再切到独立的设备选择页，而是继续渲染上次设备的页面并整体置灰。
    // 连接中、连接失败时都保持置灰：进度只体现在「立刻连接」按钮上，页面不会突然亮起来。
    val keepContentGreyed = !canShowDetailPage

    val displayBattery = if (isStandaloneConnected) appBattery else batteryParams.value
    val displayAnc = if (isStandaloneConnected) appAnc else ancMode.value
    val displayGameMode = if (isStandaloneConnected) appGameMode else gameMode.value
    val displayEqPresets = if (isStandaloneConnected) {
        appEqPresets
    } else {
        buildList {
            val byId = LinkedHashMap<Int, EqPreset>()
            activeProfile.value.eqPresets.forEach { byId[it.id] = it }
            hookDeviceEqPresets.value.forEach { entry ->
                if (entry.name.isNotBlank()) byId[entry.id] = EqPreset(entry.id, entry.name)
            }
            addAll(byId.values.sortedBy { it.id })
        }
    }
    val displayEqDevicePresets = if (isStandaloneConnected) {
        appEqDevicePresets
    } else {
        hookDeviceEqPresets.value
    }
    val displayEqPresetId = if (isStandaloneConnected) appEqPresetId else hookEqPresetId.value
    val displayEqCurrentName = displayEqPresets.firstOrNull { it.id == displayEqPresetId }?.name.orEmpty()
    val displaySpatialAudioMode = if (isStandaloneConnected) appSpatialAudioMode else spatialAudioMode.value
    val displaySpatialSound = if (isStandaloneConnected) appSpatialSound else spatialSound.value
    val displayNoiseLevel = if (isStandaloneConnected) appNoiseLevel else noiseLevel.value
    val displaySmartAncLevel = if (isStandaloneConnected) appSmartAncLevel else smartAncLevel.value
    val displayAutoPlayPause = if (isStandaloneConnected) appAutoPlayPause else autoPlayPause.value
    val displayDualDevice = if (isStandaloneConnected) appDualDevice else dualDevice.value
    val displayConnectedDevices = if (isStandaloneConnected) appConnectedDevices else hookConnectedDevices.value
    val displayConnectedDevicesReceived = if (isStandaloneConnected) appConnectedDevicesReceived else hookConnectedDevicesReceived.value
    val displayAncVariant = if (isStandaloneConnected) appAncVariant else hookAncVariant.value
    val displayDeviceColor = if (isStandaloneConnected) {
        appDeviceColor ?: hookDeviceColor.value
    } else {
        hookDeviceColor.value ?: DeviceAppearanceStore.getColor(context, lastDeviceAddress.value, activeProfile.value.id)
    }
    val displayCalibration = if (isStandaloneConnected) appCalibration else hookCalibration.value

    LaunchedEffect(appDeviceColor) {
        val color = appDeviceColor ?: return@LaunchedEffect
        DeviceAppearanceStore.saveColor(
            context,
            address = lastDeviceAddress.value,
            productId = activeProfile.value.id,
            color = color,
        )
    }

    LaunchedEffect(displayDeviceColor, activeProfile.value.modelId) {
        val color = displayDeviceColor ?: return@LaunchedEffect
        val modelId = activeProfile.value.modelId
        if (modelId.isNotBlank()) {
            OfficialArtworkRepository.syncColor(context, modelId, color)
        }
    }

    // 黄金听感历史：耳机只保留当前一份，多方案由手机侧按设备分开保存。
    val hearingDeviceKey = lastDeviceAddress.value
        ?: activeProfile.value.id.takeIf { it.isNotBlank() }
        ?: "default"
    val hearingProfiles = remember { mutableStateOf<List<HearingProfile>>(emptyList()) }
    LaunchedEffect(hearingDeviceKey) {
        hearingProfiles.value = HearingProfileStore.load(context, hearingDeviceKey)
    }
    LaunchedEffect(displayCalibration.savedProfile) {
        val saved = displayCalibration.savedProfile ?: return@LaunchedEffect
        hearingProfiles.value = HearingProfileStore.upsert(context, hearingDeviceKey, saved)
    }
    LaunchedEffect(displayCalibration.currentUid, displayCalibration.levels) {
        val uid = displayCalibration.currentUid ?: return@LaunchedEffect
        if (uid == 0 || displayCalibration.levels.size != 12) return@LaunchedEffect
        if (hearingProfiles.value.none { it.uid == uid }) {
            val restored = HearingProfile(
                uid = uid,
                name = displayCalibration.currentName.ifBlank {
                    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                },
                localIndex = HearingProfileStore.nextLocalIndex(context, hearingDeviceKey),
                version = 2,
                levels = displayCalibration.levels,
                savedAt = System.currentTimeMillis(),
            )
            hearingProfiles.value = HearingProfileStore.upsert(context, hearingDeviceKey, restored)
        }
    }

    val displayTitle = when {
        hookConnected.value -> mainTitle.value
        isStandaloneConnected -> appDeviceName
        isConnecting -> stringResource(R.string.connecting)
        // 未连接时沿用上次连接的设备名，页面才有「同一副耳机」的连续感。
        else -> lastDeviceName.value.orEmpty()
    }

    // 连接失败：弹 toast 并把「立刻连接」按钮复位，不再整屏切到错误页。
    // 文案在组合期解析，避免 lint 的 LocalContext 资源读取告警。
    val connectFailedMessage = stringResource(R.string.connect_failed)
    LaunchedEffect(isError) {
        if (isError) {
            Toast.makeText(context, connectFailedMessage, Toast.LENGTH_SHORT).show()
            // disconnect() 会把状态清回 DISCONNECTED，按钮随之恢复可点。
            appController.disconnect()
        }
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
                        hookAncVariant.value = p1.getIntExtra(
                            OppoPodsAction.EXTRA_ANC_VARIANT, -1,
                        )
                    }

                    OppoPodsAction.ACTION_PODS_ANC_VARIANT_CHANGED -> {
                        hookAncVariant.value = p1.getIntExtra(
                            OppoPodsAction.EXTRA_ANC_VARIANT, -1,
                        )
                    }

                    OppoPodsAction.ACTION_PODS_COLOR_CHANGED -> {
                        val color = p1.getIntExtra(
                            OppoPodsAction.EXTRA_DEVICE_COLOR, -1,
                        ).takeIf { it >= 0 }
                        if (color != null) {
                            hookDeviceColor.value = color
                            DeviceAppearanceStore.saveColor(
                                context,
                                address = lastDeviceAddress.value,
                                productId = activeProfile.value.id,
                                color = color,
                            )
                            val modelId = activeProfile.value.modelId
                            if (modelId.isNotBlank()) {
                                OfficialArtworkRepository.syncColor(context, modelId, color)
                            }
                        }
                    }

                    OppoPodsAction.ACTION_PODS_CALIBRATION_STATE -> {
                        p1.getStringExtra(OppoPodsAction.EXTRA_CALIBRATION_JSON)
                            ?.let { DeviceProfileStore.parseCalibration(it) }
                            ?.let { hookCalibration.value = it }
                    }

                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        p1.batteryStatusCompat()?.let {
                            batteryParams.value = it
                        }
                    }

                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        gameMode.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED -> {
                        hookEqPresetId.value = p1.getIntExtra("id", -1)
                        val entriesJson = p1.getStringExtra(OppoPodsAction.EXTRA_EQ_ENTRIES_JSON)
                        hookDeviceEqPresets.value = if (entriesJson != null) {
                            DeviceProfileStore.parseEqEntries(entriesJson)
                        } else {
                            val ids = p1.getIntegerArrayListExtra("preset_ids") ?: arrayListOf()
                            val names = p1.getStringArrayListExtra("preset_names") ?: arrayListOf()
                            ids.mapIndexedNotNull { index, id ->
                                names.getOrNull(index)?.takeIf { it.isNotBlank() }?.let {
                                    EqDevicePreset(id = id, name = it)
                                }
                            }
                        }
                    }

                    OppoPodsAction.ACTION_PODS_PROFILE_CHANGED -> {
                        p1.getStringExtra(OppoPodsAction.EXTRA_PROFILE_JSON)?.let { json ->
                            runCatching { DeviceProfileStore.parse(json) }
                                .onSuccess { activeProfile.value = it }
                        }
                    }

                    OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        spatialAudioMode.value = p1.getIntExtra("mode", SpatialAudioMode.OFF)
                            .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
                    }

                    OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED -> {
                        spatialSound.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED -> {
                        noiseLevel.value = p1.getIntExtra("level", moe.chenxy.oppopods.pods.NoiseLevel.DEEP)
                    }

                    OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED -> {
                        smartAncLevel.value = p1.getIntExtra("level", -1)
                    }

                    OppoPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED -> {
                        autoPlayPause.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED -> {
                        dualDevice.value = p1.getBooleanExtra("enabled", false)
                        hookConnectedDevicesReceived.value = p1.getBooleanExtra("devices_received", false)
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTED_DEVICES_CHANGED -> {
                        p1.extras?.classLoader = moe.chenxy.oppopods.pods.ConnectedDevice::class.java.classLoader
                        val devices = p1.getParcelableArrayListExtra("devices", moe.chenxy.oppopods.pods.ConnectedDevice::class.java)
                        hookConnectedDevices.value = devices ?: emptyList()
                        hookConnectedDevicesReceived.value = p1.getBooleanExtra("devices_received", true)
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        val deviceAddress = p1.getStringExtra("device_address")
                        val connColor = p1.getIntExtra(OppoPodsAction.EXTRA_DEVICE_COLOR, -1).takeIf { it >= 0 }
                        mainTitle.value = deviceName ?: ""
                        hookEqPresetId.value = -1
                        hookDeviceEqPresets.value = emptyList()
                        if (!deviceName.isNullOrBlank()) {
                            // 记录最后一次连接的耳机：未连接时用它还原页面与完整能力，
                            // 「立刻连接」也直接连它，不再跳选择页。
                            val displayName = deviceName
                            prefs.edit().apply {
                                putString(OppoPodsPrefsKey.LAST_DEVICE_NAME, displayName)
                                if (!deviceAddress.isNullOrBlank()) {
                                    putString(OppoPodsPrefsKey.LAST_DEVICE_ADDRESS, deviceAddress)
                                }
                            }.commit()
                            lastDeviceName.value = displayName
                            if (!deviceAddress.isNullOrBlank()) {
                                lastDeviceAddress.value = deviceAddress
                            }
                            runCatching {
                                DeviceProfileStore.resolveProfile(context, prefs, deviceName)
                            }.onSuccess { activeProfile.value = it }
                        }
                        if (connColor != null) {
                            hookDeviceColor.value = connColor
                            DeviceAppearanceStore.saveColor(context, deviceAddress, activeProfile.value.id, connColor)
                        } else {
                            val savedColor = DeviceAppearanceStore.getColor(context, deviceAddress, activeProfile.value.id)
                            if (savedColor != null) {
                                hookDeviceColor.value = savedColor
                            }
                        }
                        hookDeviceColor.value?.let { color ->
                            val modelId = activeProfile.value.modelId
                            if (modelId.isNotBlank()) {
                                OfficialArtworkRepository.syncColor(context, modelId, color)
                            }
                        }
                        hookConnected.value = true
                        Log.i("OppoPods", "pod connected via hook: $deviceName")
                    }

                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        hookConnected.value = false
                        hookEqPresetId.value = -1
                        hookDeviceEqPresets.value = emptyList()
                        hookAncVariant.value = -1
                        // 保留 hookDeviceColor，断连时继续显示最近配色的素材
                        hookCalibration.value = CalibrationState()
                        if (p0 is MainActivity) {
                            p0.finish()
                        }
                    }

                    OppoPodsAction.ACTION_BT_LOG_ENTRY -> {
                        val isSend = p1.getBooleanExtra(OppoPodsAction.EXTRA_BT_LOG_IS_SEND, false)
                        val hex = p1.getStringExtra(OppoPodsAction.EXTRA_BT_LOG_HEX) ?: return
                        val label = p1.getStringExtra(OppoPodsAction.EXTRA_BT_LOG_LABEL)
                        BtLogStore.addFromBroadcast(isSend, hex, label)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_VARIANT_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_COLOR_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CALIBRATION_STATE)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_EQ_PRESET_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_PROFILE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SMART_ANC_LEVEL_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_AUTO_PLAY_PAUSE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_DUAL_DEVICE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED_DEVICES_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_BT_LOG_ENTRY)
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
        if (mode == NoiseControlMode.TRANSPARENCY) {
            hookAncVariant.value = activeProfile.value.transparencyVariants
                .firstOrNull { it.key == moe.chenxy.oppopods.pods.AncKeys.TRANSPARENCY }
                ?.protocolIndex ?: -1
        } else {
            hookAncVariant.value = -1
        }
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

    fun setAncVariant(index: Int) {
        if (isStandaloneConnected) {
            appController.setAncVariant(index)
            return
        }
        hookAncVariant.value = index
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_ANC_VARIANT_SELECT).apply {
            putExtra(OppoPodsAction.EXTRA_ANC_VARIANT, index)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun encodeHearingProfile(profile: HearingProfile): String =
        Json.encodeToString(HearingProfile.serializer(), profile)

    fun decodeHearingProfile(json: String): HearingProfile? = runCatching {
        Json.decodeFromString(HearingProfile.serializer(), json)
    }.getOrNull()

    fun dispatchCalibration(
        command: String,
        heard: Boolean = false,
        level: Int = 5,
        released: Boolean = true,
        enabled: Boolean = false,
        name: String = "",
        preview: Boolean = false,
        profileJson: String = "",
    ) {
        if (isStandaloneConnected) {
            val engine = appController.calibration
            when (command) {
                CalibrationCommand.START_FIT -> engine.startFit()
                CalibrationCommand.START_HEARING -> engine.startHearing()
                CalibrationCommand.ANSWER -> engine.answer(heard)
                CalibrationCommand.SET_LEVEL -> engine.setLevel(level, released)
                CalibrationCommand.NEXT -> engine.next()
                CalibrationCommand.PREVIOUS -> engine.previous()
                CalibrationCommand.RESUME -> engine.resume()
                CalibrationCommand.PAUSE -> engine.pause()
                CalibrationCommand.SAVE -> engine.save(name)
                CalibrationCommand.CANCEL -> engine.cancel()
                CalibrationCommand.SET_ENABLED -> engine.setHearingEnabled(enabled)
                CalibrationCommand.PREVIEW -> engine.setPreview(preview)
                CalibrationCommand.REFRESH -> engine.refresh()
                CalibrationCommand.APPLY_PROFILE -> {
                    val profile = decodeHearingProfile(profileJson)
                    if (profile != null) engine.applyProfile(profile)
                }
            }
            return
        }
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_CALIBRATION_COMMAND).apply {
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_COMMAND, command)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_HEARD, heard)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_LEVEL, level)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_RELEASED, released)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_ENABLED, enabled)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_NAME, name)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_PREVIEW, preview)
            putExtra(OppoPodsAction.EXTRA_CALIBRATION_PROFILE, profileJson)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
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

    fun setEqPreset(id: Int) {
        if (id < 0) return
        if (isStandaloneConnected) {
            appController.setEqPreset(id)
            return
        }
        hookEqPresetId.value = id
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_EQ_PRESET_SET).apply {
            putExtra("id", id)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int,
        maxValue: Int,
    ) {
        if (isStandaloneConnected) {
            appController.saveEqPreset(id, name, frequencies, gains, minValue, maxValue)
            return
        }
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_EQ_PRESET_SAVE).apply {
            putExtra("id", id)
            putExtra("name", name)
            putIntegerArrayListExtra("frequencies", ArrayList(frequencies))
            putIntegerArrayListExtra("gains", ArrayList(gains))
            putExtra("min_value", minValue)
            putExtra("max_value", maxValue)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    fun deleteEqPreset(entry: EqDevicePreset) {
        if (isStandaloneConnected) {
            appController.deleteEqPreset(entry)
            return
        }
        context.sendBroadcast(Intent(OppoPodsAction.ACTION_EQ_PRESET_DELETE).apply {
            putExtra(
                OppoPodsAction.EXTRA_EQ_ENTRIES_JSON,
                DeviceProfileStore.exportEqEntries(listOf(entry)),
            )
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
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

    fun setSpatialSound(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setSpatialSound(enabled)
            return
        }
        spatialSound.value = enabled
        Intent(OppoPodsAction.ACTION_SPATIAL_SOUND_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setNoiseLevel(level: Int) {
        if (isStandaloneConnected) {
            appController.setNoiseLevel(level)
            return
        }
        noiseLevel.value = level
        Intent(OppoPodsAction.ACTION_NOISE_LEVEL_SET).apply {
            this.putExtra("level", level)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setAutoPlayPause(enabled)
            return
        }
        autoPlayPause.value = enabled
        Intent(OppoPodsAction.ACTION_AUTO_PLAY_PAUSE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun setDualDevice(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setDualDevice(enabled)
            return
        }
        dualDevice.value = enabled
        Intent(OppoPodsAction.ACTION_DUAL_DEVICE_SET).apply {
            this.putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            context.sendBroadcast(this)
        }
    }

    fun broadcastActiveProfile(profile: DeviceProfile) {
        Intent(OppoPodsAction.ACTION_ACTIVE_PROFILE_CHANGED).apply {
            setPackage("com.android.bluetooth")
            putExtra(OppoPodsAction.EXTRA_PROFILE_JSON, DeviceProfileStore.exportJson(profile))
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        // 按当前模式预解析（自动模式先用蓝牙名预判），连上后 0x8103 再精确校正。
        val deviceName = if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            device.name
        } else {
            null
        }
        val resolved = runCatching {
            DeviceProfileStore.resolveProfile(context, prefs, deviceName)
        }.getOrElse { activeProfile.value }
        activeProfile.value = resolved
        // 记录「上一次连接的耳机」，未连接时用它渲染对应型号的页面。
        val displayName = deviceName?.takeIf { it.isNotBlank() } ?: device.address
        prefs.edit()
            .putString(OppoPodsPrefsKey.LAST_DEVICE_ADDRESS, device.address)
            .putString(OppoPodsPrefsKey.LAST_DEVICE_NAME, displayName)
            .commit()
        lastDeviceAddress.value = device.address
        lastDeviceName.value = displayName
        appController.connect(
            device = device,
            connectionMethod = rfcommConnectionMethod.value,
            profile = resolved
        )
    }

    /** 断开当前耳机：独立连接直接关闭，hook 模式交给蓝牙进程执行。 */
    fun disconnectPods() {
        if (isStandaloneConnected) {
            appController.disconnect()
        } else if (hookConnected.value) {
            context.sendBroadcast(
                Intent(OppoPodsAction.ACTION_PODS_DISCONNECT_REQUEST).apply {
                    setPackage("com.android.bluetooth")
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
    }
    /**
     * 首页「立刻连接」：直接连记录下来的那台耳机。
     *
     * 只有确实没有记录（或缺少蓝牙权限）时才退回选择页——正常路径不应该让
     * 用户为此多点一次。
     */
    fun connectLastDevice() {
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull()
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (adapter == null || !granted) {
            backStack.add(Screen.DevicePicker)
            return
        }
        // 优先用记录下来的地址；没有记录时退而取已配对里第一台 OPPO 耳机。
        val address = lastDeviceAddress.value
            ?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            ?: runCatching {
                adapter.bondedDevices
                    ?.firstOrNull { it.name?.contains("oppo", ignoreCase = true) == true }
                    ?.address
            }.getOrNull()
        val device = address?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        if (device != null) {
            onDeviceSelected(device)
        } else {
            backStack.add(Screen.DevicePicker)
        }
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
        temporaryBatteryIslandDurationSecondsValue: Int,
        showConnectionPopupEnabled: Boolean,
        connectionPopupDismissSecondsValue: Int,
        showConnectionNotificationEnabled: Boolean,
        notificationIslandStyleEnabled: Boolean
    ) {
        val settings = NotificationSettings(
            showConnectionBatteryIsland = showConnectionBatteryIslandEnabled,
            temporaryBatteryIslandDurationSeconds = temporaryBatteryIslandDurationSecondsValue,
            showConnectionPopup = showConnectionPopupEnabled,
            connectionPopupDismissSeconds = connectionPopupDismissSecondsValue,
            showConnectionNotification = showConnectionNotificationEnabled,
            notificationIslandStyle = notificationIslandStyleEnabled,
            showHeadsetStatusBarIcon = showHeadsetStatusBarIcon.value,
            updatedAt = System.currentTimeMillis()
        )
        settings.writeToPrefs(prefs, commit = true)
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

    // 配置切换时：隐藏的 UI 自动关闭，重新显示时恢复原值
    SaveOnHideEffect(
        visible = activeProfile.value.spatialAudioVisible,
        currentValue = milinkSpatialAudioOptionEnabled.value,
        hiddenValue = false,
        onValueChange = { enabled ->
            milinkSpatialAudioOptionEnabled.value = enabled
            prefs.edit()
                .putBoolean(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, enabled)
                .commit()
            broadcastMilinkSpatialAudioOption(enabled)
        }
    )
    SaveOnHideEffect(
        visible = activeProfile.value.spatialAudioVisible,
        currentValue = displaySpatialAudioMode,
        hiddenValue = SpatialAudioMode.OFF,
        onValueChange = { setSpatialAudioMode(it) }
    )
    SaveOnHideEffect(
        visible = activeProfile.value.spatialSoundVisible,
        currentValue = displaySpatialSound,
        hiddenValue = false,
        onValueChange = { setSpatialSound(it) }
    )

    fun broadcastCustomButtonFunction(value: String) {
        Intent(OppoPodsAction.ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED).apply {
            setPackage("com.milink.service")
            putExtra(CustomButtonFunction.PREF_KEY, value)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    fun broadcastCustomButtonPosition(value: String) {
        Intent(OppoPodsAction.ACTION_CUSTOM_BUTTON_POSITION_CHANGED).apply {
            setPackage("com.milink.service")
            putExtra(CustomButtonPosition.PREF_KEY, value)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }

    // Each entry owns its Scaffold + top bar, so page transitions carry the whole page.
    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        },
    ) {
        entry<Screen.Home> {
            val homeTitle = mainTitle.value
                .ifEmpty { lastDeviceName.value.orEmpty() }
                .ifEmpty { stringResource(R.string.app_name) }
            val homeImageFile = remember {
                PodImageStore.resolvedFile(context, PodImageSlot.HOME_IMAGE)
            }
            val modelFile = remember { OfficialArtworkStore.modelFile(context) }
            // Miuix 的按压缩小反馈：按下整体缩到 96%，松手弹回。
            val connectInteraction = remember { MutableInteractionSource() }
            val connectPressed by connectInteraction.collectIsPressedAsState()
            val connectScale = animateFloatAsState(
                targetValue = if (connectPressed) 0.96f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "connectPressScale",
            )
            var showFitTestSheet by remember { mutableStateOf(false) }
            MainShell(
                floatingNavbar = themeConfig.value.floatingNavbar,
                liquidGlass = themeConfig.value.liquidGlass,
                blurEnabled = themeConfig.value.blurEnabled,
                railExpanded = navRailExpanded.value,
                onRailExpandedChange = { expanded ->
                    navRailExpanded.value = expanded
                    prefs.edit()
                        .putBoolean(OppoPodsPrefsKey.NAV_RAIL_EXPANDED, expanded)
                        .apply()
                },
                homeContent = { extraBottom, isActive ->
                    PrimaryPageScaffold(
                        title = homeTitle,
                        blurEnabled = themeConfig.value.blurEnabled,
                        extraBottomPadding = extraBottom,
                        navigationIcon = {
                            // 顶栏左侧「+」：随时可以重新挑一台耳机。
                            IconButton(onClick = { backStack.add(Screen.DevicePicker) }) {
                                Icon(
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = stringResource(R.string.add_device),
                                )
                            }
                        },
                        actions = {
                            if (canShowDetailPage) {
                                IconButton(onClick = { refreshStatus() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Refresh,
                                        contentDescription = stringResource(R.string.refresh),
                                    )
                            }
                            }
                        },
                    ) { padding, behavior ->
                        Box(Modifier.fillMaxSize()) {
                            // 未连接 / 连接中整页置灰且不可操作，但保留上次设备的布局。
                            DisabledContent(enabled = !keepContentGreyed) {
                                PodDetailPage(
                                    modifier = Modifier
                                        .overScrollVertical()
                                        .nestedScroll(behavior.nestedScrollConnection),
                                    contentPadding = padding,
                                    batteryParams = displayBattery,
                                    ancMode = displayAnc,
                                    onAncModeChange = { setAncMode(it) },
                                    adaptiveModeEnabled = activeProfile.value.adaptiveVisible,
                                    noiseLevelVisible = activeProfile.value.noiseLevelVisible,
                                    noiseLevel = displayNoiseLevel,
                                    smartAncLevel = displaySmartAncLevel,
                                    onNoiseLevelChange = { setNoiseLevel(it) },
                                    homeImageFile = homeImageFile,
                                    modelFile = modelFile,
                                    isActive = isActive,
                                    autoRotate = canShowDetailPage,
                                    transparencyVariants = activeProfile.value.transparencyVariants,
                                    ancVariant = displayAncVariant,
                                    onAncVariantChange = { setAncVariant(it) },
                                    onDisconnect = if (canShowDetailPage) {
                                        { disconnectPods() }
                                    } else {
                                        null
                                    },
                                )
                                }

                            if (keepContentGreyed) {
                                // 页面底部（悬浮底栏之上）的主操作。
                                Button(
                                    // 连接中不再响应点击，但保持同一个蓝色按钮：
                                    // 进度直接画在按钮上，替代按钮文字。
                                    onClick = { if (!isConnecting) connectLastDevice() },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 24.dp)
                                        .padding(
                                            bottom = padding.calculateBottomPadding() + 16.dp
                                        )
                                        .fillMaxWidth()
                                        // 按压缩小作用在整个按钮上（含底色），
                                        // 不能只靠 indication——那只缩内容层。
                                        .graphicsLayer {
                                            scaleX = connectScale.value
                                            scaleY = connectScale.value
                                        },
                                    // 默认按钮是次级容器色，这里需要强调蓝的主按钮。
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                    interactionSource = connectInteraction,
                                ) {
                                    if (isConnecting) {
                                        InfiniteProgressIndicator(
                                            color = Color.White,
                                            size = 22.dp,
                                        )
                                    } else {
                                        Text(text = stringResource(R.string.connect_now))
                                    }
                                }
                            }
                        }
                    }
                },
                featuresContent = { extraBottom ->
                    PrimaryPageScaffold(
                        title = stringResource(R.string.tab_features),
                        blurEnabled = themeConfig.value.blurEnabled,
                        extraBottomPadding = extraBottom,
                    ) { padding, behavior ->
                        DisabledContent(enabled = !keepContentGreyed) {
                            FeaturesPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(behavior.nestedScrollConnection),
                                contentPadding = padding,
                                gameMode = displayGameMode,
                                onGameModeChange = { setGameMode(it) },
                                gameModeVisible = activeProfile.value.gameModeVisible,
                                eqVisible = activeProfile.value.eqPresets.isNotEmpty() ||
                                        activeProfile.value.customEqVisible,
                                eqTitle = if (activeProfile.value.eqPresets.any { it.tag == 3 }) {
                                    stringResource(R.string.eq_master_tuning)
                                } else {
                                    stringResource(R.string.sound_effects)
                                },
                                eqCurrentName = displayEqCurrentName,
                                onOpenEqualizer = { backStack.add(Screen.Equalizer) },
                                spatialAudioMode = displaySpatialAudioMode,
                                onSpatialAudioModeChange = { setSpatialAudioMode(it) },
                                spatialAudioVisible = activeProfile.value.spatialAudioVisible,
                                spatialSound = displaySpatialSound,
                                onSpatialSoundChange = { setSpatialSound(it) },
                                spatialSoundVisible = activeProfile.value.spatialSoundVisible,
                                fitDetectionVisible = activeProfile.value.fitDetectionVisible,
                                hearingEnhancementVisible = activeProfile.value.hearingEnhancementVisible,
                                hearingName = displayCalibration.currentName,
                                onOpenFitTest = { showFitTestSheet = true },
                                onOpenHearing = { backStack.add(Screen.Hearing) },
                                autoPlayPauseVisible = activeProfile.value.autoPlayPauseVisible,
                                autoPlayPause = displayAutoPlayPause,
                                onAutoPlayPauseChange = { setAutoPlayPause(it) },
                                dualDeviceVisible = activeProfile.value.dualDeviceVisible,
                                dualDevice = displayDualDevice,
                                onDualDeviceChange = { setDualDevice(it) },
                                connectedDevicesVisible = activeProfile.value.connectedDevicesVisible,
                                connectedDevices = displayConnectedDevices,
                                connectedDevicesReceived = displayConnectedDevicesReceived,
                            )
                        }

                        FitTestSheet(
                            show = showFitTestSheet,
                            onDismissRequest = {
                                if (displayCalibration.isBusy) {
                                    dispatchCalibration(CalibrationCommand.CANCEL)
                                }
                                showFitTestSheet = false
                            },
                            state = displayCalibration,
                            supported = activeProfile.value.fitDetectionVisible,
                            onStart = { dispatchCalibration(CalibrationCommand.START_FIT) },
                            onCancel = { dispatchCalibration(CalibrationCommand.CANCEL) },
                        )
                    }
                },
                settingsContent = { extraBottom ->
                    PrimaryPageScaffold(
                        title = stringResource(R.string.settings),
                        blurEnabled = themeConfig.value.blurEnabled,
                        extraBottomPadding = extraBottom,
                    ) { padding, behavior ->
                    SettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(behavior.nestedScrollConnection),
                        contentPadding = padding,
                        onOpenTheme = { backStack.add(Screen.Theme) },
                        showConnectionBatteryIsland = showConnectionBatteryIsland,
                        onShowConnectionBatteryIslandChange = {
                            showConnectionBatteryIsland.value = it
                            prefs.edit()
                                .putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, it)
                                .commit()
                            broadcastNotificationSettings(
                                it,
                                temporaryBatteryIslandDurationSeconds.value,
                                showConnectionPopup.value,
                                connectionPopupDismissSeconds.value,
                                showConnectionNotification.value,
                                notificationIslandStyle.value
                            )
                        },
                        temporaryBatteryIslandDurationSeconds = temporaryBatteryIslandDurationSeconds,
                        onTemporaryBatteryIslandDurationSecondsChange = {
                            temporaryBatteryIslandDurationSeconds.value = it
                            prefs.edit()
                                .putInt(OppoPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS, it)
                                .commit()
                            broadcastNotificationSettings(
                                showConnectionBatteryIsland.value,
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
                                temporaryBatteryIslandDurationSeconds.value,
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
                                temporaryBatteryIslandDurationSeconds.value,
                                showConnectionPopup.value,
                                connectionPopupDismissSeconds.value,
                                showConnectionNotification.value,
                                it
                            )
                        },
                        showHeadsetStatusBarIcon = showHeadsetStatusBarIcon,
                        onShowHeadsetStatusBarIconChange = {
                            showHeadsetStatusBarIcon.value = it
                            prefs.edit()
                                .putBoolean(OppoPodsPrefsKey.SHOW_HEADSET_STATUS_BAR_ICON, it)
                                .commit()
                            broadcastNotificationSettings(
                                showConnectionBatteryIsland.value,
                                temporaryBatteryIslandDurationSeconds.value,
                                showConnectionPopup.value,
                                connectionPopupDismissSeconds.value,
                                showConnectionNotification.value,
                                notificationIslandStyle.value
                            )
                        },
                        onOpenAdvancedSettings = { backStack.add(Screen.AdvancedSettings) },
                        onOpenAbout = { backStack.add(Screen.About) },
                        onOpenProfiles = { backStack.add(Screen.Profiles) },
                        debugMode = debugMode.value,
                        onOpenDebug = { backStack.add(Screen.Debug) }
                    )
                    }
                },
            )
        }
        entry<Screen.DevicePicker> {
            SecondaryPageScaffold(
                title = stringResource(R.string.select_device_title),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                DevicePickerPage(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                    scrollBehavior = behavior,
                    // 已连接时标「当前连接」，否则退回「上次连接」。
                    connectedAddress = if (canShowDetailPage) lastDeviceAddress.value else null,
                    lastConnectedAddress = lastDeviceAddress.value,
                    onDeviceSelected = {
                        onDeviceSelected(it)
                        // 选完直接回到主页面，避免用户再退一次。
                        if (backStack.size > 1) backStack.removeLast()
                    },
                )
            }
        }
        entry<Screen.Equalizer> {
            val isDynaudio = activeProfile.value.eqPresets.any { it.tag == 3 }
            val pageTitle = if (isDynaudio) {
                stringResource(R.string.eq_master_tuning)
            } else {
                stringResource(R.string.sound_effects)
            }

            SecondaryPageScaffold(
                title = pageTitle,
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                EqualizerPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    builtInPresets = activeProfile.value.eqPresets,
                    devicePresets = displayEqDevicePresets,
                    selectedId = displayEqPresetId,
                    customEqVisible = activeProfile.value.customEqVisible,
                    customEqFrequencies = activeProfile.value.customEqFrequencies,
                    customEqMaxPresets = activeProfile.value.customEqMaxPresets,
                    onSelectPreset = { setEqPreset(it) },
                    onOpenCustomEq = { preset -> setEqPreset(preset.id) },
                    onSavePreset = { id, name, frequencies, gains, minValue, maxValue ->
                        saveEqPreset(id, name, frequencies, gains, minValue, maxValue)
                    },
                    onDeletePreset = { deleteEqPreset(it) },
                )
            }
        }
        entry<Screen.FitTest> {
            DisposableEffect(Unit) {
                onDispose { dispatchCalibration(CalibrationCommand.CANCEL) }
            }

            SecondaryPageScaffold(
                title = stringResource(R.string.fit_detection_title),
                onBack = {
                    if (displayCalibration.isBusy) {
                        dispatchCalibration(CalibrationCommand.CANCEL)
                    }
                    backStack.removeLast()
                },
            ) { padding, behavior ->
                FitTestPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    state = displayCalibration,
                    supported = activeProfile.value.fitDetectionVisible,
                    onStart = { dispatchCalibration(CalibrationCommand.START_FIT) },
                    onCancel = { dispatchCalibration(CalibrationCommand.CANCEL) },
                )
            }
        }
        entry<Screen.Hearing> {
            LaunchedEffect(Unit) {
                dispatchCalibration(CalibrationCommand.REFRESH)
            }

            DisposableEffect(Unit) {
                onDispose { dispatchCalibration(CalibrationCommand.CANCEL) }
            }

            SecondaryPageScaffold(
                title = stringResource(R.string.hearing_title),
                onBack = {
                    if (displayCalibration.isBusy) {
                        dispatchCalibration(CalibrationCommand.CANCEL)
                    }
                    backStack.removeLast()
                },
            ) { padding, behavior ->
                HearingPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    state = displayCalibration,
                    supported = activeProfile.value.hearingEnhancementVisible,
                    onStart = { dispatchCalibration(CalibrationCommand.START_HEARING) },
                    onAnswer = { dispatchCalibration(CalibrationCommand.ANSWER, heard = it) },
                    onLevel = { level, released ->
                        dispatchCalibration(
                            CalibrationCommand.SET_LEVEL,
                            level = level,
                            released = released,
                        )
                    },
                    onNext = { dispatchCalibration(CalibrationCommand.NEXT) },
                    onPrevious = { dispatchCalibration(CalibrationCommand.PREVIOUS) },
                    onSave = { dispatchCalibration(CalibrationCommand.SAVE, name = it) },
                    onCancel = { dispatchCalibration(CalibrationCommand.CANCEL) },
                    onEnabledChange = {
                        dispatchCalibration(CalibrationCommand.SET_ENABLED, enabled = it)
                    },
                    onPreview = {
                        dispatchCalibration(CalibrationCommand.PREVIEW, preview = it)
                    },
                    profiles = hearingProfiles.value,
                    requiresEarScan = activeProfile.value.earScanVisible,
                    onSelectProfile = { profile ->
                        // null = 关闭；其余按本机历史把数据重新写回耳机。
                        if (profile == null) {
                            dispatchCalibration(CalibrationCommand.SET_ENABLED, enabled = false)
                        } else {
                            dispatchCalibration(
                                CalibrationCommand.APPLY_PROFILE,
                                profileJson = encodeHearingProfile(profile),
                            )
                        }
                    },
                    onDeleteProfile = { profile ->
                        hearingProfiles.value = HearingProfileStore.remove(context, hearingDeviceKey, profile.uid)
                    },
                    onRenameProfile = { profile, newName ->
                        hearingProfiles.value = HearingProfileStore.upsert(context, hearingDeviceKey, profile.copy(name = newName))
                    },
                )
            }
        }
        entry<Screen.Profiles> {
            SecondaryPageScaffold(
                title = stringResource(R.string.device_profiles),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                ProfilesPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    prefs = prefs,
                    activeProfile = activeProfile.value,
                    deviceColor = displayDeviceColor,
                    onActiveProfileChanged = { p ->
                        activeProfile.value = p
                        appController.setProfile(p)
                        broadcastActiveProfile(p)
                    }
                )
            }
        }
        entry<Screen.AdvancedSettings> {
            SecondaryPageScaffold(
                title = stringResource(R.string.advanced_settings),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                AdvancedSettingsPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
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
                    adaptiveVisible = activeProfile.value.adaptiveVisible,
                    spatialAudioVisible = activeProfile.value.spatialAudioVisible,
                    spatialSoundVisible = activeProfile.value.spatialSoundVisible,
                    showConnectionPopup = showConnectionPopup,
                    onShowConnectionPopupChange = {
                        showConnectionPopup.value = it
                        prefs.edit()
                            .putBoolean(OppoPodsPrefsKey.SHOW_CONNECTION_POPUP, it)
                            .commit()
                        broadcastNotificationSettings(
                            showConnectionBatteryIsland.value,
                            temporaryBatteryIslandDurationSeconds.value,
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
                            temporaryBatteryIslandDurationSeconds.value,
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
                    },
                    customButtonFunction = customButtonFunction,
                    onCustomButtonFunctionChange = {
                        customButtonFunction.value = it
                        prefs.edit()
                            .putString(CustomButtonFunction.PREF_KEY, it.preferenceValue)
                            .commit()
                        broadcastCustomButtonFunction(it.preferenceValue)
                    },
                    customButtonPosition = customButtonPosition,
                    onCustomButtonPositionChange = {
                        customButtonPosition.value = it
                        prefs.edit()
                            .putString(CustomButtonPosition.PREF_KEY, it.preferenceValue)
                            .commit()
                        broadcastCustomButtonPosition(it.preferenceValue)
                    }
                )
            }
        }
        entry<Screen.Theme> {
            SecondaryPageScaffold(
                title = stringResource(R.string.theme_title),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                ThemePage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    config = themeConfig.value,
                    onConfigChange = onThemeConfigChange,
                )
            }
        }
        entry<Screen.About> {
            AboutPage(
                onBack = { backStack.removeLast() },
                debugMode = debugMode.value,
                onDebugModeChanged = { enabled ->
                    debugMode.value = enabled
                    prefs.edit().putBoolean("debug_mode", enabled).commit()
                }
            )
        }
        entry<Screen.Debug> {
            SecondaryPageScaffold(
                title = stringResource(R.string.debug_mode),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                DebugPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    loggingEnabled = loggingEnabled.value,
                    onLoggingEnabledChange = {
                        loggingEnabled.value = it
                        BtLogStore.isEnabled = it
                        prefs.edit().putBoolean("bt_logging_enabled", it).commit()
                        if (it) {
                            BtLogStore.addRecv(byteArrayOf(), "日志已启用，等待蓝牙数据...")
                        }
                    },
                    onOpenLog = { backStack.add(Screen.DebugLog) }
                )
            }
        }
        entry<Screen.DebugLog> {
            SecondaryPageScaffold(
                title = stringResource(R.string.debug_view_log),
                onBack = { backStack.removeLast() },
            ) { padding, behavior ->
                DebugLogPage(
                    modifier = Modifier
                        .overScrollVertical()
                        .nestedScroll(behavior.nestedScrollConnection),
                    contentPadding = padding,
                    onClear = { BtLogStore.clear() }
                )
            }
        }
    }
}
