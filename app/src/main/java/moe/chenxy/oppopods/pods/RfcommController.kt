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
import android.util.Log
import com.highcapable.yukihookapi.hook.xposed.prefs.YukiHookPrefsBridge
import de.robv.android.xposed.XposedHelpers
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
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "OppoPods-RfcommController"
    private const val RFCOMM_CHANNEL = 15
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    // Basic Objects
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private val audioManager: AudioManager? by lazy {
        mContext?.getSystemService(AudioManager::class.java)
    }
    private lateinit var mPrefsBridge: YukiHookPrefsBridge

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1

    // Polling job
    private var batteryPollJob: kotlinx.coroutines.Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            if (p1?.action == OppoPodsAction.ACTION_GET_PODS_MAC) {
                Intent(OppoPodsAction.ACTION_PODS_MAC_RECEIVED).apply {
                    Log.i(TAG, "${p1.action} ,mac ${mDevice.address}")
                    this.`package` = "com.android.systemui"
                    this.putExtra("mac", mDevice.address)
                    this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    p0?.sendBroadcast(this)
                    return
                }
            }
            handleUIEvent(p1!!)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 3) return
        Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIAncStatus(currentAnc)
                Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
                    this.putExtra("device_name", mDevice.name)
                    mContext!!.sendBroadcast(this)
                }
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus()
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        val left = PodParams(
            result.left?.level ?: 0,
            result.left?.isCharging == true,
            result.left != null,
            0
        )
        val right = PodParams(
            result.right?.level ?: 0,
            result.right?.isCharging == true,
            result.right != null,
            0
        )
        val case = PodParams(
            result.case?.level ?: 0,
            result.case?.isCharging == true,
            result.case != null,
            0
        )

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "batt left ${left.battery} right ${right.battery} case ${case.battery}")
        }

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast && (left.battery <= 0 || right.battery <= 0)) {
            return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams)
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
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

    /**
     * Create RFCOMM socket to OPPO earphone on channel 15 via reflection.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefsBridge: YukiHookPrefsBridge) {
        mContext = context
        mDevice = device
        mPrefsBridge = prefsBridge

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
            this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(OppoPodsAction.ACTION_GET_PODS_MAC)
            this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
        }, Context.RECEIVER_EXPORTED)

        Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            this.putExtra("device_name", device.name)
            context.sendBroadcast(this)
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true

        // Start persistent connection and battery polling
        CoroutineScope(Dispatchers.IO).launch {
            delay(500)
            try {
                socket = createRfcommSocket(device)
                socket!!.connect()
                Log.d(TAG, "RFCOMM connected!")

                // Start reader thread
                startPacketReader(socket!!.inputStream)

                // Initial status query (combo: battery wake + mode)
                delay(300)
                queryStatus()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                isConnected = false
                return@launch
            }
        }

        // Start battery polling
        batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
            delay(2000) // Wait for initial connection
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) {
                    queryStatus()
                }
            }
        }
    }

    private fun startPacketReader(inputStream: InputStream) {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val packet = buffer.copyOfRange(0, bytesRead)
                        handleOppoPacket(packet)
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "RFCOMM read error", e)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleOppoPacket(packet: ByteArray) {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")
        }

        // Try parse as battery response
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) {
            handleBatteryChanged(batteryResult)
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
            }
            changeUIAncStatus(currentAnc)
            return
        }

        // Unknown packet - log in debug
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Unknown OPPO packet: ${packet.toHexString(HexFormat.UpperCase)}")
        }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        batteryPollJob?.cancel()

        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
                context.sendBroadcast(this)
            }
            it.unregisterReceiver(broadcastReceiver)
        }

        mShowedConnectedToast = false
        mContext = null
        MediaControl.mContext = null
    }

    private fun sendPacketSafe(packet: ByteArray) {
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
        }
    }

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        val packet = when (mode) {
            1 -> Enums.ANC_OFF
            2 -> Enums.ANC_NOISE_CANCEL
            3 -> Enums.ANC_TRANSPARENCY
            else -> return
        }
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet)
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(Enums.QUERY_BATTERY)
        }
    }

    /**
     * Combo query strategy: send battery query (wake), wait 50ms, then mode query.
     */
    fun queryStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(Enums.QUERY_BATTERY)
            delay(50)
            sendPacketSafe(Enums.QUERY_ANC)
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
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
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

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == device!!.name) {
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
            val service = XposedHelpers.getObjectField(mContext, "mAdapterService")
            XposedHelpers.callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }
}
