package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.oppopods.utils.FocusIslandUtil
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.oppopods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.NotificationSettings
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.R

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val NOTIFICATION_ID = 10003
    private const val NOTIFICATION_TAG_PREFIX = "BTHeadset"
    private const val LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX = "BTHeadsetIsland"
    private const val CONNECTION_CHANNEL_ID = "oppopods_connection_notification"
    private const val CONNECTION_CHANNEL_NAME = "OppoPods"
    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // ANC 模式本地缓存，用于循环切换和状态同步（1=关 2=降噪 3=通透 4=自适应）
    // 通过接收 ACTION_PODS_ANC_CHANGED 广播与 RfcommController 保持同步
    private var localAncMode = 1

    override fun onHook() {
        var notificationSettings = NotificationSettings.fromPrefs(prefs)
        val lastNotificationIslandStyle = mutableMapOf<String, Boolean>()
        var lastNotificationDevice: BluetoothDevice? = null
        var lastNotificationBatteryParams: BatteryParams? = null
        var lastNotificationRfcommConnected: Boolean = true

        fun notificationTag(address: String): String {
            return "$NOTIFICATION_TAG_PREFIX$address"
        }

        fun legacyIslandNotificationTag(address: String): String {
            return "$LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX$address"
        }

        fun cancelNotificationByTag(notificationManager: NotificationManager, tag: String) {
            notificationManager.cancelAsUser(
                tag,
                NOTIFICATION_ID,
                SystemApisUtils.getUserAllUserHandle()
            )
        }

        fun cancelAllPodsNotifications(context: Context) {
            try {
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.activeNotifications
                    .filter {
                        it.id == NOTIFICATION_ID &&
                            (it.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true ||
                                it.tag?.startsWith(LEGACY_ISLAND_NOTIFICATION_TAG_PREFIX) == true)
                    }
                    .mapNotNull { it.tag }
                    .forEach { cancelNotificationByTag(notificationManager, it) }
                lastNotificationIslandStyle.clear()
                lastNotificationDevice = null
                lastNotificationBatteryParams = null
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel active Pod Notifications!", e)
            }
        }

        fun cachedNotificationSettings(context: Context): NotificationSettings? {
            return context.getSharedPreferences(
                OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                Context.MODE_PRIVATE
            ).let { NotificationSettings.fromPrefsOrNull(it) }
        }

        fun cacheNotificationSettings(context: Context, settings: NotificationSettings) {
            settings.withUpdatedAtIfMissing().writeToPrefs(
                context.getSharedPreferences(
                    OppoPodsPrefsKey.NOTIFICATION_SETTINGS_CACHE_PREFS_NAME,
                    Context.MODE_PRIVATE
                )
            )
        }

        fun loadNotificationSettings(context: Context): NotificationSettings {
            reloadRemotePrefs()
            val remoteSettings = NotificationSettings.fromPrefs(prefs)
            if (
                remoteSettings.updatedAt == 0L &&
                prefs.contains(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION) &&
                !remoteSettings.showConnectionNotification
            ) {
                return remoteSettings
            }
            return NotificationSettings.newerOf(remoteSettings, cachedNotificationSettings(context))
        }

        fun effectiveNotificationSettings(
            intent: Intent? = null,
            context: Context? = null
        ): NotificationSettings {
            val intentSettings = NotificationSettings.fromIntent(intent, notificationSettings)
            return NotificationSettings.newerOf(
                intentSettings,
                context?.let { cachedNotificationSettings(it) }
            )
        }

        fun syncNotificationSettings(context: Context, intent: Intent) {
            notificationSettings = NotificationSettings.fromIntent(intent, notificationSettings)
                .withUpdatedAtIfMissing()
            cacheNotificationSettings(context, notificationSettings)
            Log.d(
                "OppoPods",
                "Notification settings synced in MiBluetooth: batteryIsland=${notificationSettings.showConnectionBatteryIsland}, popup=${notificationSettings.showConnectionPopup}, popupDismiss=${notificationSettings.connectionPopupDismissSeconds}s, show=${notificationSettings.showConnectionNotification}, island=${notificationSettings.notificationIslandStyle}, updatedAt=${notificationSettings.updatedAt}"
            )
        }

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle").apply {
                setPackage("com.android.bluetooth")
                putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS)
        }

        fun createPodsNotification(
            bluetoothDevice: BluetoothDevice?,
            context: Context,
            batteryParams: BatteryParams,
            showNotificationAsIsland: Boolean = notificationSettings.showNotificationAsIsland,
            rfcommConnected: Boolean = true
        ) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("OppoPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }
                val notificationTitle = if (rfcommConnected) {
                    alias ?: ""
                } else {
                    "${alias ?: ""}（已断开）"
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}：${batteryParams.case!!.battery} %" +
                            "${if (batteryParams.case!!.isCharging) " ⚡" else ""}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}：${batteryParams.left!!.battery} %" +
                        (if (batteryParams.left!!.isCharging) " ⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " | " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}：${batteryParams.right!!.battery} %" +
                        (if (batteryParams.right!!.isCharging) " ⚡" else "")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                val activeNotificationTag = notificationTag(address)
                val previousIslandStyle = lastNotificationIslandStyle[address]
                cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                if (previousIslandStyle == true && !showNotificationAsIsland) {
                    cancelNotificationByTag(notificationManager, activeNotificationTag)
                }
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        CONNECTION_CHANNEL_ID,
                        CONNECTION_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.setPackage("com.android.bluetooth")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, PENDING_INTENT_FLAGS)
                )
                // 循环切换降噪模式：降噪 → 自适应 → 通透 → 关，指定 package 确保广播路由到 com.android.bluetooth 进程
                val ancCycleIntent = Intent(OppoPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage("com.android.bluetooth")
                ancCycleIntent.setIdentifier("BTHeadset$address")
                val moduleContext = context.createPackageContext(
                    "moe.chenxy.oppopods", Context.CONTEXT_IGNORE_SECURITY
                )
                val headsetIcon = Icon.createWithBitmap(
                    BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
                )
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.oppopods.show_pods_ui").apply {
                        setClassName("moe.chenxy.oppopods", "moe.chenxy.oppopods.PopupActivity")
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = if (showNotificationAsIsland) FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = notificationTitle
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = notificationTitle
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = notificationTitle
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, PENDING_INTENT_FLAGS)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                        addActionInfo {
                            val disconnectLabel = moduleContext.getString(R.string.notification_btn_disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                setPackage("com.android.bluetooth")
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, PENDING_INTENT_FLAGS)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                } else null
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    activeNotificationTag,
                    NOTIFICATION_ID,
                    Notification.Builder(context, CONNECTION_CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(notificationTitle)
                        .setDefaults(-1)
                        .setContentTitle(notificationTitle)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
                lastNotificationIslandStyle[address] = showNotificationAsIsland
                lastNotificationDevice = bluetoothDevice
                lastNotificationBatteryParams = batteryParams
                lastNotificationRfcommConnected = rfcommConnected
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    cancelNotificationByTag(notificationManager, notificationTag(address))
                    cancelNotificationByTag(notificationManager, legacyIslandNotificationTag(address))
                    lastNotificationIslandStyle.remove(address)
                    lastNotificationDevice = null
                    lastNotificationBatteryParams = null
                }
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel Pod Notification!", e)
            }
        }


        hookConstructorAfter(findConstructorByParamCount("com.android.bluetooth.ble.app.MiuiBluetoothNotification", 2)) {
            val context = getObjectField(instance, "mContext") as Context
            notificationSettings = loadNotificationSettings(context)
            cacheNotificationSettings(context, notificationSettings)
            if (!notificationSettings.showConnectionNotification) {
                cancelAllPodsNotifications(context)
            }

                    val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(p0: Context?, p1: Intent?) {
                            if (p1?.action == "chen.action.oppopods.sendstrongtoast") {
                                val settings = effectiveNotificationSettings(p1, context)
                                if (!settings.showConnectionBatteryIsland) {
                                    Log.d("OppoPods", "Temporary battery island suppressed by settings")
                                    return
                                }
                                val batteryParams = p1.getParcelableExtra(
                                    "batteryParams",
                                    BatteryParams::class.java
                                ) ?: return
                                FocusIslandUtil.showBatteryIsland(context, batteryParams)
                            } else if (p1?.action == "chen.action.oppopods.updatepodsnotification") {
                                val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                                val settings = effectiveNotificationSettings(p1, context)
                                if (settings.showConnectionNotification && batteryParams != null) {
                                    createPodsNotification(
                                        device,
                                        context,
                                        batteryParams,
                                        settings.showNotificationAsIsland,
                                        p1.getBooleanExtra(OppoPodsAction.EXTRA_RFCOMM_CONNECTED, true)
                                    )
                                } else if (device != null) {
                                    cancelNotification(device, context)
                                } else {
                                    cancelAllPodsNotifications(context)
                                }
                            } else if (p1?.action == "chen.action.oppopods.cancelpodsnotification") {
                                val device = p1.getParcelableExtra(
                                    "device",
                                    BluetoothDevice::class.java
                                ) ?: return
                                cancelNotification(device, context)
                            } else if (p1?.action == OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
                                // 同步耳机实际 ANC 状态到本地缓存，确保下次循环切换时状态准确
                                localAncMode = p1.getIntExtra("status", 1)
                            } else if (p1?.action == OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED) {
                                // 接收来自 App 端设置页面的 Adaptive 模式开关状态变更，无需本地动作
                                // cycle ANC 时通过 prefs bridge 实时读取偏好，此广播仅确保通知已送达
                                val adaptiveEnabled = p1.getBooleanExtra("enabled", true)
                                // 若关闭 Adaptive 且本地缓存的当前模式为 Adaptive，重置为降噪模式
                                if (!adaptiveEnabled && localAncMode == 4) {
                                    localAncMode = 2
                                }
                            } else if (p1?.action == OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED) {
                                syncNotificationSettings(context, p1)
                                val lastDevice = lastNotificationDevice
                                val lastBatteryParams = lastNotificationBatteryParams
                                if (!notificationSettings.showConnectionNotification) {
                                    if (lastDevice != null) {
                                        cancelNotification(lastDevice, context)
                                    } else {
                                        cancelAllPodsNotifications(context)
                                    }
                                } else if (lastDevice != null && lastBatteryParams != null) {
                                    createPodsNotification(
                                        lastDevice,
                                        context,
                                        lastBatteryParams,
                                        notificationSettings.showNotificationAsIsland,
                                        lastNotificationRfcommConnected
                                    )
                                }
                            } else if (p1?.action == OppoPodsAction.ACTION_CYCLE_ANC) {
                                // 循环切换降噪模式：读取Adaptive模式偏好，关闭时跳过Adaptive仅三模式循环
                                // 使用 prefs bridge 读取与 App 端同一 SharedPreferences 文件，确保状态同步
                                val adaptiveEnabled = prefs.getBoolean("adaptive_mode", true)
                                localAncMode = when (localAncMode) {
                                    2 -> if (adaptiveEnabled) 4 else 3  // NC → Adaptive（若启用）或 Transparency
                                    4 -> 3  // Adaptive → Transparency
                                    3 -> 1  // Transparency → OFF
                                    else -> 2  // OFF → NC
                                }
                                Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
                                    setPackage("com.android.bluetooth")
                                    putExtra("status", localAncMode)
                                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                    p0?.sendBroadcast(this)
                                }
                            }
                        }
                    }

                    val intentFilter = IntentFilter("chen.action.oppopods.sendstrongtoast")
                    intentFilter.addAction("chen.action.oppopods.updatepodsnotification")
                    intentFilter.addAction("chen.action.oppopods.cancelpodsnotification")
                    intentFilter.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
                    // 监听耳机实际 ANC 状态变更广播，保持 localAncMode 与 RfcommController 同步
                    intentFilter.addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
                    // 监听 Adaptive 模式开关状态变更广播，确保跨进程实时同步
                    intentFilter.addAction(OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED)
                    intentFilter.addAction(OppoPodsAction.ACTION_NOTIFICATION_SETTINGS_CHANGED)
                    context.registerReceiver(broadcastReceiver, intentFilter,
                        Context.RECEIVER_EXPORTED)
        }
    }
}
