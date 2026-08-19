package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private const val CONNECTED_DEVICE_BOOTSTRAP_DELAY_MS = 1_500L
    private const val TAG = "OppoPods-Bluetooth"
    private var notificationSettingsReceiverRegistered = false
    private var notificationSettingsContext: Context? = null
    private var notificationSettingsReceiver: BroadcastReceiver? = null
    private var bootstrapHandler: Handler? = null
    private var bootstrapRunnable: Runnable? = null

    override fun onHook() {
        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                Log.d("OppoPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                val context = instance as ContextWrapper
                registerNotificationSettingsReceiver(context)
                if (!isOppoPod(device)) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    RfcommController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    RfcommController.disconnectedPod(context, device)
                }
            }
        }

        // A module may be installed while the earbuds are already connected. In that case
        // handleConnectionStateChanged() does not run again, so the RFCOMM controller never
        // receives a device and all first-install state broadcasts are missing. Bootstrap once
        // after the Bluetooth service starts; normal connection callbacks remain the source of
        // truth for subsequent connections.
        runCatching {
            val serviceCreateMethod = runCatching {
                findMethod("com.android.bluetooth.a2dp.A2dpService", "onCreate")
            }.getOrElse {
                // AOSP/HyperOS commonly declares Service.onCreate in ProfileService rather
                // than overriding it in each profile implementation.
                findMethod("com.android.bluetooth.btservice.ProfileService", "onCreate")
            }
            hookAfter(serviceCreateMethod) {
                val context = instance as? Context ?: return@hookAfter
                scheduleConnectedDeviceBootstrap(context)
            }
            Log.d(TAG, "hooked ${serviceCreateMethod.declaringClass.name}.onCreate for connected-device bootstrap")
        }.onFailure { Log.w(TAG, "hook connected-device bootstrap skipped", it) }
    }

    override fun onHotReloading() {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        bootstrapRunnable = null
        bootstrapHandler = null
        notificationSettingsReceiver?.let { receiver ->
            runCatching { notificationSettingsContext?.unregisterReceiver(receiver) }
        }
        notificationSettingsReceiver = null
        notificationSettingsContext = null
        notificationSettingsReceiverRegistered = false
        RfcommController.shutdownForHotReload()
    }

    private fun registerNotificationSettingsReceiver(context: Context) {
        if (notificationSettingsReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) return
                    RfcommController.syncNotificationSettings(
                        receiverContext ?: context,
                        intent,
                        refreshNotification = false
                    )
                }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED),
            Context.RECEIVER_EXPORTED
        )
        notificationSettingsContext = context.applicationContext ?: context
        notificationSettingsReceiver = receiver
        notificationSettingsReceiverRegistered = true
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: device.alias ?: return false
        return name.contains("oppo", ignoreCase = true)
    }

    private fun scheduleConnectedDeviceBootstrap(context: Context) {
        bootstrapRunnable?.let { runnable -> bootstrapHandler?.removeCallbacks(runnable) }
        val handler = Handler(context.mainLooper)
        val runnable = Runnable { bootstrapConnectedDevice(context) }
        bootstrapHandler = handler
        bootstrapRunnable = runnable
        handler.postDelayed(runnable, CONNECTED_DEVICE_BOOTSTRAP_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun bootstrapConnectedDevice(context: Context) {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val device = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            .asSequence()
            .flatMap { profile ->
                runCatching { bluetoothManager.getConnectedDevices(profile).asSequence() }
                    .getOrElse { emptySequence() }
            }
            .distinctBy { it.address }
            .firstOrNull(::isOppoPod)
            ?: run {
                Log.d(TAG, "connected-device bootstrap found no OPPO earbuds")
                return
            }

        Log.i(TAG, "connected-device bootstrap found ${device.address}")
        RfcommController.connectPod(context, device, prefs)
    }

}
