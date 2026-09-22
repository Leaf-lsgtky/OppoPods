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
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey

object HeadsetStateDispatcher : HookContext() {
    private const val CONNECTED_DEVICE_BOOTSTRAP_DELAY_MS = 1_500L
    private const val TAG = "OppoPods-Bluetooth"
    private var notificationSettingsReceiverRegistered = false
    private var notificationSettingsContext: Context? = null
    private var notificationSettingsReceiver: BroadcastReceiver? = null
    private var bootstrapHandler: Handler? = null
    private var bootstrapRunnable: Runnable? = null

    // 用户是否希望连接期间常驻状态栏耳机图标（默认开）。
    @Volatile
    private var showHeadsetIcon: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_HEADSET_STATUS_BAR_ICON
    // 当前是否有 OPPO 耳机连接——供 HeadsetIconShowManager 拦截判定使用。
    @Volatile
    private var oppoConnected: Boolean = false
    private var appContext: Context? = null

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
                    oppoConnected = true
                    refreshHeadsetIconSetting()
                    statusBarManager.setIconVisibility("wireless_headset", showHeadsetIcon)
                    RfcommController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    oppoConnected = false
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    RfcommController.disconnectedPod(context, device)
                }
            }
        }

        // HyperOS 的 HeadsetIconShowManager 独占 wireless_headset 图标：设备连上后若不被
        // 小米识别（OPPO 即如此），它会在 ~500ms 后主动隐藏，覆盖本模块的点亮。在 OPPO 连接
        // 且用户要求显示时吞掉针对该图标的隐藏调用。
        // 注意：最新系统里该管理器随 MiuiNearbyServiceV2 运行在 com.xiaomi.bluetooth 进程，
        // 那边的隐藏调用由 XiaomiBluetoothHeadsetIconGuard 拦截；本进程的守卫仍保留，覆盖
        // 旧版本系统以及仍在 com.android.bluetooth 内发起的隐藏。
        hookWirelessHeadsetIconGuard()

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
        oppoConnected = false
        appContext = null
        RfcommController.shutdownForHotReload()
    }

    private fun registerNotificationSettingsReceiver(context: Context) {
        appContext = context.applicationContext ?: context
        if (notificationSettingsReceiverRegistered) return
        refreshHeadsetIconSetting()
        val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) return
                    val ctx = receiverContext ?: context
                    RfcommController.syncNotificationSettings(ctx, intent, refreshNotification = false)
                    showHeadsetIcon = intent.getBooleanExtra(
                        OppoPodsPrefsKey.SHOW_HEADSET_STATUS_BAR_ICON,
                        showHeadsetIcon
                    )
                    // 设置变更后立即在已连接时反映到状态栏（HeadsetIconShowManager 拦截会跟随
                    // showHeadsetIcon 生效/放行）。
                    if (oppoConnected) applyHeadsetIcon(ctx, showHeadsetIcon)
                    Log.d(TAG, "headset icon setting synced: show=$showHeadsetIcon oppoConnected=$oppoConnected")
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

    private fun refreshHeadsetIconSetting() {
        reloadRemotePrefs()
        showHeadsetIcon = prefs.getBoolean(
            OppoPodsPrefsKey.SHOW_HEADSET_STATUS_BAR_ICON,
            OppoPodsPrefsKey.DEFAULT_SHOW_HEADSET_STATUS_BAR_ICON
        )
    }

    private fun applyHeadsetIcon(context: Context, visible: Boolean) {
        runCatching {
            val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
            statusBarManager.setIconVisibility("wireless_headset", visible)
        }.onFailure { Log.w(TAG, "applyHeadsetIcon failed", it) }
    }

    /**
     * Suppresses any hide of the `wireless_headset` status-bar icon while an OPPO pod is connected
     * and the user wants the icon shown.
     *
     * Rather than hooking MIUI's obfuscated HeadsetIconShowManager class (whose name changes across
     * builds), this hooks the stable framework choke point `StatusBarManager.setIconVisibility(
     * String, boolean)` — the exact API every icon writer in this process funnels through,
     * including MIUI's manager and this module itself — and filters by the version-independent slot
     * name. The module's own hide on disconnect is unaffected because it runs after [oppoConnected]
     * is cleared, and toggling the setting off clears [showHeadsetIcon] so that hide passes through.
     */
    private fun hookWirelessHeadsetIconGuard() {
        runCatching {
            // Located by name + arg count (like the rest of the module) so a boxed/primitive or
            // parameter-order variation across builds cannot break the lookup.
            val method = findMethodByParamCount("android.app.StatusBarManager", "setIconVisibility", 2)
            hookBefore(method) {
                val slot = args.getOrNull(0) as? String ?: return@hookBefore
                if (slot != "wireless_headset") return@hookBefore
                val visible = args.getOrNull(1) as? Boolean ?: return@hookBefore
                if (!visible && oppoConnected && showHeadsetIcon) {
                    // 吞掉隐藏调用：跳过原方法，让本模块点亮的 wireless_headset 图标保持常驻。
                    result = null
                    Log.d(TAG, "suppressed wireless_headset hide while OPPO connected")
                } else if (!visible) {
                    Log.i(
                        TAG,
                        "wireless_headset hide passed through in com.android.bluetooth: " +
                            "oppoConnected=$oppoConnected showHeadsetIcon=$showHeadsetIcon"
                    )
                }
            }
            Log.d(TAG, "hooked StatusBarManager.setIconVisibility to retain wireless_headset for OPPO")
        }.onFailure { Log.w(TAG, "hook wireless_headset icon guard skipped", it) }
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
