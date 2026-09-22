package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import java.util.Collections

/**
 * 最新 HyperOS 中，管理 `wireless_headset` 状态栏图标的 HeadsetIconShowManager（由
 * MiuiNearbyServiceV2 托管，混淆名 com.android.bluetooth.ble.app.i）运行在
 * com.xiaomi.bluetooth 进程：设备连接后若无法识别为小米生态设备（OPPO 即如此），
 * 它会在约 500ms 后调用 StatusBarManager.setIconVisibility("wireless_headset", false)
 * 隐藏图标。该调用不经过 com.android.bluetooth 进程，HeadsetStateDispatcher 的守卫
 * 拦不到，导致模块点亮的耳机图标闪现后消失。本 Hook 在 com.xiaomi.bluetooth 进程内
 * 对同一框架稳定点补一份拦截。
 *
 * 连接状态采用双信号判定：HEADSET/A2DP 连接广播跟踪（不依赖 profile proxy）+
 * BluetoothManager.getConnectedDevices 实时查询（覆盖进程启动前已连接的场景）。
 * 每次隐藏调用的判定结果都输出 INFO 日志（tag OppoPods-Bluetooth），便于在设备上
 * 确认隐藏调用是否流经本进程以及拦截失败的原因。
 */
object XiaomiBluetoothHeadsetIconGuard : HookContext() {
    private const val TAG = "OppoPods-Bluetooth"
    private const val HEADSET_ICON_SLOT = "wireless_headset"

    @Volatile
    private var appContext: Context? = null

    // 广播跟踪到的已连接 OPPO 耳机地址，作为实时查询之外的兜底信号。
    private val connectedOppoAddresses = Collections.synchronizedSet(mutableSetOf<String>())
    private var connectionReceiver: BroadcastReceiver? = null
    private var connectionReceiverContext: Context? = null

    override fun onHook() {
        // Application.onCreate 声明于 android.app.Application（框架稳定 API），进程启动
        // 早期必然执行；热重载后不会再次触发，由 currentApplication() 反射兜底。
        runCatching {
            hookAfter(findMethod("android.app.Application", "onCreate")) {
                val app = instance as? Application ?: return@hookAfter
                onContextAvailable(app)
            }
        }.onFailure { Log.w(TAG, "hook Application.onCreate for icon guard failed", it) }

        // 拦截所有首参为 String 的 setIconVisibility 重载（避免不同版本签名差异导致
        // 按参数个数查找绑到错误重载），再按 slot 名过滤。
        runCatching {
            val overloads = findClass("android.app.StatusBarManager").declaredMethods
                .filter { it.name == "setIconVisibility" && it.parameterTypes.firstOrNull() == String::class.java }
            overloads.forEach { method ->
                method.isAccessible = true
                hookBefore(method) {
                    val slot = args.getOrNull(0) as? String ?: return@hookBefore
                    if (slot != HEADSET_ICON_SLOT) return@hookBefore
                    val visible = args.filterIsInstance<Boolean>().firstOrNull() ?: return@hookBefore
                    if (visible) return@hookBefore
                    if (shouldSuppressHide()) {
                        // 吞掉隐藏调用：跳过原方法，保留 HeadsetStateDispatcher 点亮的图标。
                        result = null
                    }
                }
            }
            if (overloads.isEmpty()) {
                Log.w(TAG, "no setIconVisibility(String,*) overload found in com.xiaomi.bluetooth")
            } else {
                Log.d(TAG, "hooked ${overloads.size} setIconVisibility(String,*) overload(s) in com.xiaomi.bluetooth")
            }
        }.onFailure { Log.w(TAG, "hook wireless_headset icon guard (xiaomi.bluetooth) skipped", it) }
    }

    override fun onHotReloading() {
        connectionReceiver?.let { receiver ->
            runCatching { connectionReceiverContext?.unregisterReceiver(receiver) }
        }
        connectionReceiver = null
        connectionReceiverContext = null
        connectedOppoAddresses.clear()
        appContext = null
    }

    private fun shouldSuppressHide(): Boolean {
        val context = ensureContext()
        val oppoConnected = isOppoPodConnected(context)
        reloadRemotePrefs()
        val showHeadsetIcon = prefs.getBoolean(
            OppoPodsPrefsKey.SHOW_HEADSET_STATUS_BAR_ICON,
            OppoPodsPrefsKey.DEFAULT_SHOW_HEADSET_STATUS_BAR_ICON
        )
        val suppress = context != null && oppoConnected && showHeadsetIcon
        if (suppress) {
            Log.i(TAG, "suppressed wireless_headset hide in com.xiaomi.bluetooth")
        } else {
            Log.i(
                TAG,
                "wireless_headset hide passed through in com.xiaomi.bluetooth: " +
                    "context=${context != null} oppoConnected=$oppoConnected showSetting=$showHeadsetIcon"
            )
        }
        return suppress
    }

    private fun ensureContext(): Context? {
        appContext?.let { return it }
        val context = currentApplication() ?: return null
        onContextAvailable(context)
        return appContext
    }

    private fun onContextAvailable(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
        }
        registerConnectionReceiver(context)
    }

    private fun registerConnectionReceiver(context: Context) {
        if (connectionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent) {
                val device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                ) ?: return
                if (!isOppoPod(device)) return
                val state = intent.getIntExtra(
                    BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED
                )
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> connectedOppoAddresses += device.address
                    BluetoothProfile.STATE_DISCONNECTING,
                    BluetoothProfile.STATE_DISCONNECTED -> connectedOppoAddresses -= device.address
                }
            }
        }
        // 这两个是受保护的系统广播，仅蓝牙栈可发送，EXPORTED 注册不会引入伪造风险。
        val filter = IntentFilter().apply {
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        }
        runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "register connection receiver failed", it) }
        connectionReceiver = receiver
        connectionReceiverContext = context.applicationContext ?: context
    }

    private fun isOppoPodConnected(context: Context?): Boolean {
        if (connectedOppoAddresses.isNotEmpty()) return true
        if (context == null) return false
        val bluetoothManager = runCatching {
            context.getSystemService(BluetoothManager::class.java)
        }.getOrNull() ?: return false
        return listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any { profile ->
            runCatching { bluetoothManager.getConnectedDevices(profile) }
                .getOrNull()
                .orEmpty()
                .any(::isOppoPod)
        }
    }

    /** 任意应用进程内均可通过 ActivityThread 静态方法取到当前 Application。 */
    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()

    /** 与 HeadsetStateDispatcher 的判定保持一致：设备名含 "oppo"。 */
    @SuppressLint("MissingPermission")
    private fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: device.alias ?: return false
        return name.contains("oppo", ignoreCase = true)
    }
}
