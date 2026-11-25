package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import moe.chenxy.oppopods.pods.DeviceProfile
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.pods.MiuiHeadsetCodec
import moe.chenxy.oppopods.pods.NoiseLevel
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.pods.ProfileKeys
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 复用 Settings 的 java 耳机插件页，仅把 OPPO 设备的数据和命令转换为插件契约。 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object SettingsMiuiPluginHook : HookContext() {
    private const val TAG = "OppoPods-MiuiSettings"
    // 独立虚拟 ID：不全局篡改真实小米机型配置。
    private const val BRIDGE_ID = "OPPOPODS_PLUTO"
    private const val BRIDGE_SUPPORT = "$BRIDGE_ID,000000000000000010000000"
    private const val PLUGIN_ACTIVITY = "plugin.settings.java.JavaActivity"
    private const val PLUGIN_FRAGMENT = "plugin.settings.java.MiuiHeadsetFragment"
    private const val PREFS = "oppopods_details_state"

    private data class State(
        val connected: Boolean = false,
        val battery: BatteryParams? = null,
        val mode: Int? = null,
        val noiseLevel: Int? = null,
        val lastManualLevel: Int? = null,
        val profile: DeviceProfile? = null,
    )
    private class Session(val device: BluetoothDevice) {
        var resumed = false
        var service: Any? = null
        var lastRequest = 0L
        var imageRequested = false
        var lastImageAttempt = 0L
        var rebindControls = false
    }

    private val knownAddresses = ConcurrentHashMap.newKeySet<String>()
    private val states = ConcurrentHashMap<String, State>()
    private val controlAddresses = ConcurrentHashMap<String, String>()
    private val activities = Collections.synchronizedMap(WeakHashMap<Activity, Session>())
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val hookedAfterMethods = ConcurrentHashMap.newKeySet<Method>()
    private val pluginLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var imageExecutor = Executors.newSingleThreadExecutor()
    private var routePending = false
    @Volatile private var disposed = false
    private var periodicStarted = false

    private val periodic = object : Runnable {
        override fun run() {
            if (disposed) return
            if (activityEntries().any { it.second.resumed }) {
                requestStatus(false)
                activityEntries().filter { it.second.resumed }.forEach { (activity, session) ->
                    publish(activity, session)
                }
                main.postDelayed(this, 3_000)
            } else {
                periodicStarted = false
                if (!routePending) unregisterReceiver()
            }
        }
    }

    override fun onHook() {
        disposed = false
        if (imageExecutor.isShutdown) imageExecutor = Executors.newSingleThreadExecutor()
        installRoute()
        installLoaderObservers()
        main.post { if (!disposed) adoptOpenActivities() }
    }

    override fun onHotReloading() {
        disposed = true
        main.removeCallbacksAndMessages(null)
        periodicStarted = false
        routePending = false
        unregisterReceiver()
        imageExecutor.shutdownNow()
        // 宿主字段和 WeakReference 中的 module Proxy 必须在热重载前移除。
        activityEntries().forEach { (activity, session) ->
            clearServiceReferences(activity, session.service)
            session.service = null
        }
        activities.clear()
        pluginLoaders.clear()
        states.clear()
        controlAddresses.clear()
        knownAddresses.clear()
        hookedMethods.clear()
        hookedAfterMethods.clear()
        context = null
    }

    private fun installRoute() {
        val cached = findClass("com.android.settingslib.bluetooth.CachedBluetoothDevice")
        listOf("com.android.settings.bluetooth.BluetoothSettings", "com.android.settings.bluetooth.MiuiBluetoothSettings")
            .forEach { name ->
                install("plugin entry $name") {
                    before(findMethod(name, "checkStartMiuiHeadset", cached)) {
                        val device = runCatching { callMethod(args[0], "getDevice") as? BluetoothDevice }.getOrNull()
                            ?: return@before
                        if (!isOppo(device)) return@before
                        val activity = callMethod(instance, "getActivity") as? Activity ?: return@before
                        initializeContext(activity)
                        registerReceiver()
                        routePending = true
                        val intent = Intent("miui.bluetooth.action.HEADSET_SETTINGS_PLUGIN").apply {
                            setPackage("com.android.settings")
                            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                            putExtra("bluetoothaddress", device.address)
                            putExtra("MIUI_HEADSET_SUPPORT", BRIDGE_SUPPORT)
                            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
                        }
                        activity.startActivity(intent)
                        requestStatus(true)
                        main.postDelayed({
                            routePending = false
                            if (activityEntries().none { it.second.resumed }) unregisterReceiver()
                        }, 15_000)
                        Log.i(TAG, "opened Xiaomi headset plugin entry for OPPO")
                        result = true
                    }
                }
            }
    }

    private fun installLoaderObservers() {
        val loadersClass = "com.iqiyi.android.qigsaw.core.splitload.SplitApplicationLoaders"
        install("Qigsaw loader") {
            after(findMethodByParamCount(loadersClass, "addClassLoader", 1)) {
                (args[0] as? ClassLoader)?.let(::installPlugin)
            }
            val registry = findMethod(loadersClass, "getInstance").invoke(null)
            (callMethod(registry, "getValidClassLoader", "java") as? ClassLoader)?.let(::installPlugin)
        }
        install("plugin activity loader") {
            after(findMethod(
                "android.app.Instrumentation", "newActivity",
                ClassLoader::class.java, String::class.java, Intent::class.java
            )) {
                val activity = result as? Activity ?: return@after
                if (activity.javaClass.name == PLUGIN_ACTIVITY) installPlugin(activity.javaClass.classLoader!!)
            }
        }
        // 有些版本提前把 java 合并进宿主 loader；类不存在时不影响正常包加载。
        runCatching { Class.forName(PLUGIN_ACTIVITY, false, appClassLoader).classLoader?.let(::installPlugin) }
    }

    @Synchronized
    private fun installPlugin(loader: ClassLoader) {
        if (disposed || loader in pluginLoaders) return
        val activityClass = runCatching { Class.forName(PLUGIN_ACTIVITY, false, loader) }.getOrNull() ?: return
        pluginLoaders.add(loader)
        fun host(name: String) = Class.forName(name, false, loader)
        fun method(name: String, member: String, vararg params: Class<*>) =
            host(name).getDeclaredMethod(member, *params).apply { isAccessible = true }
        install("Pluto bridge identity") {
            before(method("plugin.settings.java.HeadsetIDConstants", "isPlutoHeadset", String::class.java)) {
                if (args[0] == BRIDGE_ID) result = true
            }
        }
        install("modern native settings identity") {
            before(method("plugin.settings.java.HeadsetIDConstants", "isSupportV2Version", String::class.java, Context::class.java)) {
                if (args[0] == BRIDGE_ID) result = true
            }
        }
        val configurator = "plugin.settings.java.InterfaceForDeviceFuncConfig"
        listOf("isFeatureVisibleInFragment" to 3, "isSupportFeature" to 2).forEach { (name, count) ->
            install("supported plugin features $name") {
                before(host(configurator).declaredMethods.single { it.name == name && it.parameterCount == count }) {
                    if (args[0] != BRIDGE_ID) return@before
                    result = supportedFeature(args.lastOrNull() as? String ?: "")
                }
            }
        }
        install("plugin activity create") {
            before(method(PLUGIN_ACTIVITY, "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@before
                initializeContext(activity)
                val device = intentDevice(activity.intent)
                trace("create-entry device=${device != null} keys=${activity.intent.extras?.keySet()?.joinToString()}")
                if (device == null || !isOppo(device)) return@before
                registerReceiver()
                patchIntent(activity.intent, device)
                activities[activity] = Session(device)
                trace("activity-created")
                routePending = false
            }
            after(method(PLUGIN_ACTIVITY, "onCreate", Bundle::class.java)) {
                val activity = instance as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                ensureService(activity, session, loader)
                main.post { if (!disposed && activities[activity] === session) initializeFragments(activity, session) }
            }
        }
        install("service bridge") {
            before(method(PLUGIN_ACTIVITY, "getService")) {
                val activity = instance as? Activity ?: return@before
                val session = activities[activity] ?: return@before
                ensureService(activity, session, loader)
                result = session.service
            }
            // bindService 仍交给宿主完成生命周期；回调仅替换 OPPO 这一个 Activity 的服务数据源。
            val connectionClass = host("plugin.settings.java.JavaActivity\$ServiceConnectMma")
            before(connectionClass.getDeclaredMethod("onServiceConnected", android.content.ComponentName::class.java, android.os.IBinder::class.java)) {
                val activity = (getObjectField(instance, "activityWeakRef") as? WeakReference<*>)?.get() as? Activity
                    ?: return@before
                val session = activities[activity] ?: return@before
                ensureService(activity, session, loader)
                main.post { if (!disposed) initializeFragments(activity, session) }
                result = null
            }
        }
        install("plugin activity resume") {
            after(method(PLUGIN_ACTIVITY, "onResume")) {
                val activity = instance as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                initializeContext(activity)
                registerReceiver()
                session.resumed = true
                trace("activity-resumed")
                ensureService(activity, session, loader)
                requestStatus(true)
                main.post { if (!disposed) initializeFragments(activity, session) }
                startPeriodic()
            }
        }
        install("plugin new intent") {
            after(Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)) {
                val activity = instance as? Activity ?: return@after
                if (activity.javaClass.name != PLUGIN_ACTIVITY) return@after
                val intent = args[0] as? Intent ?: return@after
                val nextDevice = intentDevice(intent) ?: return@after
                val previous = activities[activity]
                if (previous == null && !isOppo(nextDevice)) return@after
                if (previous?.device?.address != nextDevice.address) {
                    if (isOppo(nextDevice)) patchIntent(intent, nextDevice)
                    activity.intent = intent
                    activity.recreate()
                }
            }
        }
        install("plugin activity pause") {
            val pause = activityClass.declaredMethods.firstOrNull { it.name == "onPause" && it.parameterCount == 0 }
                ?: Activity::class.java.getDeclaredMethod("onPause")
            after(pause) {
                val activity = instance as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                session.resumed = false
                if (activityEntries().none { it.second.resumed }) {
                    main.removeCallbacks(periodic)
                    periodicStarted = false
                    unregisterReceiver()
                    states.replaceAll { _, value -> value.copy(connected = false, battery = null, mode = null, noiseLevel = null) }
                }
            }
        }
        install("plugin activity destroy") {
            val destroy = activityClass.declaredMethods.first { it.name == "onDestroy" && it.parameterCount == 0 }
            after(destroy) {
                val activity = instance as? Activity ?: return@after
                activities.remove(activity)?.let { clearServiceReferences(activity, it.service) }
            }
        }
        install("OPPO transport gate") {
            host("plugin.settings.java.tws.BleMmaUtil").declaredMethods
                .filter { it.name.startsWith("isBleMmaConnect") }.forEach { gate ->
                    before(gate) {
                        val device = args.filterIsInstance<BluetoothDevice>().firstOrNull() ?: return@before
                        if (isOppo(device)) result = state(device).connected
                    }
                }
            before(method(PLUGIN_ACTIVITY, "isWear")) {
                val activity = instance as? Activity ?: return@before
                activities[activity]?.let { result = state(it.device).connected }
            }
        }
        install("Pluto capability controls") {
            val controllerClass = host("plugin.settings.java.tws.anc.MiuiHeadsetForPluto")
            controllerClass.declaredMethods.filter {
                it.name in setOf("onCreatView", "updateSubAncOpen", "setSubOpneAncEnable")
            }.forEach { uiMethod ->
                after(uiMethod) {
                    val device = runCatching { getObjectField(instance, "mDevice") as? BluetoothDevice }.getOrNull()
                        ?: return@after
                    if (!isOppo(device)) return@after
                    val fragment = (getObjectField(instance, "mFragment") as? WeakReference<*>)?.get()
                    applyControlCapabilities(fragment, state(device))
                }
            }
        }
        install("native fragment data") {
            after(method(PLUGIN_FRAGMENT, "onCreateView", android.view.LayoutInflater::class.java, android.view.ViewGroup::class.java, Bundle::class.java)) {
                val activity = callMethod(instance, "getActivity") as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                main.post { if (!disposed) initializeFragments(activity, session) }
            }
            after(method(PLUGIN_FRAGMENT, "onServiceConnected")) {
                val activity = callMethod(instance, "getActivity") as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                publish(activity, session)
                hideUnsupportedPreferences(instance)
                loadImage(instance, session)
            }
            after(method(PLUGIN_FRAGMENT, "onDestroy")) {
                val activity = callMethod(instance, "getActivity") as? Activity ?: return@after
                val session = activities[activity] ?: return@after
                val activityRef = WeakReference(activity)
                // Settings 17 的预测返回在 onDestroy 内仍把 mTransitioningOp 计入返回栈。
                // 等原生 Fragment 清理和当前事务完成，再复查插件自己的退出条件。
                // 不接管返回事件，也不改根页面、子页面的事务和返回栈。
                main.post {
                    val target = activityRef.get()
                    if (target != null && !disposed && activities[target] === session &&
                        !target.isFinishing && !target.isDestroyed && !target.isChangingConfigurations
                    ) {
                        guarded("complete native fragment teardown") {
                            val manager = callMethod(target, "getSupportFragmentManager")
                            val count = callMethod(manager, "getBackStackEntryCount") as? Int ?: return@guarded
                            if (count == 0) callMethod(target, "checkActivityDestory")
                            trace("native-root-settled count=$count finishing=${target.isFinishing}")
                        }
                    }
                }
            }
            before(method(PLUGIN_FRAGMENT, "handleConnectMmaFailed", String::class.java)) {
                val activity = callMethod(instance, "getActivity") as? Activity ?: return@before
                if (activities.containsKey(activity)) result = null
            }
        }
        Log.i(TAG, "Xiaomi headset plugin hooks installed")
    }

    private fun patchIntent(intent: Intent, device: BluetoothDevice) {
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        intent.putExtra("bluetoothaddress", device.address)
        intent.putExtra("MIUI_HEADSET_SUPPORT", BRIDGE_SUPPORT)
        intent.putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
    }

    private fun supportedFeature(feature: String): Boolean {
        val entries = activityEntries()
        val profile = (entries.firstOrNull { it.second.resumed } ?: entries.lastOrNull())
            ?.second?.device?.let { state(it).profile }
        return when (feature) {
            "FEATURE_ANC" -> profile?.supportedAncModes()?.let { it.size >= 2 } ?: true
            "FEATURE_BATTERY_OPTIMIZATION", "FEATURE_MAIN_HOME_PAGE_RECONSTRUCTION",
            "FEATURE_SETTINGS_VERSION_V2", "FEATURE_MORE_SETTINGS" -> true
            "SUB_NOISE_CONTROL" -> profile?.let { MiuiHeadsetCodec.supportedManualNoiseLevels(it.commands.keys).isNotEmpty() } ?: false
            "FEATURE_INTELLIGENCE_ANC" -> profile?.let { MiuiHeadsetCodec.smartAncCommandKey(it.commands.keys) != null } ?: false
            else -> false
        }
    }

    private fun ensureService(activity: Activity, session: Session, loader: ClassLoader) {
        if (session.service == null) {
            val api = Class.forName("com.android.bluetooth.ble.app.IMiuiHeadsetService", false, loader)
            val binder = Binder()
            val activityRef = WeakReference(activity)
            session.service = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, args ->
                if (disposed) return@newProxyInstance defaultResult(method.returnType)
                val target = activityRef.get() ?: return@newProxyInstance defaultResult(method.returnType)
                serviceCall(target, session, binder, method, args.orEmpty())
            }
        }
        setObjectField(activity, "mService", session.service)
    }

    private fun serviceCall(activity: Activity, session: Session, binder: Binder, method: Method, args: Array<out Any?>): Any? {
        val value = state(session.device)
        return when (method.name) {
            "asBinder" -> binder
            "toString" -> "OppoPodsHeadsetService"
            "hashCode" -> System.identityHashCode(session)
            "equals" -> args.firstOrNull() === session.service
            "checkSupport", "getDeviceInfo" -> BRIDGE_SUPPORT
            "isMiTWS", "checkIsMiTWS" -> true
            "isSupportAudioSwitch" -> "0"
            "changeAncMode" -> {
                val mode = (args.firstOrNull() as? Int)?.let(MiuiHeadsetCodec::oppoModeFromMiui)
                if (mode != null) command(session, mode = mode)
                null
            }
            "changeAncLevel" -> {
                val anc = args.firstOrNull() as? String
                val profile = value.profile
                if (anc != null && profile != null) {
                    val level = MiuiHeadsetCodec.oppoNoiseLevelFromMiui(anc, MiuiHeadsetCodec.supportedManualNoiseLevels(profile.commands.keys))
                    if (level != null) command(session, mode = 2, level = level)
                }
                null
            }
            "setCommonCommand" -> commonCommand(activity, session, args)
            // 原生刷新会再次查询配置；状态由广播统一推送，不能在查询中反向 publish 形成回路。
            "getDeviceConfig", "getCommonConfig" -> null
            else -> defaultResult(method.returnType)
        }
    }

    private fun commonCommand(activity: Activity, session: Session, args: Array<out Any?>): String {
        val command = args.firstOrNull() as? Int ?: return ""
        val data = args.getOrNull(1) as? String ?: ""
        val value = state(session.device)
        return when (command) {
            102 -> if (value.connected) "1" else "0"
            109 -> ""
            110 -> "0"
            120 -> "true"
            132 -> {
                if (data.startsWith("F2|102|")) {
                    val enabled = data.substringAfterLast('|') == "01"
                    val profile = value.profile
                    if (enabled && profile != null) {
                        when (MiuiHeadsetCodec.smartAncCommandKey(profile.commands.keys)) {
                            ProfileKeys.SET_NOISE_LEVEL_SMART -> command(session, mode = 2, level = NoiseLevel.SMART)
                            ProfileKeys.ANC_ADAPTIVE -> command(session, mode = 4)
                        }
                    } else if (!enabled && profile != null) {
                        val levels = MiuiHeadsetCodec.supportedManualNoiseLevels(profile.commands.keys)
                        (value.lastManualLevel?.takeIf { it in levels } ?: levels.lastOrNull())
                            ?.let { command(session, mode = 2, level = it) }
                    }
                    main.postDelayed({ if (!disposed) publish(activity, session) }, 350)
                    "1"
                } else ""
            }
            else -> ""
        }
    }

    private fun command(session: Session, mode: Int, level: Int? = null) {
        val value = state(session.device)
        val profile = value.profile ?: return
        if (!value.connected || mode !in profile.supportedAncModes()) return
        val ctx = context ?: return
        // 档位包本身就是完整的 SET_ANC 位图；避免另发主模式包与它在 IO 协程中竞争。
        val action = if (level == null) OppoPodsAction.ACTION_ANC_SELECT else OppoPodsAction.ACTION_NOISE_LEVEL_SET
        ctx.sendBroadcast(Intent(action).apply {
            setPackage("com.android.bluetooth")
            putExtra("address", controlAddress(session.device))
            if (level == null) putExtra("status", mode) else putExtra("level", level)
        })
        Log.i(TAG, "requested native ANC mode=$mode level=$level")
        requestStatus(false)
    }

    private fun initializeFragments(activity: Activity, session: Session) {
        if (disposed || activity.isDestroyed) return
        val loader = activity.javaClass.classLoader ?: return
        ensureService(activity, session, loader)
        pluginFragments(activity).forEach { fragment ->
            guarded("initialize plugin fragment") {
                if (getObjectField(fragment, "mService") == null) callMethod(fragment, "onServiceConnected")
                if (session.rebindControls) {
                    getObjectField(fragment, "mMiuiHeadsetAnc")?.let { controller ->
                        callMethod(controller, "onCreatView")
                        session.rebindControls = false
                    }
                }
                hideUnsupportedPreferences(fragment)
                loadImage(fragment, session)
            }
        }
        publish(activity, session)
    }

    private fun publish(activity: Activity, session: Session) {
        if (disposed || activity.isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { if (!disposed) publish(activity, session) }
            return
        }
        val value = state(session.device)
        val battery = value.battery
        fun encoded(params: moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams?): Int =
            MiuiHeadsetCodec.encodeBattery(params?.battery, params?.isCharging == true, value.connected && params?.isConnected == true)
        val left = encoded(battery?.left)
        val right = encoded(battery?.right)
        val case = encoded(battery?.case)
        val anc = MiuiHeadsetCodec.miuiAncState(value.mode ?: -1, value.noiseLevel)
        pluginFragments(activity).forEach { fragment ->
            guarded("publish native state") {
                getObjectField(fragment, "mMiuiHeadsetBattery")?.let {
                    callMethod(it, "onBatteryChanged", left, right, case)
                }
                val controller = getObjectField(fragment, "mMiuiHeadsetAnc")
                if (controller != null) {
                    callMethod(controller, "refreshAnc", if (value.connected) anc.ancString else "-1")
                    callMethod(controller, "refreshStatus", "getcommonconfig102${session.device.address}", if (anc.intelligentOn) "01" else "00")
                    // OPPO 不提供小米通勤沉浸协议；该条目保持隐藏。
                }
                hideUnsupportedPreferences(fragment)
                applyControlCapabilities(fragment, value)
                loadImage(fragment, session)
            }
        }
    }

    private fun applyControlCapabilities(fragment: Any?, value: State) {
        val root = runCatching { getObjectField(fragment, "mRootView") as? View }.getOrNull() ?: return
        val profile = value.profile
        val manual = profile?.let { MiuiHeadsetCodec.supportedManualNoiseLevels(it.commands.keys).isNotEmpty() } == true
        val smart = profile?.let { MiuiHeadsetCodec.smartAncCommandKey(it.commands.keys) != null } == true
        val ready = value.connected && value.mode != null && profile != null
        val intelligent = MiuiHeadsetCodec.miuiAncState(value.mode ?: -1, value.noiseLevel).intelligentOn
        pluginView(root, "anc_open_sub_view")?.visibility =
            if ((manual || smart) && value.mode in listOf(2, 4)) View.VISIBLE else View.GONE
        pluginView(root, "anc_sliding_pluto")?.let { toggle ->
            (toggle.parent?.parent as? View)?.visibility = if (smart) View.VISIBLE else View.GONE
            toggle.isEnabled = ready && smart
        }
        pluginView(root, "anc_open_weight_adjust_pluto")?.let { slider ->
            slider.visibility = if (manual) View.VISIBLE else View.GONE
            slider.isEnabled = ready && manual && !intelligent
        }
        pluginView(root, "anc_open_text")?.visibility = if (manual) View.VISIBLE else View.GONE
    }

    private fun hideUnsupportedPreferences(fragment: Any?) {
        listOf(
            "immersion_category", "immersion_preference", "spatial_audio_root_key", "switchConfig",
            "moreSettingsInAi", "audio_share_container", "bleShareAudioCategory"
        ).forEach { key ->
            runCatching { callMethod(fragment, "findPreference", key) } .getOrNull()?.let {
                runCatching { callMethod(it, "setVisible", false) }
            }
        }
    }

    private fun loadImage(fragment: Any?, session: Session) {
        if (session.imageRequested || android.os.SystemClock.elapsedRealtime() - session.lastImageAttempt < 10_000L) return
        val root = runCatching { getObjectField(fragment, "mRootView") as? View }.getOrNull() ?: return
        val ctx = context ?: return
        session.imageRequested = true
        session.lastImageAttempt = android.os.SystemClock.elapsedRealtime()
        val rootRef = WeakReference(root)
        imageExecutor.execute {
            val bitmap = runCatching {
                ctx.contentResolver.openInputStream(PodImageStore.uri(PodImageSlot.HOME_IMAGE))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bitmap != null && !disposed) main.post {
                if (disposed) return@post
                val currentRoot = rootRef.get() ?: return@post
                val image = pluginView(currentRoot, "tic") as? ImageView
                image?.setImageBitmap(bitmap)
                image?.scaleType = ImageView.ScaleType.FIT_CENTER
                if (image == null) session.imageRequested = false
            } else if (!disposed) main.post { session.imageRequested = false }
        }
    }

    private fun pluginView(root: View, name: String): View? {
        val id = runCatching {
            Class.forName("plugin.settings.java.R\$id", false, root.context.classLoader)
                .getDeclaredField(name).getInt(null)
        }.getOrElse { root.resources.getIdentifier(name, "id", "com.xiaomi.settings.headsetplugin.java") }
        return if (id != 0) root.findViewById(id) else null
    }

    private fun initializeContext(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        knownAddresses.addAll(prefs?.getStringSet("known_addresses", emptySet()).orEmpty())
    }

    private fun registerReceiver() {
        val ctx = context ?: return
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_PROFILE_CHANGED)
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!disposed && intent != null) guarded("receive native state") { receive(intent) }
            }
        }
        ctx.registerReceiver(newReceiver, filter, Context.RECEIVER_EXPORTED)
        receiver = newReceiver
        trace("receiver-registered")
    }

    private fun receive(intent: Intent) {
        val address = (intent.getStringExtra("address") ?: intent.getStringExtra("device_address"))?.let(::normalize) ?: return
        if (address !in knownAddresses) {
            // LE 界面地址与 RFCOMM 经典地址可能不同，只接受唯一同名 LE 会话的配对映射。
            val name = intent.getStringExtra("device_name") ?: return
            val matches = activityEntries().filter { (_, session) ->
                session.device.type == BluetoothDevice.DEVICE_TYPE_LE &&
                    session.device.name.orEmpty().equals(name, ignoreCase = true)
            }
            if (matches.size != 1) return
            controlAddresses[normalize(matches.single().second.device.address)] = address
            knownAddresses.add(address)
        }
        states.compute(address) { _, current ->
            val value = current ?: State()
            when (intent.action) {
                OppoPodsAction.ACTION_PODS_CONNECTED -> value.copy(connected = true)
                OppoPodsAction.ACTION_PODS_DISCONNECTED -> State(profile = value.profile)
                OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> value.copy(battery = intent.batteryStatusCompat() ?: value.battery)
                OppoPodsAction.ACTION_PODS_ANC_CHANGED -> value.copy(mode = intent.getIntExtra("status", -1).takeIf { it in 1..4 })
                OppoPodsAction.ACTION_PODS_NOISE_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra("level", -1)
                    value.copy(noiseLevel = level, lastManualLevel =
                        level.takeIf { it in listOf(NoiseLevel.LIGHT, NoiseLevel.MEDIUM, NoiseLevel.DEEP) } ?: value.lastManualLevel)
                }
                OppoPodsAction.ACTION_PODS_PROFILE_CHANGED -> {
                    val profile = intent.getStringExtra(OppoPodsAction.EXTRA_PROFILE_JSON)?.let { DeviceProfileStore.parse(it) }
                    value.copy(profile = profile ?: value.profile)
                }
                else -> value
            }
        }
        val updated = states[address]
        trace("state ${intent.action?.substringAfterLast('.')} connected=${updated?.connected} mode=${updated?.mode} profile=${updated?.profile != null}")
        activityEntries().filter { controlAddress(it.second.device) == address }.forEach { (activity, session) -> publish(activity, session) }
    }

    private fun requestStatus(snapshot: Boolean) {
        val ctx = context ?: return
        val actions = if (snapshot) listOf(OppoPodsAction.ACTION_PODS_UI_INIT, OppoPodsAction.ACTION_REFRESH_STATUS)
            else listOf(OppoPodsAction.ACTION_REFRESH_STATUS)
        actions.forEach { action ->
            ctx.sendBroadcast(Intent(action).apply {
                setPackage("com.android.bluetooth")
                putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, false)
            })
        }
    }

    private fun startPeriodic() {
        if (periodicStarted) return
        periodicStarted = true
        main.postDelayed(periodic, 3_000)
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { context?.unregisterReceiver(it) } }
        receiver = null
    }

    private fun adoptOpenActivities() {
        guarded("adopt open plugin") {
            val thread = findMethod("android.app.ActivityThread", "currentActivityThread").invoke(null) ?: return@guarded
            val records = getObjectField(thread, "mActivities") as? Map<*, *> ?: return@guarded
            records.values.forEach { record ->
                val activity = runCatching { getObjectField(record, "activity") as? Activity }.getOrNull() ?: return@forEach
                if (activity.javaClass.name != PLUGIN_ACTIVITY) return@forEach
                val device = intentDevice(activity.intent) ?: return@forEach
                if (!isOppo(device)) return@forEach
                val loader = activity.javaClass.classLoader ?: return@forEach
                installPlugin(loader)
                initializeContext(activity)
                registerReceiver()
                val paused = runCatching { getObjectField(record, "paused") as? Boolean }.getOrNull() ?: true
                val session = Session(device).also { it.resumed = !paused; it.rebindControls = true }
                activities[activity] = session
                pluginFragments(activity).forEach { fragment ->
                    // 强制首次服务初始化重建 ANC，避免旧 module Proxy 留在弱引用中。
                    runCatching { setObjectField(fragment, "mService", null) }
                    runCatching { setObjectField(fragment, "mMiuiHeadsetAnc", null) }
                }
                if (session.resumed) {
                    initializeFragments(activity, session)
                    requestStatus(true)
                    startPeriodic()
                }
            }
        }
    }

    private fun clearServiceReferences(activity: Activity, service: Any?) {
        if (service == null) return
        // Codec、Rename、Animation 等宿主辅助对象也缓存服务，不能只清 Activity/ANC。
        val seen = Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun clear(value: Any?, depth: Int) {
            if (value == null || depth > 10 || !seen.add(value)) return
            if (value is WeakReference<*>) {
                if (value.get() === service) value.clear() else clear(value.get(), depth + 1)
                return
            }
            if (value is Iterable<*>) {
                value.forEach { if (it?.javaClass?.name?.startsWith("plugin.settings.java.") == true) clear(it, depth + 1) }
                return
            }
            if (!value.javaClass.name.startsWith("plugin.settings.java.")) return
            var type: Class<*>? = value.javaClass
            while (type?.name?.startsWith("plugin.settings.java.") == true) {
                type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }.forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        val child = field.get(value)
                        if (child === service) field.set(value, null) else clear(child, depth + 1)
                    }
                }
                type = type.superclass
            }
        }
        pluginFragments(activity).forEach { clear(it, 0) }
        clear(activity, 0)
    }

    private fun pluginFragments(activity: Activity): List<Any> = runCatching {
        val manager = callMethod(activity, "getSupportFragmentManager")
        (callMethod(manager, "getFragments") as? List<*>)?.filterNotNull()
            ?.filter { it.javaClass.name == PLUGIN_FRAGMENT }.orEmpty()
    }.getOrDefault(emptyList())

    private fun activityEntries(): List<Pair<Activity, Session>> = synchronized(activities) { activities.entries.map { it.key to it.value } }
    private fun controlAddress(device: BluetoothDevice): String =
        controlAddresses[normalize(device.address)] ?: normalize(device.address)
    private fun state(device: BluetoothDevice): State = states[controlAddress(device)] ?: State()
    private fun normalize(address: String) = address.uppercase(Locale.ROOT)
    private fun isOppo(device: BluetoothDevice): Boolean = runCatching {
        val address = normalize(device.address)
        if (address in knownAddresses) return@runCatching true
        if (device.name.orEmpty().contains("oppo", true) || device.alias.orEmpty().contains("oppo", true)) {
            knownAddresses.add(address)
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putStringSet("known_addresses", knownAddresses.toSet())?.apply()
            true
        } else false
    }.getOrDefault(false)

    private fun intentDevice(intent: Intent): BluetoothDevice? {
        runCatching { intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) }.getOrNull()?.let { return it }
        val address = intent.getStringExtra("bluetoothaddress") ?: intent.getStringExtra("VIRTUAL_DEVICE_ADDRESS") ?: return null
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address.uppercase(Locale.ROOT))) return null
        return runCatching {
            context?.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        }.getOrNull()
    }

    private fun defaultResult(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        String::class.java -> ""
        List::class.java -> emptyList<Any>()
        else -> null
    }

    private fun before(method: Method, block: HookParam.() -> Unit) {
        if (hookedMethods.add(method)) hookBefore(method.apply { isAccessible = true }, block)
    }
    private fun after(method: Method, block: HookParam.() -> Unit) {
        // 代理 loader 与真实 split loader 可能返回同一个 Class，两个 phase 分别去重。
        if (hookedAfterMethods.add(method)) hookAfter(method.apply { isAccessible = true }, block)
    }
    private fun trace(message: String) {
        // 仅保留短诊断，不写地址/耳机原始报文；系统禁用 logcat 时仍能确认适配进度。
        runCatching {
            val prefs = context?.getSharedPreferences("oppopods_plugin_diagnostics", Context.MODE_PRIVATE) ?: return
            val records = (prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList() + message).takeLast(20)
            prefs.edit().putString("events", records.joinToString("\n")).apply()
        }
    }

    private inline fun install(name: String, block: () -> Unit) = runCatching(block).onFailure {
        Log.w(TAG, "hook $name skipped", it)
        trace("hook $name: ${it.javaClass.simpleName}")
    }.let { }
    private inline fun guarded(name: String, block: () -> Unit) = runCatching(block).onFailure {
        Log.w(TAG, "$name failed", it)
        trace("$name: ${it.javaClass.simpleName}:${it.message?.take(140)}")
    }.let { }
}
