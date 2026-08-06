package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.graphics.drawable.Drawable
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.CustomButtonFunction
import moe.chenxy.oppopods.pods.CustomButtonPosition
import moe.chenxy.oppopods.pods.SpatialAudioMode
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.MilinkSpatialAudioOptionSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    private const val TAG = "OppoPods-MiLink"
    private const val FAKE_DEVICE_ID = "01010901"
    private const val PREFS_NAME = "oppopods_milink_state"
    private const val PANEL_REFRESH_THROTTLE_MS = 5_000L
    private const val FIND_RING_IDLE = 0
    private const val FIND_RING_ACTIVE = 103
    private const val FIND_RING_RESULT_SUCCESS = 100
    private const val HEADSET_FIND_RING_CHANGED = 10
    private const val GAME_MODE_TITLE = "游戏模式"
    private const val GAME_MODE_SUBTITLE_ON = "已开启"
    private const val GAME_MODE_SUBTITLE_OFF = "已关闭"
    private const val ADAPTIVE_TITLE = "自适应模式"
    private const val SPATIAL_SOUND_TITLE = "空间音效"
    private const val SPATIAL_SOUND_SUBTITLE_ON = "已开启"
    private const val SPATIAL_SOUND_SUBTITLE_OFF = "已关闭"
    private const val ANC_ADAPTIVE = 4
    private const val FIND_RING_HIDDEN = -1
    private const val MIRING_COMPAT_DEVICE_TYPE = 1
    private const val MIRING_VIEW_ID = "mi_audio_ringing_view"
    private const val MIRING_CARD_VIEW_ID = "mi_audio_ring_card"
    private const val AUDIO_EFFECT_VIEW_ID = "audio_effect_view"
    private const val AUDIO_EFFECT_CARD_VIEW_ID = "audio_effect_card"
    private const val CUSTOM_BUTTON_CLICK_THROTTLE_MS = 300L
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"
    private val knownOppoAddresses = linkedSetOf<String>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var statusReceiver: BroadcastReceiver? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentGameMode = false
    private var currentSpatialAudioMode = SpatialAudioMode.OFF
    private var currentSpatialSound = false
    private var milinkSpatialAudioOptionEnabled = OppoPodsPrefsKey.DEFAULT_MILINK_SPATIAL_AUDIO_OPTION_ENABLED
    private var customButtonFunction = CustomButtonFunction.GAME_MODE
    private var customButtonPosition = CustomButtonPosition.UPPER
    @Volatile
    private var panelDetaching = false
    private var gameModeIcon: Drawable? = null
    private var lastPanelRefreshMs = 0L
    private var lastHeadsetController: Any? = null
    private var lastHeadsetDevice: BluetoothDevice? = null
    private var lastProfileContext: Any? = null
    private var customButtonDetail: WeakReference<View>? = null
    private val lowerCustomButtonViews = mutableListOf<WeakReference<View>>()
    private var lastCustomButtonClickMs = 0L
    // 捕获已 attach 的 CirculateServiceInfo，用于在空间音频开关变化时即时更新
    // serviceProperties.headset_switch_state 并重新触发 setHeadsetId 让面板重读 Bundle
    private var lastCirculateServiceInfo: WeakReference<Any>? = null
    private var lastCirculateHeadsetId: String? = null
    private var lastCirculateHeadsetType: Int = 0

    override fun onHook() {
        Log.d(TAG, "MiLink hook initialized; custom placement resolver=runtime-suffix")
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        hookFindRingControllerCommand()
        hookCustomButtonAudioEffectCommand()
        hookFindRingCommand()
        hookFindRingTitle()
        hookCustomButtonPlacementForTypeOne()
        hookCirculateHeadsetServiceInfo()
        hookHeadSetsDetailDetach()
    }

    override fun onHotReloading() {
        statusReceiver?.let { receiver -> runCatching { context?.unregisterReceiver(receiver) } }
        statusReceiver = null
        receiverRegistered = false
        context = null
        lastHeadsetController = null
        lastHeadsetDevice = null
        lastProfileContext = null
        customButtonDetail = null
        lowerCustomButtonViews.forEach { reference ->
            runCatching { reference.get()?.setOnClickListener(null) }
        }
        lowerCustomButtonViews.clear()
        lastCustomButtonClickMs = 0L
        gameModeIcon = null
        lastCirculateServiceInfo = null
        lastCirculateHeadsetId = null
        lastCirculateHeadsetType = 0
    }

    // 自定义按钮（被劫持的 MiRing 面板控件）某个功能的行为定义。
    // 新增功能：加一个 CustomButtonFunction 取值 + 一个 handler + activeHandler() 里加分支即可，
    // 各 hook（显隐/点击/标题副标题图标/detach 抑制）都通过 activeHandler() 分发，无需改散落的判断。
    private class CustomButtonHandler(
        // 当前是否“开”（决定 SUCCESS 高亮 / find-ring active）
        val isActive: () -> Boolean,
        // 用户点击切换到 enabled 时执行（通常广播一个 action 给 com.android.bluetooth）
        val onToggle: (enabled: Boolean, ctx: Context?) -> Unit,
        // 控件标题
        val title: () -> CharSequence,
        // 副标题（按当前 active 状态；返回 null = 不显示副标题）
        val subtitle: (active: Boolean) -> CharSequence?,
        // 控件图标（返回 null = 保持原生图标）
        val icon: (view: View) -> Drawable?,
    )

    // 游戏模式：点击 → 广播 ACTION_GAME_MODE_SET，状态取自 currentGameMode
    private val gameModeHandler = CustomButtonHandler(
        isActive = { currentGameMode },
        onToggle = { enabled, ctx ->
            currentGameMode = enabled
            sendOppoGameMode(enabled, ctx)
        },
        title = { GAME_MODE_TITLE },
        subtitle = { active -> if (active) GAME_MODE_SUBTITLE_ON else GAME_MODE_SUBTITLE_OFF },
        icon = { view -> loadGameModeIcon(view) },
    )

    // 打开自适应模式：瞬时动作按钮——点击只广播 ACTION_ANC_SELECT(自适应)，
    // isActive 恒为 false 所以控件永远停在 NORMAL（不高亮），也不显示副标题。
    private val adaptiveHandler = CustomButtonHandler(
        isActive = { false },
        onToggle = { enabled, ctx -> if (enabled) sendOppoAnc(ANC_ADAPTIVE, ctx) },
        title = { ADAPTIVE_TITLE },
        subtitle = { null },
        icon = { null },
    )

    // 空间音效：toggle 开关，与游戏模式同结构
    private val spatialSoundHandler = CustomButtonHandler(
        isActive = { currentSpatialSound },
        onToggle = { enabled, ctx ->
            currentSpatialSound = enabled
            sendOppoSpatialSound(enabled, ctx)
        },
        title = { SPATIAL_SOUND_TITLE },
        subtitle = { active -> if (active) SPATIAL_SOUND_SUBTITLE_ON else SPATIAL_SOUND_SUBTITLE_OFF },
        icon = { null },
    )

    // 返回当前自定义按钮功能对应的 handler；NONE → null（控件隐藏）
    private fun activeHandler(): CustomButtonHandler? {
        loadState()
        return when (customButtonFunction) {
            CustomButtonFunction.GAME_MODE -> gameModeHandler
            CustomButtonFunction.ADAPTIVE -> adaptiveHandler
            CustomButtonFunction.SPATIAL_SOUND -> spatialSoundHandler
            CustomButtonFunction.NONE -> null
        }
    }

    private fun hookContextEntry() {
        listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        ).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                    registerStatusReceiver(args[0] as? Context)
                }
            }.onFailure { Log.w(TAG, "hook $className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        val classes = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        )
        classes.forEach { className ->
            hookBluetoothDeviceResult(className, "checkIsMiTWS") { 1 }
            hookBluetoothDeviceResult(className, "getDeviceId") { FAKE_DEVICE_ID }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { 1 }
            hookBluetoothDeviceResult(className, "getAncState") { miLinkAncState() }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookBluetoothDeviceResult(className, "getSpatialMode") { miLinkSpatialMode() }
            hookBluetoothDeviceResult(className, "getWearStatus") { "0,0" }
            hookBluetoothDeviceResult(className, "isLeAudio") { false }
            hookAncCommand(className, "openAnc", 2, 1)
            hookAncCommand(className, "closeAnc", 1, 0)
            hookAncCommand(className, "openTransparent", 3, 2)
            hookSpatialCommand(className, "setSpatialMode")
        }
        classes.forEach { className ->
            hookStringAddressResult(className, "isMiTWS") { true }
            hookStringAddressResult(className, "isSupportAudioSwitch") { miLinkSwitchState() }
            hookStringAddressResult(className, "getRingFindState") { miLinkFindRingActive() }
        }
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceId") { FAKE_DEVICE_ID }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getBatteryLevel", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getFindRingState") { miLinkFindRingState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getAudioSpatialEffectState") { miLinkSpatialMode() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getDeviceId") { FAKE_DEVICE_ID }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getAncState") { miLinkAncState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getFindRingState") { miLinkFindRingState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getMiAudioEffect") { miLinkSpatialMode() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getBatteryLevelCache", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getHeadsetPropertyBlock", reconnectOnRead = true) { batteryPercentForMiLink() }
        hookStringAddressResult("com.miui.headset.runtime.AncBatteryController", "getSwitchState") { miLinkSwitchState() }
        hookAncStateBlock()
        hookSpatialStateBlock()
        hookDeviceSpatialTypeModel()
        hookSpatialCallbacks()
        hookProfileAudioEffectState()
        hookHeadsetInfoNoArg("getDeviceId") { FAKE_DEVICE_ID }
        hookHeadsetInfoNoArg("component3") { FAKE_DEVICE_ID }
        hookHeadsetInfoNoArg("getPowers", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkAncState() }
        hookHeadsetInfoNoArg("component5") { miLinkAncState() }
        hookHeadsetInfoNoArg("getSwitchState") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("getAudioEffectState") { miLinkAudioEffectState() }
        hookHeadsetInfoNoArg("component10") { miLinkAudioEffectState() }
        hookHeadsetInfoNoArg("getFindRingState") { miLinkFindRingState() }
        hookHeadsetInfoNoArg("component11") { miLinkFindRingState() }
    }

    private fun hookBluetoothDeviceResult(
        className: String,
        methodName: String,
        reconnectOnRead: Boolean = false,
        result: () -> Any
    ) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                val old = this.result
                rememberHeadsetController(className, instance, device)
                captureRuntimeContext(instance)
                if (reconnectOnRead) {
                    requestPanelBluetoothStatus("$className.$methodName")
                }
                this.result = result()
                if (className == "com.miui.headset.runtime.AncBatteryController" && methodName == "getHeadsetPropertyBlock") {
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
                Log.d(TAG, "$className.$methodName forced old=$old new=${this.result} address=${device.address}")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(BluetoothDevice) skipped", it) }
    }

    private fun hookStringAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookAfter
                if (!isOppoAddress(address)) return@hookAfter
                val old = this.result
                this.result = result()
                Log.d(TAG, "$className.$methodName forced old=$old new=${this.result} address=$address")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, oppoAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController(className, instance, device)
                captureRuntimeContext(instance)
                currentAnc = oppoAnc
                sendOppoAnc(oppoAnc)
                this.result = result
                Log.d(TAG, "$className.$methodName handled address=${device.address} oppoAnc=$oppoAnc result=$result")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookSpatialCommand(className: String, methodName: String) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController(className, instance, device)
                captureRuntimeContext(instance)
                if (!spatialAudioPanelEnabled()) {
                    this.result = 0
                    Log.d(TAG, "$className.$methodName ignored: MiLink spatial option disabled address=${device.address}")
                    return@hookBefore
                }
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val mode = oppoSpatialFromMiLink(miLinkMode)
                updateSpatialAudioMode(mode)
                sendOppoSpatialAudio(mode)
                sendSpatialChanged(mode)
                notifySpatialUiChanged(instance, device, mode)
                this.result = 1
                Log.d(TAG, "$className.$methodName handled address=${device.address} miLinkMode=$miLinkMode oppoMode=$mode")
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName spatial command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController("com.miui.headset.runtime.AncBatteryController", instance, device)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val oppoAnc = oppoAncFromMiLink(miLinkMode)
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                currentAnc = oppoAnc
                sendOppoAnc(oppoAnc, instanceContext)
                sendMiLinkAncChanged(oppoAnc, instanceContext)
                notifyHeadsetPropertyChanged(instance, device, 8)
                notifyHeadsetPropertyChanged(instance, device, 4)
                this.result = miLinkAncState()
                Log.d(TAG, "AncBatteryController.setAncStateBlock handled address=${device.address} miLinkMode=$miLinkMode oppoAnc=$oppoAnc result=${this.result} context=${instanceContext ?: context}")
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setAncStateBlock skipped", it) }
    }

    private fun hookSpatialStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setMiAudioEffect", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController("com.miui.headset.runtime.AncBatteryController", instance, device)
                captureRuntimeContext(instance)
                if (!spatialAudioPanelEnabled()) {
                    this.result = null
                    Log.d(TAG, "AncBatteryController.setMiAudioEffect ignored: MiLink spatial option disabled address=${device.address}")
                    return@hookBefore
                }
                val mode = oppoSpatialFromMiLink(args[1] as? Int ?: return@hookBefore)
                updateSpatialAudioMode(mode)
                sendOppoSpatialAudio(mode)
                sendSpatialChanged(mode)
                notifySpatialUiChanged(instance, device, mode)
                this.result = null
                Log.d(TAG, "AncBatteryController.setMiAudioEffect handled address=${device.address} oppoMode=$mode")
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setMiAudioEffect skipped", it) }

        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setHeadTracking", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController("com.miui.headset.runtime.AncBatteryController", instance, device)
                captureRuntimeContext(instance)
                if (!spatialAudioPanelEnabled()) {
                    this.result = 201
                    Log.d(TAG, "AncBatteryController.setHeadTracking ignored: MiLink spatial option disabled address=${device.address}")
                    return@hookBefore
                }
                updateSpatialAudioMode(SpatialAudioMode.HEAD_TRACKING)
                sendOppoSpatialAudio(currentSpatialAudioMode)
                sendSpatialChanged(currentSpatialAudioMode)
                notifySpatialUiChanged(instance, device, currentSpatialAudioMode)
                this.result = FIND_RING_RESULT_SUCCESS
                Log.d(TAG, "AncBatteryController.setHeadTracking handled address=${device.address}")
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setHeadTracking skipped", it) }
    }

    private fun hookDeviceSpatialTypeModel() {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.runtime.AncBatteryModel", "getDeviceSpatialType", 0)) {
                if (!isTargetAncBatteryModel(instance)) return@hookAfter
                this.result = miLinkDeviceSpatialType()
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryModel.getDeviceSpatialType skipped", it) }

        runCatching {
            hookAfter(findMethod("com.miui.headset.runtime.AncBatteryModel", "setDeviceSpatialType", Int::class.javaPrimitiveType!!)) {
                if (!isTargetAncBatteryModel(instance)) return@hookAfter
                setObjectField(instance, "deviceSpatialType", miLinkDeviceSpatialType())
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryModel.setDeviceSpatialType skipped", it) }
    }

    private fun hookSpatialCallbacks() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1", "onDeviceSpatialType", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                notifySpatialUiChanged(instance, device, currentSpatialAudioMode)
                this.result = null
            }
        }.onFailure { Log.w(TAG, "hook mmaCallback.onDeviceSpatialType skipped", it) }

        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1", "onReportSpatialState", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                notifySpatialUiChanged(instance, device, currentSpatialAudioMode)
                this.result = null
            }
        }.onFailure { Log.w(TAG, "hook mmaCallback.onReportSpatialState skipped", it) }
    }

    private fun hookProfileAudioEffectState() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.ProfileContext", "setAudioEffectState", BluetoothDevice::class.java, String::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                rememberHeadsetController("com.miui.headset.runtime.ProfileContext", instance, device)
                captureRuntimeContext(instance)
                if (!spatialAudioPanelEnabled()) {
                    this.result = null
                    Log.d(TAG, "ProfileContext.setAudioEffectState ignored: MiLink spatial option disabled address=${device.address}")
                    return@hookBefore
                }
                val mode = (args[2] as? Int ?: return@hookBefore)
                    .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
                updateSpatialAudioMode(mode)
                sendOppoSpatialAudio(mode)
                sendSpatialChanged(mode)
                notifySpatialUiChanged(instance, device, mode)
                this.result = null
                Log.d(TAG, "ProfileContext.setAudioEffectState handled address=${device.address} mode=$mode")
            }
        }.onFailure { Log.w(TAG, "hook ProfileContext.setAudioEffectState skipped", it) }
    }

    private fun hookFindRingCommand() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setFindRing", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                val handler = activeHandler() ?: return@hookBefore
                if (customButtonPosition != CustomButtonPosition.UPPER) return@hookBefore
                val state = args[1] as? Int ?: return@hookBefore
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                rememberHeadsetController("com.miui.headset.runtime.AncBatteryController", instance, device)

                if (state == FIND_RING_IDLE && panelDetaching) {
                    this.result = FIND_RING_RESULT_SUCCESS
                    Log.d(TAG, "AncBatteryController.setFindRing lifecycle stop ignored address=${device.address}")
                    return@hookBefore
                }

                val enabled = state != FIND_RING_IDLE
                if (enabled == handler.isActive()) {
                    notifyFindRingChanged(instance, device)
                    this.result = FIND_RING_RESULT_SUCCESS
                    Log.d(TAG, "AncBatteryController.setFindRing duplicate ignored address=${device.address} state=$state active=${handler.isActive()}")
                    return@hookBefore
                }

                handler.onToggle(enabled, instanceContext)
                saveState(instanceContext)
                notifyFindRingChanged(instance, device)
                this.result = FIND_RING_RESULT_SUCCESS
                Log.d(TAG, "AncBatteryController.setFindRing handled address=${device.address} state=$state active=$enabled")
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setFindRing skipped", it) }
    }

    private fun hookFindRingControllerCommand() {
        runCatching {
            val headsetInfoClass = findClass("com.miui.circulate.api.service.CirculateServiceInfo")
            val detailClass = findHeadSetsDetailClass()
            val controllerClass = detailClass
                ?.let { findHeadsetControllerClass(it, headsetInfoClass) }
                ?: findClass("com.miui.circulate.api.protocol.headset.C4652c0")
            val methods = controllerCommandMethods(controllerClass, headsetInfoClass)
            if (methods.isEmpty()) {
                Log.w(TAG, "hook headset controller setFindRing skipped: command method not found in ${controllerClass.name}")
                return
            }

            methods.forEach { method ->
                hookBefore(method) {
                    if (customButtonPosition != CustomButtonPosition.UPPER) return@hookBefore
                    val state = args[1] as? Int ?: return@hookBefore
                    if (state == FIND_RING_IDLE && activeHandler() != null && panelDetaching) {
                        this.result = CompletableFuture.completedFuture(FIND_RING_RESULT_SUCCESS)
                        Log.d(TAG, "HeadsetServiceController.${method.name} detach stop ignored")
                    }
                }
                Log.d(TAG, "hooked headset controller command ${controllerClass.name}.${method.name}")
            }
        }.onFailure { Log.w(TAG, "hook headset controller command skipped", it) }
    }

    /**
     * The lower placement reuses the Apple-only single-switch audio-effect card. Its native
     * click path calls HeadsetServiceController.setAudioEffect(CirculateServiceInfo, 0/1).
     * Keep this as a command-level fallback in addition to the View click listener below.
     */
    private fun hookCustomButtonAudioEffectCommand() {
        runCatching {
            val headsetInfoClass = findClass("com.miui.circulate.api.service.CirculateServiceInfo")
            val detailClass = findHeadSetsDetailClass()
            val controllerClass = detailClass
                ?.let { findHeadsetControllerClass(it, headsetInfoClass) }
                ?: findClass("com.miui.circulate.api.protocol.headset.C4652c0")
            val methods = controllerCommandMethods(controllerClass, headsetInfoClass)
                .filter { method ->
                    method.name.equals("setAudioEffect", ignoreCase = true) ||
                        // MiLink 18 exports the Kotlin source method c0() as m19898c0().
                        matchesMiLinkMethodName(method.name, "c0")
                }
            if (methods.isEmpty()) {
                Log.w(TAG, "hook headset controller setAudioEffect skipped: named command not found in ${controllerClass.name}")
                return
            }

            methods.forEach { method ->
                hookBefore(method) {
                    if (customButtonPosition != CustomButtonPosition.LOWER) return@hookBefore
                    val serviceInfo = args[0] ?: return@hookBefore
                    if (!isTargetCirculateServiceInfo(serviceInfo)) return@hookBefore
                    val handler = activeHandler() ?: return@hookBefore
                    val state = (args[1] as? Number)?.toInt() ?: return@hookBefore
                    captureRuntimeContext(instance)

                    if (state == FIND_RING_IDLE && panelDetaching) {
                        this.result = CompletableFuture.completedFuture(FIND_RING_RESULT_SUCCESS)
                        Log.d(TAG, "HeadsetServiceController.setAudioEffect detach stop ignored")
                        return@hookBefore
                    }

                    val enabled = state != FIND_RING_IDLE
                    if (enabled == handler.isActive()) {
                        refreshCustomButtonPlacement()
                        this.result = CompletableFuture.completedFuture(FIND_RING_RESULT_SUCCESS)
                        Log.d(TAG, "HeadsetServiceController.setAudioEffect duplicate ignored state=$state active=$enabled")
                        return@hookBefore
                    }

                    handler.onToggle(enabled, context)
                    saveState(context)
                    refreshCustomButtonPlacement()
                    this.result = CompletableFuture.completedFuture(FIND_RING_RESULT_SUCCESS)
                    Log.d(TAG, "HeadsetServiceController.setAudioEffect handled state=$state active=$enabled")
                }
                Log.d(TAG, "hooked headset controller audio effect command ${controllerClass.name}.${method.name}")
            }
        }.onFailure { Log.w(TAG, "hook headset controller setAudioEffect skipped", it) }
    }

    private fun hookFindRingTitle() {
        val synergyViewClass = listOf(
            "com.miui.circulate.world.sticker.ui.SynergyView",
            "com.miui.circulate.world.sticker.p067ui.SynergyView"
        ).firstNotNullOfOrNull { className ->
            runCatching { findClass(className) }.getOrNull()
        } ?: run {
            Log.w(TAG, "hook SynergyView.setTitle skipped: class not found")
            return
        }

        runCatching {
            hookBefore(synergyViewClass.getDeclaredMethod("setTitle", Int::class.javaPrimitiveType!!).apply { isAccessible = true }) {
                val view = instance as? View ?: return@hookBefore
                val resId = args[0] as? Int ?: return@hookBefore
                val handler = activeHandler() ?: return@hookBefore
                val active = customButtonTitleState(view, resId) ?: return@hookBefore
                if (!setSynergyTitle(view, handler.title())) return@hookBefore
                applySubtitle(view, resId, handler.subtitle(active))
                applyIcon(view, resId, handler.icon(view))
                this.result = null
                Log.d(TAG, "SynergyView.setTitle replaced view=${resourceEntryName(view, view.id)} res=${resourceEntryName(view, resId)} title=${handler.title()} active=$active")
            }
        }.onFailure { Log.w(TAG, "hook SynergyView.setTitle skipped", it) }
    }

    /**
     * Keep the OPPO device type intact and alter only the already-created type-1 detail view.
     * The lower card is normally reserved for Apple devices, but its view is always inflated on
     * the current MiLink panel and can be driven without changing the device classification.
     */
    private fun hookCustomButtonPlacementForTypeOne() {
        val detailClass = findHeadSetsDetailClass() ?: run {
            Log.w(TAG, "hook custom button placement skipped: HeadSetsDetail not found")
            return
        }
        val headsetDeviceInfoClass = runCatching {
            findClass("com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo")
        }.getOrElse {
            Log.w(TAG, "hook custom button placement skipped: HeadsetDeviceInfo not found", it)
            return
        }

        // The setup method creates the sections after receiving HeadsetDeviceInfo.
        detailClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 4 &&
                    method.parameterTypes[2] == headsetDeviceInfoClass
            }
            ?.apply { isAccessible = true }
            ?.let { setupMethod ->
                runCatching {
                    hookAfter(setupMethod) {
                        val detail = instance as? View ?: return@hookAfter
                        val headsetInfo = args.getOrNull(2)
                        val miRingSection = findDetailSection(detail, MIRING_VIEW_ID)
                        val audioEffectSection = findDetailSection(detail, AUDIO_EFFECT_VIEW_ID)
                        val audioEffectStateMethod = audioEffectSection?.let {
                            findSectionMethod(it, listOf("o"), Int::class.javaPrimitiveType!!)?.name
                        }
                        Log.d(
                            TAG,
                            "HeadSetsDetail setup class=${detail.javaClass.name} type=${headsetDeviceType(headsetInfo)} " +
                                "isOppoTypeOne=${isTypeOneOppoHeadset(headsetInfo)} " +
                                "miRing=${miRingSection?.javaClass?.name} " +
                                "audioEffect=${audioEffectSection?.javaClass?.name} audioEffectState=$audioEffectStateMethod"
                        )
                        applyCustomButtonPlacement(detail, headsetInfo)
                    }
                    Log.d(TAG, "hooked ${detailClass.name}.${setupMethod.name} for custom button placement")
                }.onFailure { Log.w(TAG, "hook custom button placement setup skipped", it) }
            }
            ?: Log.w(TAG, "hook custom button placement setup skipped: setup method not found")

        // Reapply after attachment so a later layout pass cannot restore the native visibility.
        runCatching {
            val attachedMethod = detailClass.getDeclaredMethod("onAttachedToWindow").apply { isAccessible = true }
            hookAfter(attachedMethod) {
                val detail = instance as? View ?: return@hookAfter
                applyCustomButtonPlacement(detail)
            }
            Log.d(TAG, "hooked ${detailClass.name}.onAttachedToWindow for custom button placement")
        }.onFailure { Log.w(TAG, "hook custom button placement attach skipped", it) }

        // The service observer refreshes all section states through HeadSetsDetail.E() after the
        // initial setup. Reapply at the end of that pass so it cannot restore MiRing after the
        // lower Apple-style card was selected.
        detailClass.declaredMethods
            .firstOrNull { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.isEmpty() &&
                    matchesMiLinkMethodName(method.name, "E")
            }
            ?.apply { isAccessible = true }
            ?.let { refreshMethod ->
                runCatching {
                    hookAfter(refreshMethod) {
                        val detail = instance as? View ?: return@hookAfter
                        detail.post { applyCustomButtonPlacement(detail) }
                    }
                    Log.d(TAG, "hooked ${detailClass.name}.${refreshMethod.name} state refresh for custom button placement")
                }.onFailure { Log.w(TAG, "hook custom button placement state refresh skipped", it) }
            }
            ?: Log.w(TAG, "hook custom button placement state refresh skipped: method not found")
    }

    private fun applyCustomButtonPlacement(detail: View, deviceInfo: Any? = findHeadsetDetailDeviceInfo(detail)) {
        if (!isTypeOneOppoHeadset(deviceInfo)) return

        customButtonDetail = WeakReference(detail)
        val handler = activeHandler()
        val miRingSection = findDetailSection(detail, MIRING_VIEW_ID)
        val miRingCard = findViewByEntryName(detail, MIRING_CARD_VIEW_ID)
        val miRingView = findViewByEntryName(detail, MIRING_VIEW_ID)
        val audioEffectSection = findDetailSection(detail, AUDIO_EFFECT_VIEW_ID)
        val audioEffectCard = findViewByEntryName(detail, AUDIO_EFFECT_CARD_VIEW_ID)
        val audioEffectView = findViewByEntryName(detail, AUDIO_EFFECT_VIEW_ID)

        val showUpper = handler != null && customButtonPosition == CustomButtonPosition.UPPER
        val showLower = handler != null && customButtonPosition == CustomButtonPosition.LOWER
        val upperVisibilityApplied = applySectionVisibility(
            miRingSection,
            miRingCard,
            miRingView,
            showUpper,
            listOf("h")
        )
        // C6444d1.h() only changes the child views. HeadSetsDetail uses this flag independently
        // while calculating its height, so leaving it true lets the native "Find earbuds" card
        // reappear on a later layout pass.
        val miRingFlagApplied = setDetailVisibilityFlag(
            detail,
            setterName = "setMiRingVisible",
            fieldName = "miRingVisible",
            visible = showUpper
        )
        val lowerVisibilityApplied = applySectionVisibility(
            audioEffectSection,
            audioEffectCard,
            audioEffectView,
            showLower,
            listOf("m")
        )
        // C6486x.o() is the native state path: in addition to showing the card, it sets
        // HeadSetsDetail.audioEffectVisible and posts a height update. Use -1 to remove the
        // lower slot completely rather than merely hiding its child view.
        val lowerState = when {
            !showLower -> FIND_RING_HIDDEN
            handler.isActive() -> 1
            else -> 0
        }
        val lowerStateApplied = applySectionStateValue(
            audioEffectSection,
            audioEffectView,
            lowerState,
            listOf("o")
        )
        val audioEffectFlagApplied = setDetailVisibilityFlag(
            detail,
            setterName = "setAudioEffectVisible",
            fieldName = "audioEffectVisible",
            visible = showLower
        )

        if (showUpper) {
            applySectionState(miRingSection, miRingView, handler.isActive(), listOf("j"))
            applyCustomButtonAppearance(miRingView, handler)
        }
        if (showLower) {
            applyCustomButtonAppearance(audioEffectView, handler)
            installLowerButtonClick(detail, audioEffectView, audioEffectCard)
        }
        requestDetailHeightRefresh(detail)
        Log.d(
            TAG,
            "custom button placement applied position=$customButtonPosition active=${handler?.isActive()} " +
                "upper=$showUpper lower=$showLower upperSection=$upperVisibilityApplied " +
                "lowerSection=$lowerVisibilityApplied lowerState=$lowerStateApplied " +
                "miRingFlag=$miRingFlagApplied audioEffectFlag=$audioEffectFlagApplied"
        )
    }

    private fun refreshCustomButtonPlacement() {
        val detail = customButtonDetail?.get() ?: return
        detail.post { applyCustomButtonPlacement(detail) }
    }

    private fun findHeadsetDetailDeviceInfo(detail: Any): Any? {
        runCatching { callMethod(detail, "getHeadsetDeviceInfo") }.getOrNull()?.let { return it }

        val headsetDeviceInfoClass = runCatching {
            findClass("com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo")
        }.getOrNull() ?: return null
        var detailClass: Class<*>? = detail.javaClass
        while (detailClass != null) {
            for (field in detailClass.declaredFields) {
                if (!headsetDeviceInfoClass.isAssignableFrom(field.type)) continue
                val value = runCatching { field.apply { isAccessible = true }.get(detail) }.getOrNull()
                if (value != null) return value
            }
            detailClass = detailClass.superclass
        }
        return null
    }

    private fun isTypeOneOppoHeadset(deviceInfo: Any?): Boolean {
        if (deviceInfo == null) return false
        val type = headsetDeviceType(deviceInfo)
        if (type != MIRING_COMPAT_DEVICE_TYPE) return false

        val addresses = readStringMembers(deviceInfo, listOf("mac", "deviceId", "address", "bluetoothAddress"))
        if (addresses.any(::isOppoAddress)) return true
        if (addresses.any { address -> address.equals(lastHeadsetDevice?.address, ignoreCase = true) }) return true

        val name = readStringMembers(deviceInfo, listOf("name", "deviceName")).firstOrNull().orEmpty()
        if (!name.contains("oppo", ignoreCase = true)) return false
        addresses.firstOrNull()?.let { address ->
            knownOppoAddresses.add(address.uppercase())
            currentAddress = address
        }
        return true
    }

    private fun headsetDeviceType(deviceInfo: Any?): Int? {
        return (runCatching { getObjectField(deviceInfo, "type") as? Number }.getOrNull()
            ?: runCatching { callMethod(deviceInfo, "getType") as? Number }.getOrNull())?.toInt()
    }

    private fun findDetailSection(detail: Any, viewId: String): Any? {
        var viewFallback: Any? = null
        var detailClass: Class<*>? = detail.javaClass
        while (detailClass != null) {
            for (field in detailClass.declaredFields) {
                val candidate = runCatching { field.apply { isAccessible = true }.get(detail) }.getOrNull() ?: continue
                if (findSectionViewByEntryName(candidate, viewId) == null) continue
                // A root View can contain every card. Prefer the owning section object so the
                // known native h/j or m/o method remains the primary rendering path.
                if (candidate is View) {
                    if (viewFallback == null) viewFallback = candidate
                } else {
                    return candidate
                }
            }
            detailClass = detailClass.superclass
        }
        return viewFallback
    }

    private fun findSectionViewByEntryName(section: Any, viewId: String): View? {
        (section as? View)?.let { view ->
            findViewByEntryName(view, viewId)?.let { return it }
        }
        var sectionClass: Class<*>? = section.javaClass
        while (sectionClass != null) {
            for (field in sectionClass.declaredFields) {
                if (!View::class.java.isAssignableFrom(field.type)) continue
                val view = runCatching { field.apply { isAccessible = true }.get(section) as? View }.getOrNull() ?: continue
                findViewByEntryName(view, viewId)?.let { return it }
            }
            sectionClass = sectionClass.superclass
        }
        return null
    }

    private fun findViewByEntryName(root: View?, viewId: String): View? {
        root ?: return null
        if (resourceEntryName(root, root.id) == viewId) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findViewByEntryName(group.getChildAt(index), viewId)?.let { return it }
        }
        return null
    }

    private fun applySectionVisibility(
        section: Any?,
        card: View?,
        control: View?,
        visible: Boolean,
        preferredMethodNames: List<String>
    ): Boolean {
        val applied = section?.let { invokeSectionBoolean(it, preferredMethodNames, visible) } == true
        if (applied) return true

        val visibility = if (visible) View.VISIBLE else View.GONE
        card?.visibility = visibility
        control?.visibility = visibility
        return false
    }

    private fun applySectionState(
        section: Any?,
        control: View?,
        active: Boolean,
        preferredMethodNames: List<String>
    ) {
        applySectionStateValue(section, control, if (active) 1 else 0, preferredMethodNames)
    }

    private fun applySectionStateValue(
        section: Any?,
        control: View?,
        state: Int,
        preferredMethodNames: List<String>
    ): Boolean {
        val applied = section?.let { invokeSectionInt(it, preferredMethodNames, state) } == true
        if (!applied && state != FIND_RING_HIDDEN) setSynergyState(control, state == 1)
        return applied
    }

    private fun invokeSectionBoolean(section: Any, preferredMethodNames: List<String>, value: Boolean): Boolean {
        val method = findSectionMethod(section, preferredMethodNames, Boolean::class.javaPrimitiveType!!) ?: return false
        return runCatching {
            method.invoke(section, value)
            true
        }.onFailure { Log.w(TAG, "section visibility method ${method.name} failed", it) }.getOrDefault(false)
    }

    private fun invokeSectionInt(section: Any, preferredMethodNames: List<String>, value: Int): Boolean {
        val method = findSectionMethod(section, preferredMethodNames, Int::class.javaPrimitiveType!!) ?: return false
        return runCatching {
            method.invoke(section, value)
            true
        }.onFailure { Log.w(TAG, "section state method ${method.name} failed", it) }.getOrDefault(false)
    }

    private fun findSectionMethod(section: Any, names: List<String>, parameterType: Class<*>): java.lang.reflect.Method? {
        names.forEach { name ->
            var sectionClass: Class<*>? = section.javaClass
            while (sectionClass != null) {
                sectionClass.declaredMethods.firstOrNull { method ->
                    matchesMiLinkMethodName(method.name, name) &&
                        method.parameterTypes.contentEquals(arrayOf(parameterType))
                }?.let { method ->
                    method.isAccessible = true
                    return method
                }
                sectionClass = sectionClass.superclass
            }
        }
        return null
    }

    /**
     * JADX displays Kotlin source names such as o(), while the APK's runtime methods are often
     * renamed to m25356o(). Keep the semantic suffix and accept both representations so an
     * internal method-number change does not silently disable the native card update path.
     */
    private fun matchesMiLinkMethodName(runtimeName: String, sourceName: String): Boolean {
        if (runtimeName == sourceName) return true
        val prefix = runtimeName.removeSuffix(sourceName)
        return prefix.startsWith("m") && prefix.length > 1 && prefix.substring(1).all(Char::isDigit)
    }

    private fun setDetailVisibilityFlag(
        detail: Any,
        setterName: String,
        fieldName: String,
        visible: Boolean
    ): Boolean {
        val setterApplied = runCatching {
            val setter = findDetailMethod(detail, listOf(setterName), Boolean::class.javaPrimitiveType!!)
                ?: return@runCatching false
            setter.invoke(detail, visible)
            true
        }.onFailure { Log.w(TAG, "detail visibility setter $setterName failed", it) }.getOrDefault(false)
        if (setterApplied) return true

        return runCatching {
            setObjectField(detail, fieldName, visible)
            true
        }.onFailure { Log.w(TAG, "detail visibility field $fieldName failed", it) }.getOrDefault(false)
    }

    private fun requestDetailHeightRefresh(detail: View) {
        detail.post {
            val refreshed = runCatching {
                val method = findDetailMethod(detail, listOf("A")) ?: return@runCatching false
                method.invoke(detail)
                true
            }.onFailure { Log.w(TAG, "HeadSetsDetail height refresh failed", it) }.getOrDefault(false)
            if (!refreshed) {
                detail.requestLayout()
                (detail.parent as? View)?.requestLayout()
            }
        }
    }

    private fun findDetailMethod(
        detail: Any,
        names: List<String>,
        parameterType: Class<*>? = null
    ): java.lang.reflect.Method? {
        names.forEach { name ->
            var detailClass: Class<*>? = detail.javaClass
            while (detailClass != null) {
                detailClass.declaredMethods.firstOrNull { method ->
                    matchesMiLinkMethodName(method.name, name) &&
                        if (parameterType == null) method.parameterTypes.isEmpty()
                        else method.parameterTypes.contentEquals(arrayOf(parameterType))
                }?.let { method ->
                    method.isAccessible = true
                    return method
                }
                detailClass = detailClass.superclass
            }
        }
        return null
    }

    private fun setSynergyState(view: View?, active: Boolean): Boolean {
        val target = view ?: return false
        var viewClass: Class<*>? = target.javaClass
        while (viewClass != null) {
            val method = viewClass.declaredMethods.firstOrNull { candidate ->
                candidate.name == "setState" && candidate.parameterTypes.size == 1
            }
            if (method != null) {
                val parameterType = method.parameterTypes[0]
                val state = when {
                    parameterType == Int::class.javaPrimitiveType || parameterType == Int::class.java -> {
                        if (active) 1 else 0
                    }
                    parameterType.isEnum -> {
                        val desired = if (active) "SUCCESS" else "NORMAL"
                        parameterType.enumConstants?.firstOrNull { constant ->
                            (constant as? Enum<*>)?.name == desired
                        }
                    }
                    else -> null
                } ?: return false
                return runCatching {
                    method.isAccessible = true
                    method.invoke(target, state)
                    true
                }.onFailure { Log.w(TAG, "SynergyView.setState fallback failed", it) }.getOrDefault(false)
            }
            viewClass = viewClass.superclass
        }
        return false
    }

    private fun applyCustomButtonAppearance(view: View?, handler: CustomButtonHandler) {
        val target = view ?: return
        val active = handler.isActive()
        setSynergyTitle(target, handler.title())
        applySubtitle(target, target.id, handler.subtitle(active))
        applyIcon(target, target.id, handler.icon(target))
    }

    private fun installLowerButtonClick(detail: View, control: View?, card: View?) {
        listOfNotNull(control, card).distinct().forEach { target ->
            lowerCustomButtonViews.removeAll { reference ->
                reference.get() == null || reference.get() === target
            }
            lowerCustomButtonViews += WeakReference(target)
            target.isClickable = true
            target.setOnClickListener { clickedView ->
                if (customButtonPosition != CustomButtonPosition.LOWER) return@setOnClickListener
                val handler = activeHandler() ?: return@setOnClickListener
                if (!consumeCustomButtonClick()) return@setOnClickListener

                val clickedContext = clickedView.context
                context = clickedContext.applicationContext ?: clickedContext
                val enabled = !handler.isActive()
                handler.onToggle(enabled, clickedContext)
                saveState(clickedContext)
                applyCustomButtonPlacement(detail)
                notifyFindRingChanged()
                Log.d(TAG, "lower custom button clicked enabled=$enabled function=$customButtonFunction")
            }
        }
    }

    private fun consumeCustomButtonClick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCustomButtonClickMs < CUSTOM_BUTTON_CLICK_THROTTLE_MS) return false
        lastCustomButtonClickMs = now
        return true
    }

    private fun customButtonTitleState(view: View, resId: Int): Boolean? {
        return when (resourceEntryName(view, view.id)) {
            MIRING_VIEW_ID -> {
                if (customButtonPosition != CustomButtonPosition.UPPER) return null
                when (resourceEntryName(view, resId)) {
                    "circulate_headset_control_audio_find_earphone" -> false
                    "circulate_headset_control_audio_stop_find_earphone" -> true
                    else -> null
                }
            }
            AUDIO_EFFECT_VIEW_ID -> {
                if (customButtonPosition != CustomButtonPosition.LOWER) return null
                when (resourceEntryName(view, resId)) {
                    "circulate_headset_control_audio_effect_spatial" -> false
                    "circulate_headset_control_audio_effect_off" -> true
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun isTargetCirculateServiceInfo(info: Any): Boolean {
        val values = readStringMembers(info, listOf("deviceId", "mac", "address", "headsetId"))
        return values.any(::isOppoAddress) || values.any { it == FAKE_DEVICE_ID }
    }

    private fun readStringMembers(target: Any?, names: List<String>): List<String> {
        target ?: return emptyList()
        return names.flatMap { name ->
            val getterName = "get${name.substring(0, 1).uppercase()}${name.substring(1)}"
            listOfNotNull(
                runCatching { getObjectField(target, name) as? String }.getOrNull(),
                runCatching { callMethod(target, getterName) as? String }.getOrNull()
            )
        }.distinct()
    }

    private fun hookCirculateHeadsetServiceInfo() {
        runCatching {
            hookAfter(findMethod("com.miui.circulate.api.service.CirculateServiceInfo", "setHeadsetId", String::class.java, Int::class.javaPrimitiveType!!)) {
                val headsetId = args[0] as? String ?: return@hookAfter
                val address = runCatching { getObjectField(instance, "deviceId") as? String }.getOrNull()
                if (address != null && !isOppoAddress(address) && headsetId != FAKE_DEVICE_ID) return@hookAfter
                if (address == null && headsetId != FAKE_DEVICE_ID) return@hookAfter
                lastCirculateServiceInfo = WeakReference(instance)
                lastCirculateHeadsetId = headsetId
                lastCirculateHeadsetType = args[1] as? Int ?: lastCirculateHeadsetType
                if (spatialAudioPanelEnabled()) return@hookAfter
                applySpatialSwitchState(instance)
                Log.d(TAG, "CirculateServiceInfo.setHeadsetId disabled spatial switch address=$address headsetId=$headsetId")
            }
        }.onFailure { Log.w(TAG, "hook CirculateServiceInfo.setHeadsetId skipped", it) }
    }

    // 把 serviceProperties.headset_switch_state 按 spatialAudioPanelEnabled() 写为 1/0，
    // 仅在 OPPO 设备的 CirculateServiceInfo 上生效。供 setHeadsetId 钩子与运行时切换复用。
    private fun applySpatialSwitchState(info: Any?) {
        val serviceProperties = runCatching { getObjectField(info, "serviceProperties") }.getOrNull() ?: return
        val bundle = runCatching { callMethod(serviceProperties, "getAll") as? Bundle }.getOrNull() ?: return
        val newState = if (spatialAudioPanelEnabled()) 1 else 0
        if (bundle.getInt("headset_switch_state", -1) == newState) return
        bundle.putInt("headset_switch_state", newState)
        Log.d(TAG, "applySpatialSwitchState newState=$newState")
    }

    // 切换配置后立即更新已 attach 面板的 headset_switch_state 并重触发 setHeadsetId，
    // 让面板重新读取 Bundle，避免空间音频开关残留显示。
    private fun refreshSpatialSwitchVisibility() {
        val info = lastCirculateServiceInfo?.get() ?: return
        val headsetId = lastCirculateHeadsetId ?: return
        applySpatialSwitchState(info)
        runCatching { callMethod(info, "setHeadsetId", headsetId, lastCirculateHeadsetType) }
            .onFailure { Log.w(TAG, "refreshSpatialSwitchVisibility: re-trigger setHeadsetId failed", it) }
        Log.d(TAG, "refreshSpatialSwitchVisibility headsetId=$headsetId enabled=$milinkSpatialAudioOptionEnabled")
    }

    private fun hookHeadsetInfoNoArg(
        methodName: String,
        reconnectOnRead: Boolean = false,
        result: () -> Any
    ) {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.api.HeadsetInfo", methodName, 0)) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                val old = this.result
                if (reconnectOnRead) {
                    requestPanelBluetoothStatus("HeadsetInfo.$methodName")
                }
                this.result = result()
                Log.d(TAG, "HeadsetInfo.$methodName forced old=$old new=${this.result}")
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfo.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        refreshMilinkSpatialAudioOption()
        refreshCustomButtonFunction()
        refreshCustomButtonPosition()
        loadState()
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED)
            addAction(OppoPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED)
            addAction(OppoPodsAction.ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED)
            addAction(OppoPodsAction.ACTION_CUSTOM_BUTTON_POSITION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED -> {
                        refreshMilinkSpatialAudioOption(intent)
                        saveState(context)
                        refreshSpatialSwitchVisibility()
                        notifySpatialUiChanged()
                    }
                    OppoPodsAction.ACTION_CUSTOM_BUTTON_FUNCTION_CHANGED -> {
                        refreshCustomButtonFunction(intent)
                        saveState(context)
                        // 立即刷新面板中当前选择位置的自定义控件。
                        notifyFindRingChanged()
                    }
                    OppoPodsAction.ACTION_CUSTOM_BUTTON_POSITION_CHANGED -> {
                        refreshCustomButtonPosition(intent)
                        saveState(context)
                        notifyFindRingChanged()
                    }
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.batteryStatusCompat() ?: currentBattery
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentGameMode = intent.getBooleanExtra("enabled", currentGameMode)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        notifyFindRingChanged()
                    }
                    OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentSpatialAudioMode = intent.getIntExtra("mode", currentSpatialAudioMode)
                            .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        notifySpatialUiChanged()
                    }
                    OppoPodsAction.ACTION_PODS_SPATIAL_SOUND_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentSpatialSound = intent.getBooleanExtra("enabled", currentSpatialSound)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        notifyFindRingChanged()
                    }
                }
                Log.d(TAG, "state action=${intent?.action} address=$currentAddress name=$currentName anc=$currentAnc gameMode=$currentGameMode spatial=$currentSpatialAudioMode spatialSound=$currentSpatialSound miLinkSpatialEnabled=$milinkSpatialAudioOptionEnabled rawBattery=${currentBattery.debugString()} miLinkBattery=${miLinkBatteryLevels()}")
            }
        }
        context?.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiver = receiver
        receiverRegistered = true
        requestBluetoothStatus("receiver-register")
        Log.d(TAG, "registered status receiver context=$context")
    }

    private fun requestPanelBluetoothStatus(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPanelRefreshMs < PANEL_REFRESH_THROTTLE_MS) return
        lastPanelRefreshMs = now
        requestBluetoothStatus("panel-$reason", allowReconnect = true)
    }

    private fun requestBluetoothStatus(reason: String, allowReconnect: Boolean = false) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, allowReconnect)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        Log.d(TAG, "requested bluetooth status reason=$reason allowReconnect=$allowReconnect")
    }

    private fun isOppoPod(device: BluetoothDevice): Boolean {
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

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isOppoAddress(address)) return true
        }
        return false
    }

    private fun miLinkAncState(): Int {
        loadState()
        return when (currentAnc) {
            2 -> 1
            3 -> 2
            else -> 0
        }
    }

    private fun oppoAncFromMiLink(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun miLinkFindRingState(): Int {
        // 自定义按钮=无（无 handler）：返回隐藏哨兵，面板会隐藏该控件
        val handler = activeHandler() ?: return FIND_RING_HIDDEN
        return if (handler.isActive()) FIND_RING_ACTIVE else FIND_RING_IDLE
    }

    private fun miLinkFindRingActive(): Boolean {
        return activeHandler()?.isActive() ?: false
    }

    private fun miLinkSpatialMode(): Int {
        loadState()
        if (!spatialAudioPanelEnabled()) return -1
        return miLinkSpatialModeFromMode(currentSpatialAudioMode)
    }

    private fun oppoSpatialFromMiLink(mode: Int): Int {
        return when (mode) {
            9, 11 -> SpatialAudioMode.HEAD_TRACKING
            1 -> SpatialAudioMode.FIXED
            else -> SpatialAudioMode.OFF
        }
    }

    private fun miLinkSpatialModeFromMode(mode: Int): Int {
        if (!spatialAudioPanelEnabled()) return -1
        return when (mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)) {
            SpatialAudioMode.HEAD_TRACKING -> 11
            SpatialAudioMode.FIXED -> 1
            else -> 0
        }
    }

    private fun miLinkAudioEffectState(): Int {
        loadState()
        return currentAudioEffectState()
    }

    private fun miLinkDeviceSpatialType(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    private fun miLinkSwitchState(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    private fun currentAudioEffectState(): Int {
        return if (spatialAudioPanelEnabled()) {
            currentSpatialAudioMode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        } else {
            -1
        }
    }

    private fun spatialAudioPanelEnabled(): Boolean {
        return milinkSpatialAudioOptionEnabled
    }

    private fun updateSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        saveState(context)
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

    // 解析自定义按钮功能：intent extra > 本地缓存 > 远程 prefs（默认 GAME_MODE）
    private fun refreshCustomButtonFunction(intent: Intent? = null) {
        val localPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = localPrefs
            ?.takeIf { it.contains(CustomButtonFunction.PREF_KEY) }
            ?.getString(CustomButtonFunction.PREF_KEY, null)
            ?.let { CustomButtonFunction.fromPreference(it) }
        customButtonFunction = if (intent?.hasExtra(CustomButtonFunction.PREF_KEY) == true) {
            CustomButtonFunction.fromPreference(intent.getStringExtra(CustomButtonFunction.PREF_KEY))
        } else {
            reloadRemotePrefs()
            runCatching {
                CustomButtonFunction.fromPreference(prefs.getString(CustomButtonFunction.PREF_KEY, null))
            }.getOrDefault(cached ?: CustomButtonFunction.GAME_MODE)
        }
        localPrefs?.edit()
            ?.putString(CustomButtonFunction.PREF_KEY, customButtonFunction.preferenceValue)
            ?.apply()
    }

    // 解析自定义按钮位置：intent extra > 本地缓存 > 远程 prefs（默认 UPPER）。
    private fun refreshCustomButtonPosition(intent: Intent? = null) {
        val localPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = localPrefs
            ?.takeIf { it.contains(CustomButtonPosition.PREF_KEY) }
            ?.getString(CustomButtonPosition.PREF_KEY, null)
            ?.let { CustomButtonPosition.fromPreference(it) }
        customButtonPosition = if (intent?.hasExtra(CustomButtonPosition.PREF_KEY) == true) {
            CustomButtonPosition.fromPreference(intent.getStringExtra(CustomButtonPosition.PREF_KEY))
        } else {
            reloadRemotePrefs()
            runCatching {
                CustomButtonPosition.fromPreference(prefs.getString(CustomButtonPosition.PREF_KEY, null))
            }.getOrDefault(cached ?: CustomButtonPosition.UPPER)
        }
        localPrefs?.edit()
            ?.putString(CustomButtonPosition.PREF_KEY, customButtonPosition.preferenceValue)
            ?.apply()
    }

    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        val left = batteryValue(currentBattery.left)
        val right = batteryValue(currentBattery.right)
        val box = batteryValue(currentBattery.case)
        return listOf(
            box,
            left,
            right,
            chargingValue(currentBattery.case),
            chargingValue(currentBattery.left),
            chargingValue(currentBattery.right)
        )
    }

    private fun batteryPercentForMiLink(): Int {
        loadState()
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams?): Int {
        if (params?.isConnected != true) return 255
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: PodParams?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    private fun sendOppoAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoAnc skipped: context is null mode=$mode")
            return
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
        Log.d(TAG, "sendOppoAnc broadcast sent mode=$mode")
    }

    private fun sendOppoGameMode(enabled: Boolean, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoGameMode skipped: context is null enabled=$enabled")
            return
        }
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
        Log.d(TAG, "sendOppoGameMode broadcast sent enabled=$enabled")
    }

    private fun sendOppoSpatialAudio(mode: Int, fallbackContext: Context? = null) {
        if (!spatialAudioPanelEnabled()) {
            Log.d(TAG, "sendOppoSpatialAudio skipped: MiLink spatial option disabled mode=$mode")
            return
        }
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoSpatialAudio skipped: context is null mode=$mode")
            return
        }
        Intent(OppoPodsAction.ACTION_SPATIAL_AUDIO_SET).apply {
            putExtra("mode", mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING))
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
        Log.d(TAG, "sendOppoSpatialAudio broadcast sent mode=$mode")
    }

    private fun sendOppoSpatialSound(enabled: Boolean, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.w(TAG, "sendOppoSpatialSound skipped: context is null enabled=$enabled")
            return
        }
        Intent(OppoPodsAction.ACTION_SPATIAL_SOUND_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
        Log.d(TAG, "sendOppoSpatialSound broadcast sent enabled=$enabled")
    }

    private fun sendSpatialChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        listOf(BuildConfig.APPLICATION_ID, "com.milink.service", "com.android.settings").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_SPATIAL_AUDIO_CHANGED).apply {
                currentAddress?.let { putExtra("address", it) }
                currentName?.let { putExtra("device_name", it) }
                putExtra("mode", normalizedMode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
        Log.d(TAG, "sendSpatialChanged broadcast sent mode=$normalizedMode")
    }

    private fun sendMiLinkAncChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("status", mode)
            setPackage("com.milink.service")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        Log.d(TAG, "sendMiLinkAncChanged broadcast sent mode=$mode")
    }

    private fun notifyFindRingChanged(controller: Any? = lastHeadsetController, device: BluetoothDevice? = lastHeadsetDevice) {
        if (controller != null && device != null) {
            notifyHeadsetPropertyChanged(controller, device, HEADSET_FIND_RING_CHANGED)
        }
        refreshCustomButtonPlacement()
    }

    private fun notifySpatialUiChanged(
        owner: Any? = lastHeadsetController ?: lastProfileContext,
        device: BluetoothDevice? = lastHeadsetDevice,
        mode: Int = currentSpatialAudioMode
    ) {
        val targetDevice = device ?: return
        val spatialMode = miLinkSpatialModeFromMode(mode)
        val audioEffectState = currentAudioEffectState()
        listOf(owner, lastHeadsetController, lastProfileContext)
            .distinctBy { it?.javaClass?.name }
            .forEach { target ->
                syncSpatialModel(target, targetDevice, spatialMode)
                notifyHeadsetPropertyChanged(target, targetDevice, 9)
                notifyHeadsetPropertyChanged(target, targetDevice, 4)
                notifyProfileAudioEffectListeners(target, audioEffectState)
            }
        refreshCustomButtonPlacement()
    }

    private fun syncSpatialModel(owner: Any?, device: BluetoothDevice, spatialMode: Int) {
        val model = runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull() ?: return
        if (!isTargetAncBatteryModel(model, device)) return
        runCatching { setObjectField(model, "spatialState", spatialMode) }
        runCatching { setObjectField(model, "deviceSpatialType", miLinkDeviceSpatialType()) }
    }

    private fun notifyProfileAudioEffectListeners(owner: Any?, audioEffectState: Int) {
        runCatching {
            val listener = getObjectField(owner, "audioEffectListener")
            callMethod(listener, "invoke", audioEffectState)
        }
    }

    private fun rememberHeadsetController(className: String, controller: Any?, device: BluetoothDevice) {
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" -> {
                lastHeadsetController = controller
                lastHeadsetDevice = device
            }
            "com.miui.headset.runtime.ProfileContext" -> {
                lastProfileContext = controller
                lastHeadsetDevice = device
            }
        }
    }

    private fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastHeadsetController, "context") as? Context }.getOrNull()
            ?: return
        context = ownerContext.applicationContext ?: ownerContext
    }

    private fun isTargetAncBatteryModel(model: Any?, fallbackDevice: BluetoothDevice? = lastHeadsetDevice): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
            ?: fallbackDevice
            ?: return false
        return isOppoPod(device)
    }

    // hook HeadSetsDetail.onDetachedFromWindow，用标志位标记面板正在销毁，
    // 取代脆弱的运行时堆栈判断（关面板时系统会自动发 find-ring stop，需吞掉以免误关游戏模式）
    private fun hookHeadSetsDetailDetach() {
        val detailClass = findHeadSetsDetailClass() ?: run {
            Log.w(TAG, "hook HeadSetsDetail.onDetachedFromWindow skipped: class not found")
            return
        }
        runCatching {
            val method = detailClass.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }
            hookBefore(method) { panelDetaching = true }
            hookAfter(method) {
                panelDetaching = false
                if (customButtonDetail?.get() === instance) {
                    customButtonDetail = null
                }
            }
            Log.d(TAG, "hooked ${detailClass.name}.onDetachedFromWindow")
        }.onFailure { Log.w(TAG, "hook HeadSetsDetail.onDetachedFromWindow skipped", it) }
    }

    private fun findHeadSetsDetailClass(): Class<*>? {
        return listOf(
            "com.miui.circulateplus.world.headset.HeadSetsDetail",
            "com.miui.circulate.world.headset.HeadSetsDetail",
            "com.miui.circulate.world.detail.HeadSetsDetail"
        ).firstNotNullOfOrNull { className ->
            runCatching { findClass(className) }.getOrNull()
        }
    }

    private fun findHeadsetControllerClass(detailClass: Class<*>, headsetInfoClass: Class<*>): Class<*>? {
        runCatching {
            detailClass.getDeclaredMethod("getHeadsetController").returnType
        }.getOrNull()
            ?.takeIf { hasHeadsetControllerCommandSignature(it, headsetInfoClass) }
            ?.let { return it }

        return detailClass.declaredFields
            .map { it.type }
            .firstOrNull { hasHeadsetControllerCommandSignature(it, headsetInfoClass) }
    }

    private fun hasHeadsetControllerCommandSignature(controllerClass: Class<*>, headsetInfoClass: Class<*>): Boolean {
        return controllerCommandMethods(controllerClass, headsetInfoClass).isNotEmpty()
    }

    private fun controllerCommandMethods(controllerClass: Class<*>, headsetInfoClass: Class<*>): List<java.lang.reflect.Method> {
        val intType = Int::class.javaPrimitiveType!!
        return controllerClass.declaredMethods.filter { method ->
            CompletableFuture::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == headsetInfoClass &&
                method.parameterTypes[1] == intType
        }.onEach { it.isAccessible = true }
    }

    // Applies to the two explicitly selected SynergyView instances only.
    private fun applySubtitle(view: View, resId: Int, text: CharSequence?) {
        runCatching {
            val subtitle = (findViewByEntryName(view, "item_subtitle") as? TextView)
                ?: run {
                    val pkg = view.resources.getResourcePackageName(resId)
                    val subtitleId = view.resources.getIdentifier("item_subtitle", "id", pkg)
                    if (subtitleId == 0) return@run null
                    view.findViewById<TextView>(subtitleId)
                }
                ?: return
            subtitle.text = text ?: ""
            subtitle.visibility = if (text == null) View.GONE else View.VISIBLE
            Log.d(TAG, "applySubtitle set text=$text")
        }.onFailure { Log.w(TAG, "applySubtitle failed", it) }
    }

    // 在 SynergyView 的 item_icon 上设置自定义图标（drawable=null → 保持原生）
    private fun applyIcon(view: View, resId: Int, drawable: Drawable?) {
        drawable ?: return
        runCatching {
            val iconView = (findViewByEntryName(view, "item_icon") as? ImageView)
                ?: run {
                    val pkg = view.resources.getResourcePackageName(resId)
                    val iconId = view.resources.getIdentifier("item_icon", "id", pkg)
                    if (iconId == 0) return@run null
                    view.findViewById<ImageView>(iconId)
                }
                ?: return
            iconView.setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
            Log.d(TAG, "applyIcon set")
        }.onFailure { Log.w(TAG, "applyIcon failed", it) }
    }

    // 从模块 APK 跨包加载游戏模式图标并缓存（妙享进程无法直接访问模块资源）
    private fun loadGameModeIcon(view: View): Drawable? {
        gameModeIcon?.let { return it }
        return runCatching {
            val moduleContext = view.context.createPackageContext(
                MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY
            )
            val resId = moduleContext.resources.getIdentifier("ic_game_mode", "drawable", MODULE_PACKAGE)
            if (resId == 0) return null
            moduleContext.getDrawable(resId)?.also { gameModeIcon = it }
        }.onFailure { Log.w(TAG, "loadGameModeIcon failed", it) }.getOrNull()
    }

    private fun resourceEntryName(view: View, resId: Int): String? {
        if (resId == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(resId) }.getOrNull()
    }

    private fun setSynergyTitle(view: View, title: CharSequence): Boolean {
        var viewClass: Class<*>? = view.javaClass
        while (viewClass != null) {
            viewClass.declaredMethods.firstOrNull { method ->
                method.name == "setTitle" && method.parameterTypes.contentEquals(arrayOf(CharSequence::class.java))
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(view, title)
                    true
                }.onFailure { Log.w(TAG, "SynergyView.setTitle fallback failed", it) }.getOrDefault(false)
            }
            viewClass = viewClass.superclass
        }
        val titleView = findViewByEntryName(view, "item_title") as? TextView ?: return false
        titleView.text = title
        return true
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice, updateType: Int) {
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull()
        if (listener == null) {
            Log.w(TAG, "notifyHeadsetPropertyChanged skipped: listener is null updateType=$updateType")
            return
        }
        runCatching {
            callMethod(listener, "invoke", device, updateType)
            Log.d(TAG, "notifyHeadsetPropertyChanged invoked updateType=$updateType address=${device.address}")
        }.onFailure { Log.w(TAG, "notifyHeadsetPropertyChanged failed updateType=$updateType", it) }
    }

    private fun BatteryParams.debugString(): String {
        return "left=${left?.battery}/${left?.isConnected}/${left?.isCharging} right=${right?.battery}/${right?.isConnected}/${right?.isCharging} case=${case?.battery}/${case?.isConnected}/${case?.isCharging}"
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putBoolean("game_mode", currentGameMode)
            .putBoolean("spatial_sound", currentSpatialSound)
            .putInt("spatial_audio_mode", currentSpatialAudioMode)
            .putBoolean(OppoPodsPrefsKey.MILINK_SPATIAL_AUDIO_OPTION_ENABLED, milinkSpatialAudioOptionEnabled)
            .putString(CustomButtonFunction.PREF_KEY, customButtonFunction.preferenceValue)
            .putString(CustomButtonPosition.PREF_KEY, customButtonPosition.preferenceValue)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentGameMode = prefs.getBoolean("game_mode", currentGameMode)
        currentSpatialSound = prefs.getBoolean("spatial_sound", currentSpatialSound)
        currentSpatialAudioMode = prefs.getInt("spatial_audio_mode", currentSpatialAudioMode)
            .coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        if (prefs.contains(CustomButtonFunction.PREF_KEY)) {
            customButtonFunction = CustomButtonFunction.fromPreference(
                prefs.getString(CustomButtonFunction.PREF_KEY, null)
            )
        }
        if (prefs.contains(CustomButtonPosition.PREF_KEY)) {
            customButtonPosition = CustomButtonPosition.fromPreference(
                prefs.getString(CustomButtonPosition.PREF_KEY, null)
            )
        }
        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
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
}
