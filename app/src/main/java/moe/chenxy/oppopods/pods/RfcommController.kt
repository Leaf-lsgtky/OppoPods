package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import android.content.SharedPreferences
import java.util.concurrent.Executor

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "OppoPods-RfcommController"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    // Basic Objects
    private val rfcommLock = Any()
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private val audioManager: AudioManager? by lazy {
        mContext?.getSystemService(AudioManager::class.java)
    }
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    @Volatile
    private var isPodConnected = false
    @Volatile
    private var isRfcommConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    private var currentGameMode: Boolean = false
    private var currentSpatialAudioMode: Int = SpatialAudioMode.OFF
    @Volatile
    private lateinit var activeProfile: DeviceProfile
    private var rfcommConnectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    private var lastGameModeStatusUpdateMs: Long = 0L
    private var notificationSettings: NotificationSettings = NotificationSettings()
    private val showConnectionBatteryIslandEnabled: Boolean
        get() = notificationSettings.showConnectionBatteryIsland
    private val showConnectionPopupEnabled: Boolean
        get() = notificationSettings.showConnectionPopup
    private val connectionPopupDismissSeconds: Int
        get() = notificationSettings.connectionPopupDismissSeconds
    private val showConnectionNotificationEnabled: Boolean
        get() = notificationSettings.showConnectionNotification
    private val notificationIslandStyleEnabled: Boolean
        get() = notificationSettings.notificationIslandStyle
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""

    // Polling job
    private var batteryPollJob: kotlinx.coroutines.Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            p1?.let { handleUIEvent(it, p0) }
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 4) return
        Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            this.putExtra("status", status)
            putBatteryExtras(status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED) {
            putExtra("enabled", enabled)
        }
    }

    private fun changeUISpatialAudioStatus(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        Intent(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED).apply {
            this.putExtra("mode", normalizedMode)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED) {
            putExtra("mode", normalizedMode)
        }
    }

    private fun refreshPodsNotification() {
        val context = mContext ?: return
        if (!::mDevice.isInitialized) return

        if (!showConnectionNotificationEnabled) {
            cancelPodsNotificationByMiuiBt(context, mDevice)
            return
        }

        if (!::currentBatteryParams.isInitialized) return
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
            context,
            currentBatteryParams,
            mDevice,
            notificationSettings,
            isRfcommConnected
        )
    }

    fun syncNotificationSettings(
        context: Context?,
        intent: Intent,
        refreshNotification: Boolean = false
    ) {
        val settings = NotificationSettings.fromIntent(intent, notificationSettings)
            .withUpdatedAtIfMissing()
        notificationSettings = settings
        context?.let { cacheNotificationSettings(it, settings) }
        Log.d(
            TAG,
            "Notification settings synced: batteryIsland=$showConnectionBatteryIslandEnabled, popup=$showConnectionPopupEnabled, popupDismiss=${connectionPopupDismissSeconds}s, show=$showConnectionNotificationEnabled, island=$notificationIslandStyleEnabled, updatedAt=${notificationSettings.updatedAt}"
        )
        if (refreshNotification) {
            refreshPodsNotification()
        }
    }

    private fun loadNotificationSettings(context: Context): NotificationSettings {
        reloadPrefs()
        val remoteSettings = NotificationSettings.fromPrefs(mPrefs)
        val cachedSettings = context.getSharedPreferences(
            OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE
        ).let { NotificationSettings.fromPrefsOrNull(it) }
        if (
            remoteSettings.updatedAt == 0L &&
            mPrefs.contains(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION) &&
            !remoteSettings.showConnectionNotification
        ) {
            return remoteSettings
        }
        return NotificationSettings.newerOf(remoteSettings, cachedSettings)
    }

    private fun cacheNotificationSettings(context: Context, settings: NotificationSettings) {
        settings.withUpdatedAtIfMissing().writeToPrefs(
            context.getSharedPreferences(
                OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            )
        )
    }

    fun handleUIEvent(intent: Intent, receiverContext: Context? = null) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIAncStatus(currentAnc)
                changeUIGameModeStatus(currentGameMode)
                changeUISpatialAudioStatus(currentSpatialAudioMode)
                Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
                    this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    this.`package` = BuildConfig.APPLICATION_ID
                    this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    mContext!!.sendBroadcast(this)
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                val allowReconnect = intent.getBooleanExtra(
                    OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT,
                    false
                )
                queryStatus(allowReconnect)
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setGameMode(enabled)
            }
            OppoPodsAction.ACTION_SPATIAL_AUDIO_SET -> {
                val mode = intent.getIntExtra("mode", SpatialAudioMode.OFF)
                setSpatialAudioMode(mode)
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED -> {
                syncNotificationSettings(receiverContext ?: mContext, intent, refreshNotification = true)
            }
            OppoPodsAction.ACTION_ACTIVE_PROFILE_CHANGED -> {
                val jsonStr = intent.getStringExtra(OppoPodsAction.EXTRA_PROFILE_JSON)
                activeProfile = runCatching {
                    if (jsonStr != null) DeviceProfileStore.parse(jsonStr)
                    else DeviceProfileStore.activeProfile(mPrefs)
                }.getOrDefault(activeProfile)
                Log.d(TAG, "Active device profile synced: ${activeProfile.name} (${activeProfile.id})")
            }
        }
    }

    private fun currentBatterySnapshot(): BatteryParams {
        return if (::currentBatteryParams.isInitialized) {
            BatteryParams(
                currentBatteryParams.left?.copy(),
                currentBatteryParams.right?.copy(),
                currentBatteryParams.case?.copy()
            )
        } else {
            BatteryParams()
        }
    }

    private fun batteryInfoToPodParams(
        info: BatteryParser.BatteryInfo?,
        previous: PodParams?,
        preserveMissing: Boolean
    ): PodParams {
        if (info != null) {
            return PodParams(info.level, info.isCharging, true, previous?.rawStatus ?: 0)
        }
        if (preserveMissing && previous != null) return previous.copy()
        return PodParams(0, false, false, previous?.rawStatus ?: 0)
    }

    private fun caseInfoToPodParams(
        info: BatteryParser.BatteryInfo?,
        previous: PodParams?,
        preserveMissing: Boolean
    ): PodParams {
        if (info != null) {
            lastKnownCaseBattery = info.level
            lastKnownCaseCharging = info.isCharging
            return PodParams(info.level, info.isCharging, true, previous?.rawStatus ?: 0)
        }
        if (preserveMissing && previous != null) return previous.copy()
        return PodParams(lastKnownCaseBattery, lastKnownCaseCharging, false, previous?.rawStatus ?: 0)
    }

    fun handleBatteryChanged(result: BatteryParser.BatteryResult, preserveMissing: Boolean = false) {
        val previous = currentBatterySnapshot()
        val batteryParams = BatteryParams(
            left = batteryInfoToPodParams(result.left, previous.left, preserveMissing),
            right = batteryInfoToPodParams(result.right, previous.right, preserveMissing),
            case = caseInfoToPodParams(result.case, previous.case, preserveMissing)
        )
        publishBatteryParams(batteryParams)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun publishBatteryParams(batteryParams: BatteryParams) {
        val context = mContext ?: return
        val left = batteryParams.left ?: PodParams()
        val right = batteryParams.right ?: PodParams()
        val case = batteryParams.case ?: PodParams()

        if (BuildConfig.DEBUG) {
            Log.v(
                TAG,
                "batt left ${left.battery}/${left.isCharging} right ${right.battery}/${right.isCharging} case ${case.battery}/${case.isCharging}"
            )
        }

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            mShowedConnectedToast = true
            if (showConnectionBatteryIslandEnabled) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                    context,
                    batteryParams,
                    notificationSettings
                )
            }
            if (showConnectionPopupEnabled) {
                showConnectionPopup(context, batteryParams)
            }
        }
        if (showConnectionNotificationEnabled) {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
                context,
                batteryParams,
                mDevice,
                notificationSettings,
                isRfcommConnected
            )
        } else {
            cancelPodsNotificationByMiuiBt(context, mDevice)
        }
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private fun showConnectionPopup(context: Context, batteryParams: BatteryParams) {
        try {
            Intent().apply {
                setClassName(BuildConfig.APPLICATION_ID, "moe.chenxy.oppopods.ConnectionPopupActivity")
                putExtra("status", batteryParams)
                putExtra("device_name", currentDeviceDisplayName())
                putExtra(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    connectionPopupDismissSeconds
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show connection popup", e)
        }
    }

    private fun currentDeviceDisplayName(): String {
        return if (::mDevice.isInitialized) {
            mDevice.alias?.takeIf { it.isNotBlank() }
                ?: mDevice.name
                ?: cachedDeviceName
        } else {
            cachedDeviceName
        }
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@RfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch { p0?.run() }
        }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        mediaRouter.unregisterRouteCallback(routeCallback)
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        reloadPrefs()
        activeProfile = DeviceProfileStore.activeProfile(mPrefs)
        Log.d(TAG, "Active device profile: ${activeProfile.name} (${activeProfile.id})")
        notificationSettings = loadNotificationSettings(context)
        cacheNotificationSettings(context, notificationSettings)
        rfcommConnectionMethod = RfcommConnectionMethod.fromPreference(
            mPrefs.getString(RfcommConnectionMethod.PREF_KEY, null)
        )
        Log.d(
            TAG,
            "Notification settings initial: batteryIsland=$showConnectionBatteryIslandEnabled, popup=$showConnectionPopupEnabled, popupDismiss=${connectionPopupDismissSeconds}s, show=$showConnectionNotificationEnabled, island=$notificationIslandStyleEnabled"
        )
        Log.d(TAG, "RFCOMM connection method initial: ${rfcommConnectionMethod.preferenceValue}")

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
            this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            this.addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
            this.addAction(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET)
            this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
            this.addAction(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED)
            this.addAction(OppoPodsAction.ACTION_ACTIVE_PROFILE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            this.putExtra("device_name", cachedDeviceName)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("device_name", cachedDeviceName)
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isPodConnected = true

        // Start persistent RFCOMM connection and battery polling
        CoroutineScope(Dispatchers.IO).launch {
            var initialConnected = connectRfcomm("initial connect")
            if (!initialConnected) {
                delay(500)
                initialConnected = connectRfcomm("initial connect retry")
            }

            if (initialConnected) {
                sendStatusQueryPackets()
            } else {
                Log.w(TAG, "Initial RFCOMM connect failed; will retry on the next control/query operation")
            }
        }

        // Start battery polling
        batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
            delay(2000) // Wait for initial connection
            while (isPodConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isPodConnected) {
                    queryStatus(allowReconnect = false)
                }
            }
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun refreshRfcommConnectionMethod() {
        if (::mPrefs.isInitialized) {
            reloadPrefs()
            rfcommConnectionMethod = RfcommConnectionMethod.fromPreference(
                mPrefs.getString(RfcommConnectionMethod.PREF_KEY, null)
            )
        }
    }

    private fun reloadPrefs() {
        if (!::mPrefs.isInitialized) return
        runCatching {
            mPrefs.javaClass.methods.firstOrNull {
                it.name == "reload" && it.parameterTypes.isEmpty()
            }?.invoke(mPrefs)
        }
    }

    private fun connectRfcomm(reason: String): Boolean {
        if (!isPodConnected || mContext == null || !::mDevice.isInitialized) {
            Log.d(TAG, "Skip RFCOMM connect: podConnected=$isPodConnected, reason=$reason")
            return false
        }

        synchronized(rfcommLock) {
            if (isRfcommConnected && socket != null) {
                return true
            }

            refreshRfcommConnectionMethod()
            closeRfcommSocketLocked()

            return try {
                Log.d(
                    TAG,
                    "RFCOMM connecting: reason=$reason, method=${rfcommConnectionMethod.preferenceValue}"
                )
                val connectedSocket = OppoRfcommSocketFactory.connect(
                    mDevice,
                    TAG,
                    rfcommConnectionMethod
                )
                socket = connectedSocket
                isRfcommConnected = true
                startPacketReader(connectedSocket)
                Log.d(TAG, "RFCOMM connected: reason=$reason")
                refreshPodsNotification()
                true
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed: reason=$reason", e)
                closeRfcommSocketLocked()
                false
            }
        }
    }

    private fun closeRfcommSocketLocked() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM socket close failed", e)
        } finally {
            socket = null
            isRfcommConnected = false
        }
    }

    private fun markRfcommDisconnected(
        reason: String,
        failedSocket: BluetoothSocket? = null,
        error: Throwable? = null
    ) {
        if (error != null) {
            Log.e(TAG, "RFCOMM disconnected: $reason", error)
        } else {
            Log.d(TAG, "RFCOMM disconnected: $reason")
        }

        synchronized(rfcommLock) {
            if (failedSocket == null || socket === failedSocket) {
                closeRfcommSocketLocked()
                refreshPodsNotification()
            }
        }
    }

    private fun isActiveRfcommSocket(targetSocket: BluetoothSocket): Boolean {
        return synchronized(rfcommLock) {
            isRfcommConnected && socket === targetSocket
        }
    }

    private fun startPacketReader(readerSocket: BluetoothSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            val framer = OppoPacketFramer()
            try {
                val inputStream = readerSocket.inputStream
                while (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        framer.append(buffer, bytesRead).forEach { packet ->
                            handleOppoPacket(packet)
                        }
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                    markRfcommDisconnected("read error", readerSocket, e)
                }
                return@launch
            }

            if (isPodConnected && isActiveRfcommSocket(readerSocket)) {
                markRfcommDisconnected("reader stopped", readerSocket)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleOppoPacket(packet: ByteArray) {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")
        }

        // Try parse as battery response (query response, Cmd=0x8106)
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) {
            handleBatteryChanged(batteryResult)
            return
        }

        // Try parse as active battery report (unsolicited, Cmd=0x0204, type=0x01)
        val activeResult = BatteryParser.parseActiveReport(packet)
        if (activeResult != null) {
            handleBatteryChanged(activeResult, preserveMissing = true)
            return
        }

        // Try parse as ANC mode response
        val ancResult = AncModeParser.parse(packet)
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: $ancResult")
            currentAnc = when (ancResult) {
                NoiseControlMode.OFF -> 1
                NoiseControlMode.NOISE_CANCELLATION -> 2
                NoiseControlMode.TRANSPARENCY -> 3
                NoiseControlMode.ADAPTIVE -> 4
            }
            changeUIAncStatus(currentAnc)
            return
        }

        // Try parse as batch query response for game mode (Cmd=0x810D)
        val gameModeResult = GameModeParser.parseForFeature(packet, activeProfile.gameModeFeatureId())
        if (gameModeResult != null) {
            Log.d(TAG, "Game mode received: $gameModeResult")
            lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
            currentGameMode = gameModeResult
            changeUIGameModeStatus(gameModeResult)
            return
        }

        val spatialAudioResult = SpatialAudioParser.parseModeNotify(packet)
        if (spatialAudioResult != null) {
            Log.d(TAG, "Spatial audio mode received: $spatialAudioResult")
            currentSpatialAudioMode = spatialAudioResult
            changeUISpatialAudioStatus(spatialAudioResult)
            return
        }

        val spatialAudioSetStatus = SpatialAudioParser.parseSetResponseStatus(packet)
        if (spatialAudioSetStatus != null) {
            Log.d(TAG, "Spatial audio set response: $spatialAudioSetStatus")
            return
        }

        val setFeatureResult = SwitchFeatureSetParser.parse(packet)
        if (setFeatureResult != null) {
            Log.d(TAG, "Switch feature response: status=${setFeatureResult.status}, value=${setFeatureResult.value}")
            return
        }

        // Unknown packet - log in debug
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Unknown OPPO packet: ${packet.toHexString(HexFormat.UpperCase)}")
        }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isPodConnected = false
        batteryPollJob?.cancel()

        synchronized(rfcommLock) {
            closeRfcommSocketLocked()
        }

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
                this.`package` = BuildConfig.APPLICATION_ID
                this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
            it.unregisterReceiver(broadcastReceiver)
        }

        mShowedConnectedToast = false
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        currentSpatialAudioMode = SpatialAudioMode.OFF
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    private fun writePacket(targetSocket: BluetoothSocket, packet: ByteArray, reason: String): Boolean {
        try {
            targetSocket.outputStream.write(packet)
            targetSocket.outputStream.flush()
            return true
        } catch (e: IOException) {
            markRfcommDisconnected("send failed: $reason", targetSocket, e)
            return false
        }
    }

    private fun sendPacketSafe(
        packet: ByteArray,
        reason: String = "send packet",
        allowReconnect: Boolean = true
    ): Boolean {
        if (allowReconnect) {
            if (!connectRfcomm(reason)) return false
        }

        val targetSocket = synchronized(rfcommLock) { socket } ?: run {
            Log.d(TAG, "Skip packet: RFCOMM disconnected and reconnect not allowed, reason=$reason")
            return false
        }

        if (writePacket(targetSocket, packet, reason)) {
            return true
        }

        if (!allowReconnect) return false
        if (!connectRfcomm("$reason retry")) return false

        val retrySocket = synchronized(rfcommLock) { socket } ?: return false
        return writePacket(retrySocket, packet, "$reason retry")
    }

    fun setGameMode(enabled: Boolean) {
        Log.d(TAG, "setGameMode: $enabled")
        if (currentGameMode == enabled) {
            changeUIGameModeStatus(enabled)
            Log.d(TAG, "setGameMode skipped duplicate: $enabled")
            return
        }
        currentGameMode = enabled
        changeUIGameModeStatus(enabled)
        CoroutineScope(Dispatchers.IO).launch {
            sendGameModePackets(enabled)
        }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        Log.d(TAG, "setSpatialAudioMode: $normalizedMode")
        currentSpatialAudioMode = normalizedMode
        changeUISpatialAudioStatus(normalizedMode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.spatialPacket(normalizedMode), "set spatial audio mode")
        }
    }

    fun cycleAnc() {
        val next = when (currentAnc) {
            2 -> if (activeProfile.adaptiveVisible) 4 else 3  // NC → Adaptive（若启用）或 Transparency
            4 -> 3  // Adaptive → Transparency
            3 -> 1  // Transparency → OFF
            else -> 2  // OFF or unknown → NC
        }
        setANCMode(next)
    }

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode  // 乐观更新，与 AppRfcommController 保持一致
        if (mode !in 1..4) return
        val packet = activeProfile.ancPacket(mode)
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet, "set ANC mode")
        }
    }

    fun queryBattery(allowReconnect: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_BATTERY), "query battery", allowReconnect)
        }
    }

    private suspend fun sendGameModePackets(enabled: Boolean) {
        for ((index, packet) in activeProfile.gameModePackets(enabled).withIndex()) {
            if (index > 0) delay(120)
            if (!sendPacketSafe(packet, "set game mode")) return
        }
    }

    private suspend fun sendStatusQueryPackets(allowReconnect: Boolean = false) {
        if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_STATUS), "query status", allowReconnect)) return
        delay(50)
        if (!sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_BATTERY), "query battery", allowReconnect)) return
        delay(50)
        sendPacketSafe(activeProfile.packet(ProfileKeys.QUERY_ANC), "query ANC", allowReconnect)
    }

    /**
     * Combo query strategy: send batch query (wake + game mode), then battery, then ANC.
     */
    fun queryStatus(allowReconnect: Boolean = false) {
        CoroutineScope(Dispatchers.IO).launch {
            sendStatusQueryPackets(allowReconnect)
        }
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        MediaControl.sendPause()

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            for (route in routes) {
                if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "found speaker route $route")
                    mediaRouter.transferTo(route)
                }
            }
        }

        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val targetDevice = device ?: return
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, targetDevice)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == targetDevice.name) {
                Log.d(TAG, "found bt route $route")
                mediaRouter.transferTo(route)
            }
        }

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }
}
