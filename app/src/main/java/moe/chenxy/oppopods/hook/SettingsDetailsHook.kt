package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import java.util.WeakHashMap

/**
 * 新版设置详情页 hook（路径 B：BluetoothDeviceDetailsFragment 数据驱动页）。
 *
 * 新版设置详情页 hook（路径 B）：让 OPPO 耳机在标准的蓝牙设备详情页
 * （BluetoothDeviceDetailsFragment 数据驱动页）显示「耳机图 / 电量 / 降噪控制」。
 * 旧版 MiuiHeadsetActivity 专属页 hook 已废弃移除，本 hook 为唯一设置详情页 hook。
 *
 * 逆向来源：[docs/Settings_Headset_Detail_Page_Analysis.md]（jadx 加载的 com.android.settings）。
 *
 * 设计要点：
 *  - 耳机图与电量来自蓝牙设备的 metadata / BatteryLevelsInfo → 通过强制高级头部可见 +
 *    注入 BatteryLevelsInfo 与关键 metadata 让原生头部 UI 显示。
 *  - 降噪控件来自 DeviceSettingServiceConnection 下发的 DeviceSettingsConfig → 在
 *    getDeviceSettingsConfig 返回后替换为「原配置 + OPPO 自定义 MultiTogglePreference」，
 *    并把用户切换动作经 updateDeviceSettings 回传到 OPPO 指令广播。
 *  - 所有 hook 均用 runCatching 兜底，且 OPPO 判定失败时直接放行，不影响其他设备。
 */
@SuppressLint("MissingPermission")
object SettingsDetailsHook : HookContext() {
    private const val TAG = "OppoPods-Details"

    // 自定义 ANC 控件 settingId（不会与系统/厂商 provider 冲突）
    private const val OPPO_ANC_SETTING_ID = 990001
    private const val PREFS_NAME = "oppopods_details_state"
    private const val REFRESH_INTERVAL_MS = 3_000L

    // 三个降噪档位：关闭 / 通透 / 降噪
    private val ANC_TOGGLE_LABELS = arrayOf("关闭", "通透", "降噪")

    private val knownOppoAddresses = linkedSetOf<String>()
    private val fragments = WeakHashMap<Any, Boolean>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var statusReceiver: BroadcastReceiver? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentCachedDevice: Any? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (fragments.keys.any { isOppoFragment(it) }) {
                requestBluetoothStatus("details-periodic")
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "details periodic refresh stopped: no active fragment")
            }
        }
    }

    override fun onHook() {
        hookAdvancedHeaderGate()
        hookBatteryMetadata()
        hookBatteryLevels()
        hookConfigInjection()
        hookFragmentLifecycle()
    }

    override fun onHotReloading() {
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
        statusReceiver?.let { receiver -> runCatching { context?.unregisterReceiver(receiver) } }
        statusReceiver = null
        receiverRegistered = false
        context = null
    }

    // -----------------------------------------------------------------------------------------
    // 1. 强制高级头部可见（耳机图 + 电量布局的入口）
    // -----------------------------------------------------------------------------------------
    private fun hookAdvancedHeaderGate() {
        runCatching {
            // BluetoothUtils.isAdvancedDetailsHeader(BluetoothDevice): int  -> 0 表示可用
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "isAdvancedDetailsHeader", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice
                if (isOppoPod(device)) {
                    Log.d(TAG, "isAdvancedDetailsHeader forced 0 for OPPO device=${device?.address}")
                    result = 0
                }
            }
        }.onFailure { Log.w(TAG, "hook isAdvancedDetailsHeader skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 2. 注入电量相关的蓝牙 metadata（决定头部显示左/右/盒三路电量与充电态）
    // -----------------------------------------------------------------------------------------
    private fun hookBatteryMetadata() {
        // BluetoothUtils.getIntMetaData(BluetoothDevice, int)
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "getIntMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice
                val key = args[1] as? Int ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                val level = when (key) {
                    10 -> oppoLevel(currentBattery.left)
                    11 -> oppoLevel(currentBattery.right)
                    12 -> 50 // 作为「双设备布局」开关阈值，真实电量由 BatteryLevelsInfo 提供
                    else -> return@hookAfter
                }
                Log.d(TAG, "getIntMetaData($key) -> $level for OPPO")
                result = level
            }
        }.onFailure { Log.w(TAG, "hook getIntMetaData skipped", it) }

        // BluetoothUtils.getBooleanMetaData(BluetoothDevice, int)  -> 充电态 / 拆解耳机标识
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.BluetoothUtils", "getBooleanMetaData", BluetoothDevice::class.java, Integer.TYPE)) {
                val device = args[0] as? BluetoothDevice
                val key = args[1] as? Int ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                val value = when (key) {
                    6 -> true // isUntetheredHeadset
                    13 -> currentBattery.left?.isCharging == true
                    14 -> currentBattery.right?.isCharging == true
                    15 -> currentBattery.case?.isCharging == true
                    else -> return@hookAfter
                }
                Log.d(TAG, "getBooleanMetaData($key) -> $value for OPPO")
                result = value
            }
        }.onFailure { Log.w(TAG, "hook getBooleanMetaData skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 3. 注入 BatteryLevelsInfo（头部电量控件直接读取它）
    // -----------------------------------------------------------------------------------------
    private fun hookBatteryLevels() {
        runCatching {
            hookAfter(findMethod("com.android.settingslib.bluetooth.CachedBluetoothDevice", "getBatteryLevelsInfo")) {
                val cached = instance
                val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
                if (isOppoPod(device)) {
                    val info = oppoBatteryLevelsInfo()
                    Log.d(TAG, "getBatteryLevelsInfo -> $info for OPPO")
                    result = info
                }
            }
        }.onFailure { Log.w(TAG, "hook getBatteryLevelsInfo skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 4. 注入降噪控件（best-effort）：把 OPPO 自定义 MultiTogglePreference 追加进 DeviceSettingsConfig，
    //    并拦截 updateDeviceSettings 把切换动作回传给 OPPO 指令广播。
    // -----------------------------------------------------------------------------------------
    private fun hookConfigInjection() {
        // DeviceSettingServiceConnection.getDeviceSettingsConfig(Continuation) 返回 DeviceSettingsConfig?
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection", "getDeviceSettingsConfig", 1)) {
                val cached = runCatching { getObjectField(instance, "cachedDevice") }.getOrNull()
                val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
                if (!isOppoPod(device)) return@hookAfter
                val original = result
                val augmented = runCatching { buildAugmentedConfig(original) }.getOrNull()
                if (augmented != null) {
                    Log.d(TAG, "getDeviceSettingsConfig augmented with OPPO ANC item (original=$original)")
                    result = augmented
                }
            }
        }.onFailure { Log.w(TAG, "hook getDeviceSettingsConfig skipped", it) }

        // DeviceSettingServiceConnection.updateDeviceSettings(int settingId, DeviceSettingPreferenceState, Continuation)
        runCatching {
            hookBefore(findMethodByParamCount("com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection", "updateDeviceSettings", 3)) {
                val settingId = args[0] as? Int ?: return@hookBefore
                if (settingId != OPPO_ANC_SETTING_ID) return@hookBefore
                val state = args[1]
        val toggleState = runCatching<Int?> {
            // MultiTogglePreferenceState.getIntState() / getState()
            callMethod(state, "getState") as? Int
        }.getOrNull() ?: runCatching<Int?> { getObjectField(state, "mState") as? Int }.getOrNull() ?: 0
                val oppoMode = when (toggleState) {
                    1 -> 2
                    2 -> 3
                    else -> 1
                }
                Log.d(TAG, "updateDeviceSettings OPPO ANC toggleState=$toggleState -> oppoMode=$oppoMode")
                currentAnc = oppoMode
                sendOppoAnc(oppoMode)
                sendSettingsAncChanged(oppoMode)
                result = null
            }
        }.onFailure { Log.w(TAG, "hook updateDeviceSettings skipped", it) }
    }

    private fun buildAugmentedConfig(original: Any?): Any? {
        val configClass = findClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig")
        val itemClass = findClass("com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem")

        val originalMain: List<*> = runCatching<List<Any>> { callMethod(original, "getMainContentItems") as List<Any> }.getOrNull() ?: emptyList<Any>()
        val originalMore: List<*> = runCatching<List<Any>> { callMethod(original, "getMoreSettingsItems") as List<Any> }.getOrNull() ?: emptyList<Any>()
        val originalHelp = runCatching<Any?> { callMethod(original, "getMoreSettingsHelpItem") }.getOrNull()
        val originalGroups: List<*>? = runCatching<List<Any>> { callMethod(original, "getSettingGroups") as List<Any> }.getOrNull()
        val originalExtras = runCatching<Bundle> { callMethod(original, "getExtras") as Bundle }.getOrNull() ?: Bundle.EMPTY

        val ancItem = buildAncPreference() ?: return original
        val newMain = ArrayList<Any>(originalMain.size + 1)
        for (e in originalMain) if (e != null) newMain.add(e)
        newMain.add(ancItem)
        val newMore = ArrayList<Any>(originalMore.size)
        for (e in originalMore) if (e != null) newMore.add(e)

        val ctor = configClass.getDeclaredConstructor(
            List::class.java, List::class.java, itemClass, List::class.java, Bundle::class.java
        ).apply { isAccessible = true }
        return ctor.newInstance(newMain, newMore, originalHelp, originalGroups, originalExtras)
    }

    private fun buildAncPreference(): Any? {
        val prefClass = findClass("com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference")
        val toggleClass = findClass("com.android.settingslib.bluetooth.devicesettings.ToggleInfo")

        val toggles = ArrayList<Any>(ANC_TOGGLE_LABELS.size)
        for (label in ANC_TOGGLE_LABELS) {
            val icon = makeToggleIcon(label)
            val tc = toggleClass.getDeclaredConstructor(String::class.java, Bitmap::class.java, Bundle::class.java)
                .apply { isAccessible = true }
            toggles.add(tc.newInstance(label, icon, Bundle.EMPTY))
        }

        val stateIndex = when (currentAnc) {
            2 -> 1
            3 -> 2
            else -> 0
        }
        val ctor = prefClass.getDeclaredConstructor(
            String::class.java, List::class.java, Integer.TYPE,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, Bundle::class.java
        ).apply { isAccessible = true }
        return ctor.newInstance("降噪", toggles, stateIndex, true, true, Bundle.EMPTY)
    }

    private fun makeToggleIcon(label: String): Bitmap {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { isAntiAlias = true; color = Color.DKGRAY }
        canvas.drawCircle(24f, 24f, 22f, paint)
        return bmp
    }

    // -----------------------------------------------------------------------------------------
    // 5. 详情页生命周期：识别 OPPO 设备、桥接状态、周期刷新
    // -----------------------------------------------------------------------------------------
    private fun hookFragmentLifecycle() {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.BluetoothDeviceDetailsFragment", "onResume", 0)) {
                val fragment = instance ?: return@hookAfter
                val cached = runCatching { getObjectField(fragment, "cachedDevice") }.getOrNull()
                    ?: runCatching { callMethod(fragment, "getCachedDevice") }.getOrNull()
                val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
                if (!isOppoPod(device)) return@hookAfter
                currentCachedDevice = cached
                fragments[fragment] = true
                registerStatusReceiver(fragment as? Context ?: context)
                requestBluetoothStatus("details-resume")
                startPeriodicRefresh()
                Log.d(TAG, "BluetoothDeviceDetailsFragment resumed for OPPO device=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook BluetoothDeviceDetailsFragment.onResume skipped", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 状态桥接（监听 com.android.bluetooth 下发的 ACTION_PODS_* 跨进程广播）
    // -----------------------------------------------------------------------------------------
    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        loadState()
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.batteryStatusCompat() ?: currentBattery
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        refreshHeader()
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        refreshHeader()
                    }
                }
                Log.d(TAG, "state action=${intent?.action} address=$currentAddress anc=$currentAnc battery=${settingsBatteryString()}")
            }
        }
        context?.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiver = receiver
        receiverRegistered = true
        requestBluetoothStatus("receiver-register")
        Log.d(TAG, "registered status receiver context=$context")
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        listOf(OppoPodsAction.ACTION_PODS_UI_INIT, OppoPodsAction.ACTION_REFRESH_STATUS).forEach { action ->
            ctx.sendBroadcast(Intent(action).apply {
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
        Log.d(TAG, "requested bluetooth status reason=$reason")
    }

    private fun startPeriodicRefresh() {
        if (refreshLoopStarted) return
        refreshLoopStarted = true
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "details periodic refresh started")
    }

    /** 通过 CachedBluetoothDevice 的属性变更回调触发头部与配置刷新。 */
    private fun refreshHeader() {
        runCatching {
            val cached = currentCachedDevice ?: return@runCatching
            // CachedBluetoothDevice.dispatchAttributesChanged() 会通知已注册的头部控制器 refresh()
            callMethod(cached, "dispatchAttributesChanged")
            Log.d(TAG, "dispatched attributes changed for header refresh")
        }.onFailure { Log.w(TAG, "refreshHeader failed", it) }
    }

    // -----------------------------------------------------------------------------------------
    // 指令下发
    // -----------------------------------------------------------------------------------------
    private fun sendOppoAnc(mode: Int) {
        val ctx = context ?: run {
            Log.w(TAG, "sendOppoAnc skipped: context is null mode=$mode")
            return
        }
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun sendSettingsAncChanged(mode: Int) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("status", mode)
            setPackage("com.android.settings")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    // -----------------------------------------------------------------------------------------
    // OPPO 识别与电量映射
    // -----------------------------------------------------------------------------------------
    private fun isOppoFragment(fragment: Any?): Boolean {
        val cached = runCatching { getObjectField(fragment, "cachedDevice") }.getOrNull()
            ?: runCatching { callMethod(fragment, "getCachedDevice") }.getOrNull()
        val device = runCatching { callMethod(cached, "getDevice") as? BluetoothDevice }.getOrNull()
        return isOppoPod(device)
    }

    private fun isOppoPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isOppoAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val result = name.contains("oppo", ignoreCase = true)
        if (result && address != null) {
            knownOppoAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return result
    }

    private fun isOppoAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownOppoAddresses
    }

    private fun oppoLevel(params: PodParams?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun oppoBatteryLevelsInfo(): Any? {
        val left = oppoLevel(currentBattery.left)
        val right = oppoLevel(currentBattery.right)
        val case = oppoLevel(currentBattery.case)
        val overall = listOf(left, right, case).filter { it >= 0 }.maxOrNull() ?: -1
        val cls = findClass("com.android.settingslib.bluetooth.BatteryLevelsInfo")
        val ctor = cls.getDeclaredConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
        return ctor.newInstance(left, right, case, overall)
    }

    private fun settingsBatteryString(): String {
        loadState()
        return "${currentBattery.left?.battery ?: -1},${currentBattery.right?.battery ?: -1},${currentBattery.case?.battery ?: -1}"
    }

    // -----------------------------------------------------------------------------------------
    // 状态持久化（与旧 hook 隔离，避免相互覆盖）
    // -----------------------------------------------------------------------------------------
    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val hasSavedBattery = prefs.getBoolean("left_connected", false) ||
            prefs.getBoolean("right_connected", false) ||
            prefs.getBoolean("case_connected", false)
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
        if (!hasSavedBattery && hasCurrentBattery()) return
        currentBattery = BatteryParams(
            left = PodParams(prefs.getInt("left_battery", 0), prefs.getBoolean("left_charging", false), prefs.getBoolean("left_connected", false), 0),
            right = PodParams(prefs.getInt("right_battery", 0), prefs.getBoolean("right_charging", false), prefs.getBoolean("right_connected", false), 0),
            case = PodParams(prefs.getInt("case_battery", 0), prefs.getBoolean("case_charging", false), prefs.getBoolean("case_connected", false), 0)
        )
    }

    private fun hasCurrentBattery(): Boolean {
        return currentBattery.left?.isConnected == true ||
            currentBattery.right?.isConnected == true ||
            currentBattery.case?.isConnected == true
    }
}
