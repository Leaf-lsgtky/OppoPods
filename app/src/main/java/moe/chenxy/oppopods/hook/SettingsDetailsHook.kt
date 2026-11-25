package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings 17 的标准蓝牙详情页适配：入口 → 原生头部 → 原生分段 ANC 控件 → RFCOMM 状态回报。
 * 目标 APK 接口与兼容边界见 docs/Settings_17_Hook_Repair.md。
 */
@SuppressLint("MissingPermission")
object SettingsDetailsHook : HookContext() {
    private const val TAG = "OppoPods-Details"
    private const val DETAILS_FRAGMENT = "com.android.settings.bluetooth.BluetoothDeviceDetailsFragment"
    private const val PREFS_NAME = "oppopods_details_state"
    private const val REFRESH_INTERVAL_MS = 3_000L
    private const val ANC_CONFIRM_TIMEOUT_MS = 5_000L

    private class FragmentSession(val address: String, val cachedDevice: Any) {
        var resumed = false
        var originalLayout: Any? = null
        var needsRebind = false
    }

    private val knownOppoAddresses = ConcurrentHashMap.newKeySet<String>()
    private val deviceStates = ConcurrentHashMap<String, SettingsDeviceState>()
    private val originalLayouts = ConcurrentHashMap<String, Any>()
    private val ancFlows = ConcurrentHashMap<String, Any>()
    private val pendingAncTimeouts = ConcurrentHashMap<String, Runnable>()
    private val fragments = WeakHashMap<Any, FragmentSession>()
    private var context: Context? = null
    private var statusReceiver: BroadcastReceiver? = null
    private var nativeBridge: SettingsDeviceSettingsBridge? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    @Volatile private var disposed = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!disposed && sessions().any { it.resumed }) {
                requestBluetoothStatus(snapshot = false)
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
            }
        }
    }

    override fun onHook() {
        disposed = false
        nativeBridge = runCatching { SettingsDeviceSettingsBridge(appClassLoader) }
            .onFailure { Log.w(TAG, "native device-settings API unavailable; skip ANC and routing", it) }
            .getOrNull()
        hookAdvancedHeader()
        hookBatteryMetadata()
        hookBatteryLevels()
        hookFragmentLifecycle()
        nativeBridge?.let {
            hookNativeAnc(it)
            hookDetailsEntry()
            refreshHandler.post { if (!disposed) rebindOpenDetails() }
        }
    }

    override fun onHotReloading() {
        disposed = true
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
        unregisterStatusReceiver()
        // 留在宿主收集器中的只有宿主模型，旧控件变为不可操作，不保留 module 回调。
        nativeBridge?.let { bridge ->
            ancFlows.forEach { (_, flow) ->
                runCatching { bridge.updateFlow(flow, SettingsDeviceState().disconnected()) }
            }
        }
        synchronized(fragments) { fragments.clear() }
        ancFlows.clear()
        pendingAncTimeouts.clear()
        originalLayouts.clear()
        deviceStates.clear()
        knownOppoAddresses.clear()
        nativeBridge = null
        context = null
    }

    /** 国产版默认路由到 DeviceProfilesSettings，必须在 MIUI 专属页判定点显式打开标准详情页。 */
    private fun hookDetailsEntry() {
        val cachedClass = findClass("com.android.settingslib.bluetooth.CachedBluetoothDevice")
        val startPanel = findMethod(
            "com.android.settings.MiuiUtils", "startPreferencePanel",
            Activity::class.java, String::class.java, Bundle::class.java, Integer.TYPE,
            CharSequence::class.java, findClass("androidx.fragment.app.Fragment"), Integer.TYPE
        )
        listOf(
            "com.android.settings.bluetooth.BluetoothSettings",
            "com.android.settings.bluetooth.MiuiBluetoothSettings"
        ).forEach { className ->
            install("$className.checkStartMiuiHeadset") {
                hookBefore(findMethod(className, "checkStartMiuiHeadset", cachedClass)) {
                    val device = deviceOf(args[0]) ?: return@hookBefore
                    if (!isOppoPod(device)) return@hookBefore
                    guarded("open standard details") {
                        val activity = callMethod(instance, "getActivity") as? Activity ?: return@guarded
                        initializeContext(activity)
                        val arguments = Bundle().apply {
                            putString("device_address", device.address)
                            putParcelable("device", device)
                        }
                        val title = findClass("com.android.settings.R\$string")
                            .getField("bluetooth_device_advanced_title").getInt(null)
                        startPanel.invoke(null, activity, DETAILS_FRAGMENT, arguments, title, null, null, 0)
                        Log.d(TAG, "routed OPPO device to BluetoothDeviceDetailsFragment")
                        result = true
                    }
                }
            }
        }
    }

    private fun hookAdvancedHeader() {
        install("advanced header") {
            val method = findMethod(
                "com.android.settingslib.bluetooth.BluetoothUtils", "isAdvancedDetailsHeader",
                BluetoothDevice::class.java
            )
            check(method.returnType == java.lang.Boolean.TYPE) { "unexpected advanced-header return type" }
            hookBefore(method) {
                if (isOppoPod(args[0] as? BluetoothDevice)) result = true
            }
        }
    }

    private fun hookBatteryMetadata() {
        val utils = "com.android.settingslib.bluetooth.BluetoothUtils"
        install("battery metadata") {
            hookAfter(findMethod(utils, "getIntMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                val state = stateFor(device)
                val battery = state.battery
                if (battery == null && state.connected != false) return@hookAfter
                result = when (args[1] as? Int) {
                    10 -> battery?.left ?: -1
                    11 -> battery?.right ?: -1
                    12 -> battery?.case ?: -1
                    else -> return@hookAfter
                }
            }
        }
        install("TWS and charging metadata") {
            hookAfter(findMethod(utils, "getBooleanMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                val key = args[1] as? Int ?: return@hookAfter
                if (key == 6) {
                    result = true
                    return@hookAfter
                }
                val state = stateFor(device)
                val battery = state.battery
                if (battery == null && state.connected != false) return@hookAfter
                result = when (key) {
                    13 -> battery?.leftCharging == true
                    14 -> battery?.rightCharging == true
                    15 -> battery?.caseCharging == true
                    else -> return@hookAfter
                }
            }
        }
    }

    private fun hookBatteryLevels() {
        install("battery levels") {
            val constructor = findConstructor(
                "com.android.settingslib.bluetooth.BatteryLevelsInfo",
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
            hookBefore(findMethod("com.android.settingslib.bluetooth.CachedBluetoothDevice", "getBatteryLevelsInfo")) {
                val device = deviceOf(instance) ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                val state = stateFor(device)
                val battery = state.battery
                if (battery == null && state.connected != false) return@hookBefore
                guarded("build battery levels") {
                    result = if (battery != null && battery.overall >= 0) {
                        constructor.newInstance(battery.left, battery.right, battery.case, battery.overall)
                    } else null
                }
            }
        }
    }

    /** 同步返回宿主模型，避开无 provider 的挂起链；布局、实时条目、操作回传三条链缺一不可。 */
    private fun hookNativeAnc(bridge: SettingsDeviceSettingsBridge) {
        install("ANC layout") {
            hookBefore(bridge.getNodes) {
                if (!bridge.isMainFragment(args[0])) return@hookBefore
                val device = deviceOf(runCatching { getObjectField(instance, "cachedDevice") }.getOrNull())
                    ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                val original = originalLayouts[addressOf(device)] ?: return@hookBefore
                guarded("build ANC layout") {
                    result = bridge.layoutWithAnc(original, stateFor(device).showAnc)
                }
            }
        }
        install("ANC state flow") {
            hookBefore(bridge.getDeviceSetting) {
                if (args[0] != SettingsDeviceSettingsBridge.ANC_SETTING_ID) return@hookBefore
                val device = connectionDevice(instance) ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                guarded("create ANC flow") {
                    val address = addressOf(device)
                    result = ancFlows.computeIfAbsent(address) { bridge.flow(bridge.setting(stateFor(device))) }
                }
            }
        }
        install("ANC commands") {
            hookBefore(bridge.updateDeviceSettings) {
                if (args[0] != SettingsDeviceSettingsBridge.ANC_SETTING_ID) return@hookBefore
                val device = connectionDevice(instance) ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                // suspend Unit 的同步返回值属于宿主 ClassLoader，不能返回 null 或模块的 Unit。
                result = bridge.unit
                guarded("send ANC command") { requestAnc(device, bridge.selection(args[1])) }
            }
        }
        install("capture original layout") {
            hookAfter(bridge.onViewCreated) {
                val fragment = instance ?: return@hookAfter
                if (!findClass(DETAILS_FRAGMENT).isInstance(fragment)) return@hookAfter
                val session = sessionFor(fragment) ?: return@hookAfter
                guarded("capture original layout") {
                    val original = getObjectField(fragment, "originalDisplayOrder") ?: return@guarded
                    session.originalLayout = original
                    session.needsRebind = false
                    originalLayouts[session.address] = original
                    bridge.requestLayout(fragment)
                }
            }
        }
    }

    private fun hookFragmentLifecycle() {
        install("details context") {
            val onAttach = findMethod(DETAILS_FRAGMENT, "onAttach", Context::class.java)
            hookBefore(onAttach) {
                guarded("initialize details context") { (args[0] as? Context)?.let(::initializeContext) }
            }
            hookAfter(onAttach) {
                val device = connectionDevice(instance) ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                guarded("register details receiver") { (args[0] as? Context)?.let(::registerStatusReceiver) }
            }
        }
        install("details resume") {
            hookAfter(findMethod(DETAILS_FRAGMENT, "onResume")) {
                val fragment = instance ?: return@hookAfter
                val session = sessionFor(fragment) ?: return@hookAfter
                guarded("resume details") {
                    (callMethod(fragment, "getContext") as? Context)?.let(::registerStatusReceiver)
                    session.resumed = true
                    rebindSession(fragment, session)
                    requestBluetoothStatus(snapshot = true)
                    startPeriodicRefresh()
                    refreshHeader(session.address)
                }
            }
        }
        // 这两个方法由父类实现，直接 getDeclaredMethod(DETAILS_FRAGMENT, ...) 找不到。
        install("details pause") {
            hookAfter(findMethod("androidx.fragment.app.Fragment", "onPause")) {
                val session = synchronized(fragments) { fragments[instance] } ?: return@hookAfter
                session.resumed = false
                stopIdleRefresh()
            }
        }
        install("details view cleanup") {
            hookAfter(findMethod("androidx.fragment.app.Fragment", "onDestroyView")) {
                val session = synchronized(fragments) { fragments.remove(instance) }
                if (session != null) {
                    session.originalLayout?.let { originalLayouts.remove(session.address, it) }
                    stopIdleRefresh()
                }
            }
        }
    }

    private fun initializeContext(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext ?: ctx
        val preferences = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        knownOppoAddresses.addAll(preferences.getStringSet("known_addresses", emptySet()).orEmpty())
        preferences.getString("address", null)?.let { knownOppoAddresses.add(normalize(it)) }
        if (knownOppoAddresses.isNotEmpty()) {
            preferences.edit().putStringSet("known_addresses", knownOppoAddresses.toSet()).apply()
        }
    }

    private fun registerStatusReceiver(ctx: Context) {
        initializeContext(ctx)
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (disposed || intent == null) return
                guarded("receive details state") { receiveState(intent) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_PROFILE_CHANGED)
        }
        context!!.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiver = receiver
    }

    private fun receiveState(intent: Intent) {
        val address = (intent.getStringExtra("address") ?: intent.getStringExtra("device_address"))
            ?.takeIf { it.isNotBlank() }?.let(::normalize) ?: return
        if (address !in knownOppoAddresses) {
            if (!intent.getStringExtra("device_name").orEmpty().contains("oppo", ignoreCase = true)) return
            rememberAddress(address)
        }
        var layoutChanged = false
        deviceStates.compute(address) { _, previous ->
            val state = previous ?: SettingsDeviceState()
            when (intent.action) {
                OppoPodsAction.ACTION_PODS_CONNECTED -> state.copy(connected = true)
                OppoPodsAction.ACTION_PODS_DISCONNECTED -> state.disconnected()
                OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                    if (state.connected == false) return@compute state
                    val battery = intent.batteryStatusCompat() ?: return@compute state
                    state.copy(battery = batterySnapshot(battery))
                }
                OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                    if (state.connected == false) return@compute state
                    val mode = intent.getIntExtra("status", -1).takeIf { it in 1..4 } ?: return@compute state
                    state.withAncReport(mode)
                }
                OppoPodsAction.ACTION_PODS_PROFILE_CHANGED -> {
                    val json = intent.getStringExtra(OppoPodsAction.EXTRA_PROFILE_JSON) ?: return@compute state
                    val modes = DeviceProfileStore.parse(json).supportedAncModes()
                    layoutChanged = state.showAnc != (modes.size >= 2)
                    state.copy(
                        supportedAncModes = modes,
                        pendingAncMode = state.pendingAncMode?.takeIf { it in modes },
                    )
                }
                else -> state
            }
        }
        publishState(address, layoutChanged)
    }

    private fun publishState(address: String, layoutChanged: Boolean = false) = onMain {
        val state = deviceStates[address] ?: return@onMain
        if (state.pendingAncMode == null) pendingAncTimeouts.remove(address)?.let(refreshHandler::removeCallbacks)
        nativeBridge?.let { bridge ->
            ancFlows[address]?.let { flow -> guarded("publish ANC state") { bridge.updateFlow(flow, state) } }
            if (layoutChanged) fragmentEntries().filter { it.second.address == address }.forEach { (fragment, _) ->
                guarded("update ANC visibility") { bridge.requestLayout(fragment) }
            }
        }
        refreshHeader(address)
    }

    private fun requestAnc(device: BluetoothDevice, index: Int?) {
        val address = addressOf(device)
        val state = stateFor(device)
        val mode = index?.let(state::modeForSelection)
        val ctx = context
        if (mode == null || ctx == null || mode == state.ancMode) {
            // 原生按钮点击后会先禁用自己，无效/同值请求也需要重发真实状态解除锁定。
            deviceStates.putIfAbsent(address, state)
            publishState(address)
            return
        }
        deviceStates.compute(address) { _, latest -> (latest ?: state).copy(pendingAncMode = mode) }
        onMain {
            pendingAncTimeouts.remove(address)?.let(refreshHandler::removeCallbacks)
            val timeout = Runnable {
                pendingAncTimeouts.remove(address)
                deviceStates.computeIfPresent(address) { _, latest -> latest.copy(pendingAncMode = null) }
                publishState(address)
                Log.w(TAG, "ANC confirmation timed out; restored last reported selection")
            }
            pendingAncTimeouts[address] = timeout
            refreshHandler.postDelayed(timeout, ANC_CONFIRM_TIMEOUT_MS)
            publishState(address)
        }
        runCatching {
            ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                setPackage("com.android.bluetooth")
                putExtra("address", device.address)
                putExtra("status", mode)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d(TAG, "requested ANC mode=$mode")
        }.onFailure {
            deviceStates.computeIfPresent(address) { _, latest -> latest.copy(pendingAncMode = null) }
            publishState(address)
            Log.w(TAG, "send ANC command failed", it)
        }
    }

    private fun batterySnapshot(params: BatteryParams): SettingsBatterySnapshot {
        val left = SettingsBatterySnapshot.level(params.left?.battery, params.left?.isConnected == true)
        val right = SettingsBatterySnapshot.level(params.right?.battery, params.right?.isConnected == true)
        val case = SettingsBatterySnapshot.level(params.case?.battery, params.case?.isConnected == true)
        return SettingsBatterySnapshot(
            left, right, case,
            left >= 0 && params.left?.isCharging == true,
            right >= 0 && params.right?.isCharging == true,
            case >= 0 && params.case?.isCharging == true
        )
    }

    private fun requestBluetoothStatus(snapshot: Boolean) {
        val ctx = context ?: return
        val actions = if (snapshot) listOf(OppoPodsAction.ACTION_PODS_UI_INIT, OppoPodsAction.ACTION_REFRESH_STATUS)
            else listOf(OppoPodsAction.ACTION_REFRESH_STATUS)
        actions.forEach { action ->
            ctx.sendBroadcast(Intent(action).apply {
                setPackage("com.android.bluetooth")
                putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, false)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    private fun startPeriodicRefresh() {
        if (refreshLoopStarted || disposed) return
        refreshLoopStarted = true
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun stopIdleRefresh() {
        if (sessions().any { it.resumed }) return
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshLoopStarted = false
        unregisterStatusReceiver()
        // 暂停期间可能错过断连广播。恢复页面后先等待新快照，不能把旧选中态当作确认。
        pendingAncTimeouts.values.forEach(refreshHandler::removeCallbacks)
        pendingAncTimeouts.clear()
        deviceStates.replaceAll { _, state -> state.withoutLiveConfirmation() }
        nativeBridge?.let { bridge ->
            ancFlows.forEach { (address, flow) ->
                guarded("invalidate paused state") { bridge.updateFlow(flow, deviceStates[address] ?: SettingsDeviceState()) }
            }
        }
    }

    private fun unregisterStatusReceiver() {
        statusReceiver?.let { receiver -> runCatching { context?.unregisterReceiver(receiver) } }
        statusReceiver = null
    }

    private fun refreshHeader(address: String) {
        sessions().filter { it.address == address && it.resumed }.forEach { session ->
            guarded("refresh battery header") { callMethod(session.cachedDevice, "dispatchAttributesChanged") }
        }
    }

    private fun sessionFor(fragment: Any): FragmentSession? {
        synchronized(fragments) { fragments[fragment] }?.let { return it }
        val cached = runCatching { getObjectField(fragment, "cachedDevice") }.getOrNull() ?: return null
        val device = deviceOf(cached) ?: return null
        if (!isOppoPod(device)) return null
        return FragmentSession(addressOf(device), cached).also { session ->
            // 热重载时原生 View 可能已存在，不能只依赖将来再次触发 onViewCreated。
            session.originalLayout = runCatching { getObjectField(fragment, "originalDisplayOrder") }.getOrNull()
            session.originalLayout?.let {
                originalLayouts[session.address] = it
                session.needsRebind = true
            }
            synchronized(fragments) { fragments[fragment] = session }
        }
    }

    private fun rebindSession(fragment: Any, session: FragmentSession) {
        val original = session.originalLayout ?: return
        val bridge = nativeBridge ?: return
        if (session.needsRebind) {
            bridge.rebindLayout(fragment, original)
            session.needsRebind = false
        } else bridge.requestLayout(fragment)
    }

    private fun rebindOpenDetails() {
        guarded("rebind open details") {
            val thread = findMethod("android.app.ActivityThread", "currentActivityThread").invoke(null) ?: return@guarded
            val records = getObjectField(thread, "mActivities") as? Map<*, *> ?: return@guarded
            records.values.forEach { record ->
                val activity = runCatching { getObjectField(record, "activity") as? Activity }.getOrNull()
                    ?: return@forEach
                val manager = runCatching { callMethod(activity, "getSupportFragmentManager") }.getOrNull()
                    ?: return@forEach
                rebindFragments(manager)
            }
        }
    }

    private fun rebindFragments(manager: Any, depth: Int = 0) {
        if (depth > 8) return
        val visible = runCatching { callMethod(manager, "getFragments") as? List<*> }.getOrNull().orEmpty()
        visible.filterNotNull().forEach { fragment ->
            if (findClass(DETAILS_FRAGMENT).isInstance(fragment) &&
                runCatching { callMethod(fragment, "isResumed") == true }.getOrDefault(false)
            ) {
                guarded("rebind resumed details") {
                    val session = sessionFor(fragment) ?: return@guarded
                    (callMethod(fragment, "getContext") as? Context)?.let(::registerStatusReceiver)
                    session.resumed = true
                    rebindSession(fragment, session)
                    requestBluetoothStatus(snapshot = true)
                    startPeriodicRefresh()
                }
            }
            runCatching { callMethod(fragment, "getChildFragmentManager") }.getOrNull()?.let {
                rebindFragments(it, depth + 1)
            }
        }
    }

    private fun sessions(): List<FragmentSession> = synchronized(fragments) { fragments.values.toList() }
    private fun fragmentEntries(): List<Pair<Any, FragmentSession>> =
        synchronized(fragments) { fragments.entries.map { it.key to it.value } }

    private fun connectionDevice(connection: Any?): BluetoothDevice? =
        deviceOf(runCatching { getObjectField(connection, "cachedDevice") }.getOrNull())

    private fun deviceOf(cached: Any?): BluetoothDevice? =
        runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()

    private fun isOppoPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return runCatching {
            val address = addressOf(device)
            if (address in knownOppoAddresses) return@runCatching true
            val name = device.name.orEmpty()
            val alias = device.alias.orEmpty()
            if (!name.contains("oppo", true) && !alias.contains("oppo", true)) return@runCatching false
            rememberAddress(address)
            true
        }.getOrDefault(false)
    }

    private fun rememberAddress(address: String) {
        if (!knownOppoAddresses.add(normalize(address))) return
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putStringSet("known_addresses", knownOppoAddresses.toSet())?.apply()
    }

    private fun stateFor(device: BluetoothDevice): SettingsDeviceState =
        deviceStates[addressOf(device)] ?: SettingsDeviceState()

    private fun addressOf(device: BluetoothDevice): String = normalize(device.address)
    private fun normalize(address: String): String = address.uppercase(Locale.ROOT)

    private fun onMain(block: () -> Unit) {
        if (disposed) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else refreshHandler.post { if (!disposed) block() }
    }

    private inline fun install(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "hook $name skipped", it) }
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "$name failed", it) }
    }
}
