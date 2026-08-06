package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.MilinkSpatialAudioOptionSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import java.util.WeakHashMap

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "OppoPods-Settings"
    private const val FAKE_DEVICE_ID = "01010901"
    private const val FAKE_SUPPORT = "$FAKE_DEVICE_ID,000000000000000010000000"
    private const val PREFS_NAME = "oppopods_milink_state"
    private const val SETTINGS_REFRESH_INTERVAL_MS = 3_000L
    private val knownOppoAddresses = linkedSetOf<String>()
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var statusReceiver: BroadcastReceiver? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var milinkSpatialAudioOptionEnabled = OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
    private var proxyCheckSupportCalls = 0
    private var proxySetCommonCommandCalls = 0
    private var proxyGetDeviceConfigCalls = 0
    private var proxyGetCommonConfigCalls = 0
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshLoopStarted = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (headsetFragments.keys.any { isOppoFragment(it) }) {
                requestBluetoothStatus("settings-periodic")
                refreshHandler.postDelayed(this, SETTINGS_REFRESH_INTERVAL_MS)
            } else {
                refreshLoopStarted = false
                Log.d(TAG, "settings periodic refresh stopped: no active fragment")
            }
        }
    }

    override fun onHook() {
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookBatteryView()
        hookFragmentState()
    }

    override fun onHotReloading() {
        refreshHandler.removeCallbacksAndMessages(null)
        refreshLoopStarted = false
        statusReceiver?.let { receiver -> runCatching { context?.unregisterReceiver(receiver) } }
        statusReceiver = null
        receiverRegistered = false
        context = null
    }

    private fun hookActivityEntry() {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Activity.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=$knownOppoAddresses current=$currentAddress")
                if (!isOppoPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", FAKE_SUPPORT)
                intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
                intent.putExtra("DEVICE_ID", FAKE_DEVICE_ID)
                Log.d(TAG, "MiuiHeadsetActivity intent patched address=${device?.address}")
            }
            hookActivityStringGetter("getDeviceID") { FAKE_DEVICE_ID }
            hookActivityStringGetter("getSupport") { FAKE_SUPPORT }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Plugin.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=$knownOppoAddresses current=$currentAddress")
                if (!isOppoPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", FAKE_SUPPORT)
                intent.putExtra("DEVICE_ID", FAKE_DEVICE_ID)
                Log.d(TAG, "MiuiHeadsetActivityPlugin intent patched address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivityPlugin skipped", it) }
    }

    private fun hookActivityStringGetter(methodName: String, value: () -> String) {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetActivity", methodName, 0)) {
                val device = runCatching { getObjectField(instance, "mDevice") as? BluetoothDevice }.getOrNull()
                Log.d(TAG, "Activity.$methodName old=$result device=${device.describe()} isOppo=${isOppoPod(device)}")
                if (!isOppoPod(device)) return@hookAfter
                result = value()
                Log.d(TAG, "Activity.$methodName forced=$result")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetActivity.$methodName skipped", it) }
    }

    private fun hookSupportChecks() {
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "checkSupport") { support ->
            support.startsWith(FAKE_DEVICE_ID) || support.contains(FAKE_DEVICE_ID)
        }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isTWS01Headset") { it == FAKE_DEVICE_ID }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isK77sHeadset") { false }
        hookBleMmaConnectByContext()
        hookBleMmaConnectByService()
    }

    private fun hookStringStaticResult(className: String, methodName: String, resultForValue: (String) -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val value = args[0] as? String ?: return@hookAfter
                Log.d(TAG, "$className.$methodName value=$value old=$result")
                if (value != FAKE_DEVICE_ID && !value.startsWith(FAKE_DEVICE_ID)) return@hookAfter
                result = resultForValue(value)
                Log.d(TAG, "$className.$methodName forced value=$value result=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookBleMmaConnectByContext() {
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", Context::class.java, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Context) old=$result device=${device.describe()} deviceId=$deviceId service=${runCatching { callMethod(args[0], "getService") }.getOrNull()}")
                if (deviceId == FAKE_DEVICE_ID || isOppoPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Context) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Context) skipped", it) }
    }

    private fun hookBleMmaConnectByService() {
        runCatching {
            val serviceClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetService")
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", serviceClass, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Service) old=$result service=${args[0]} device=${device.describe()} deviceId=$deviceId")
                if (deviceId == FAKE_DEVICE_ID || isOppoPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Service) forced true")
                }
            }
        }.onFailure { Log.w(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Service) skipped", it) }
    }

    private fun hookServiceProxy() {
        val proxyClass = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"
        hookProxyStringResult(proxyClass, "checkSupport", BluetoothDevice::class.java) { FAKE_SUPPORT }
        hookProxyStringArgResult(proxyClass, "getDeviceInfo") { FAKE_SUPPORT }
        hookProxyStringArgResult(proxyClass, "isSupportAudioSwitch") {
            if (milinkSpatialAudioOptionEnabled) "1" else "0"
        }
        hookProxyStringArgResult(proxyClass, "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val command = commandArgs[0] as? Int
            if (command == 102) "0" else "1"
        }
        hookProxyVoidDeviceNoop(proxyClass, "connect", BluetoothDevice::class.java)
        hookProxyVoidDeviceNoop(proxyClass, "getDeviceConfig", BluetoothDevice::class.java)
        hookProxyVoidDeviceStringNoop(proxyClass, "getCommonConfig", BluetoothDevice::class.java, String::class.java)
        hookProxyBooleanStringResult(proxyClass, "isMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "checkIsMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "getRingFindState") { false }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncMode", Int::class.java, BluetoothDevice::class.java) { commandArgs ->
            val miMode = commandArgs[0] as? Int ?: return@hookProxyVoidDeviceCommand null
            oppoAncFromSettings(miMode)
        }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncLevel", String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val level = commandArgs[0] as? String ?: return@hookProxyVoidDeviceCommand null
            oppoAncFromLevel(level)
        }
    }

    private fun hookProxyStringResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: () -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val isOppo = isOppoPod(device)
                if (methodName == "checkSupport") proxyCheckSupportCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "checkSupport") proxyCheckSupportCalls else -1} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyStringArgResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: (List<Any?>) -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val address = args.firstOrNull { it is String } as? String
                val isOppo = isOppoPod(device) || (address != null && isOppoAddress(address))
                if (methodName == "setCommonCommand") proxySetCommonCommandCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "setCommonCommand") proxySetCommonCommandCalls else -1} args=${args.describeArgs()} device=${device.describe()} addressArg=$address isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = result(args)
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address ?: address}")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(className: String, methodName: String, result: () -> Boolean) {
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                val isOppo = isOppoAddress(address)
                Log.d(TAG, "$methodName proxy string call address=$address isOppo=$isOppo oldKnown=$knownOppoAddresses current=$currentAddress")
                if (!isOppo) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=$address")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceCommand(className: String, methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                Log.d(TAG, "$methodName proxy command args=${args.describeArgs()} device=${device.describe()} isOppo=${isOppoPod(device)}")
                if (!isOppoPod(device)) return@hookBefore
                val oppoMode = mode(args) ?: return@hookBefore
                currentAnc = oppoMode
                sendOppoAnc(oppoMode)
                sendSettingsAncChanged(oppoMode)
                this.result = null
                Log.d(TAG, "$methodName proxy command handled address=${device?.address} oppoMode=$oppoMode")
            }
        }.onFailure { Log.w(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls++
                val isOppo = isOppoPod(device)
                Log.d(TAG, "$methodName proxy before#${if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls else -1} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceStringNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                proxyGetCommonConfigCalls++
                val isOppo = isOppoPod(device)
                Log.d(TAG, "$methodName proxy before#$proxyGetCommonConfigCalls args=${args.describeArgs()} device=${device.describe()} isOppo=$isOppo")
                if (!isOppo) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.w(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookBatteryView() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", 4)) {
                val device = args[0] as? BluetoothDevice ?: return@hookConstructorAfter
                val ctx = args[1] as? Context
                registerStatusReceiver(ctx)
                Log.d(TAG, "Battery.<init> device=${device.describe()} isOppo=${isOppoPod(device)} ctx=$ctx currentBattery=${settingsBatteryString()}")
                if (!isOppoPod(device)) return@hookConstructorAfter
                batteryViews[instance ?: return@hookConstructorAfter] = device
                requestBluetoothStatus("battery-init")
                updateBatteryView(instance)
                Log.d(TAG, "MiuiHeadsetBattery registered address=${device.address}")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery constructor skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", "onBatteryChanged", String::class.java)) {
                val device = batteryViews[instance]
                Log.d(TAG, "Battery.onBatteryChanged(String) original=${args[0]} mappedDevice=${device.describe()} isOppo=${isOppoPod(device)} forced=${settingsBatteryString()}")
                if (!isOppoPod(device)) return@hookBefore
                result = null
                updateBatteryView(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetBattery.onBatteryChanged(String) skipped", it) }
    }

    private fun hookFragmentState() {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onCreateView", 3)) {
                registerStatusReceiver(runCatching { getObjectField(instance, "mActivity") as? Context }.getOrNull())
                Log.d(TAG, "Fragment.onCreateView after ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("fragment-create")
                startPeriodicRefresh()
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onServiceConnected", 0)) {
                Log.d(TAG, "Fragment.onServiceConnected after ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("service-connected")
                startPeriodicRefresh()
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "refreshStatus", String::class.java, String::class.java)) {
                val key = args[0] as? String
                val data = args[1] as? String
                Log.d(TAG, "Fragment.refreshStatus before key=$key data=$data ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (isOppoFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    Log.w(TAG, "Fragment.refreshStatus swallowed MMA failure for virtual Oppo device key=$key")
                    injectFragmentStatus(instance)
                    result = null
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "handleConnectMmaFailed", String::class.java)) {
                Log.w(TAG, "Fragment.handleConnectMmaFailed arg=${args[0]} ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (isOppoFragment(instance)) {
                    injectFragmentStatus(instance)
                    result = null
                    Log.w(TAG, "Fragment.handleConnectMmaFailed swallowed for virtual Oppo device")
                }
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            oppoAncFromSettings(commandArgs[0] as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            oppoAncFromLevel(commandArgs[0] as? String ?: "")
        }
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int) {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", methodName, *parameterTypes)) {
                Log.d(TAG, "MiuiHeadsetFragment.$methodName before args=${args.describeArgs()} ${fragmentDebug(instance)} isOppo=${isOppoFragment(instance)}")
                if (!isOppoFragment(instance)) return@hookBefore
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val oppoMode = mode(args)
                currentAnc = oppoMode
                sendOppoAnc(oppoMode)
                sendSettingsAncChanged(oppoMode)
                runCatching { callMethod(instance, "updateAncUi", settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.d(TAG, "MiuiHeadsetFragment.$methodName handled oppoMode=$oppoMode")
            }
        }.onFailure { Log.w(TAG, "hook MiuiHeadsetFragment.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        refreshMilinkSpatialAudioOption()
        loadState()
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED -> {
                        refreshMilinkSpatialAudioOption(intent)
                        saveState(context)
                        updateFragments()
                    }
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
                        updateBatteryViews()
                        updateFragments()
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        updateFragments()
                    }
                }
                Log.d(TAG, "state action=${intent?.action} address=$currentAddress anc=$currentAnc miLinkSpatialEnabled=$milinkSpatialAudioOptionEnabled battery=${settingsBatteryString()}")
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
        refreshHandler.postDelayed(refreshRunnable, SETTINGS_REFRESH_INTERVAL_MS)
        Log.d(TAG, "settings periodic refresh started")
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
                .onFailure { Log.w(TAG, "update battery view failed", it) }
        }
    }

    private fun updateBatteryView(view: Any?) {
        val values = settingsBatteryValues()
        callMethod(view, "onBatteryChanged", values[0], values[1], values[2])
        Log.d(TAG, "Battery.onBatteryChanged(int,int,int) forced=${values.joinToString(",")}")
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isOppoFragment(fragment)) {
                injectFragmentStatus(fragment)
            }
        }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            val payload = "${settingsAncMode()}|0100;0101;0102;0103;0200;0201|${settingsBatteryString()}|00"
            Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
            callMethod(fragment, "updateAtUiInfo", payload)
            callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
            val address = device?.address
            if (address != null) {
                val refreshPayload = settingsRefreshPayload()
                Log.d(TAG, "injectFragmentStatus refreshPayload=$refreshPayload address=$address")
                callMethod(fragment, "refreshStatus", address, refreshPayload)
            }
            Log.d(TAG, "fragment status injected anc=$currentAnc battery=${settingsBatteryString()}")
        }.onFailure { Log.w(TAG, "inject fragment status failed", it) }
    }

    private fun isOppoFragment(fragment: Any?): Boolean {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        return isOppoPod(device) || deviceId == FAKE_DEVICE_ID || support?.startsWith(FAKE_DEVICE_ID) == true
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

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }

    private fun List<Any?>.describeArgs(): String {
        return joinToString(prefix = "[", postfix = "]") { arg ->
            when (arg) {
                is BluetoothDevice -> arg.describe()
                else -> arg?.toString() ?: "null"
            }
        }
    }

    private fun fragmentDebug(fragment: Any?): String {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val service = runCatching { getObjectField(fragment, "mService") }.getOrNull()
        val hfp = runCatching { getObjectField(fragment, "mBluetoothHfp") }.getOrNull()
        val cached = runCatching { getObjectField(fragment, "mCachedDevice") }.getOrNull()
        val supportAnc = runCatching { getObjectField(fragment, "mSupportAnc") }.getOrNull()
        val ancCached = runCatching { getObjectField(fragment, "mAncCached") }.getOrNull()
        val pendingAnc = runCatching { getObjectField(fragment, "mPendingAnc") }.getOrNull()
        val ancPendingStatus = runCatching { getObjectField(fragment, "mAncPendingStatus") }.getOrNull()
        return "fragment(device=${device.describe()},deviceId=$deviceId,support=$support,service=$service,hfp=$hfp,cached=$cached,supportAnc=$supportAnc,ancCached=$ancCached,pendingAnc=$pendingAnc,ancPending=$ancPendingStatus)"
    }

    private fun isOppoAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownOppoAddresses
    }

    private fun refreshMilinkSpatialAudioOption(intent: Intent? = null) {
        milinkSpatialAudioOptionEnabled = MilinkSpatialAudioOptionSettings.resolveAndCache(
            context,
            PREFS_NAME,
            prefs,
            ::reloadRemotePrefs,
            intent
        )
    }

    private fun settingsBatteryString(): String {
        return settingsBatteryValues().joinToString(",")
    }

    private fun settingsBatteryValues(): List<Int> {
        loadState()
        return listOf(
            batteryValue(currentBattery.left),
            batteryValue(currentBattery.right),
            batteryValue(currentBattery.case)
        )
    }

    private fun batteryValue(params: PodParams?): Int {
        if (params?.isConnected != true) return 255
        val value = params.battery.coerceIn(0, 100)
        return if (params.isCharging) value or 128 else value
    }

    private fun settingsAncMode(): String {
        loadState()
        return when (currentAnc) {
            2 -> "1"
            3 -> "2"
            else -> "0"
        }
    }

    private fun settingsAncLevel(): String {
        loadState()
        return when (currentAnc) {
            2 -> "0100"
            3 -> "0200"
            else -> "0000"
        }
    }

    private fun settingsRefreshPayload(): String {
        val battery = settingsBatteryString().split(",")
        val left = battery.getOrNull(0).orEmpty()
        val right = battery.getOrNull(1).orEmpty()
        val box = battery.getOrNull(2).orEmpty()
        val values = MutableList(16) { "" }
        values[0] = left
        values[1] = right
        values[2] = box
        values[7] = settingsAncLevel()
        values[8] = "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun oppoAncFromSettings(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun oppoAncFromLevel(level: String): Int {
        return when {
            level.startsWith("01") -> 2
            level.startsWith("02") -> 3
            else -> 1
        }
    }

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

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return runCatching { getParcelableExtra(key, BluetoothDevice::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BluetoothDevice>(key) }.getOrNull()
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putBoolean(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, milinkSpatialAudioOptionEnabled)
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
            left = PodParams(
                prefs.getInt("left_battery", currentBattery.left?.battery ?: 0),
                prefs.getBoolean("left_charging", currentBattery.left?.isCharging == true),
                prefs.getBoolean("left_connected", currentBattery.left?.isConnected == true),
                0
            ),
            right = PodParams(
                prefs.getInt("right_battery", currentBattery.right?.battery ?: 0),
                prefs.getBoolean("right_charging", currentBattery.right?.isCharging == true),
                prefs.getBoolean("right_connected", currentBattery.right?.isConnected == true),
                0
            ),
            case = PodParams(
                prefs.getInt("case_battery", currentBattery.case?.battery ?: 0),
                prefs.getBoolean("case_charging", currentBattery.case?.isCharging == true),
                prefs.getBoolean("case_connected", currentBattery.case?.isConnected == true),
                0
            )
        )
    }

    private fun hasCurrentBattery(): Boolean {
        return currentBattery.left?.isConnected == true ||
            currentBattery.right?.isConnected == true ||
            currentBattery.case?.isConnected == true
    }
}
