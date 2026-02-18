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
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.constructor
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.oppopods.utils.FocusIslandUtil
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.cancelAsUser
import moe.chenxy.oppopods.utils.SystemApisUtils.notifyAsUser
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : YukiBaseHooker() {

    override fun onHook() {

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        @SuppressLint("WrongConstant")
        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            val ic_headset_notification = context.resources.getIdentifier("ic_headset_notification", "drawable", "com.xiaomi.bluetooth")

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

                val content: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "BTHeadset$address",
                        alias,
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val action = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592)
                )
                val bundle2 = Bundle()
                bundle2.putBoolean("miui.showAction", true)
                bundle2.putParcelable(
                    "miui.appIcon",
                    Icon.createWithResource(context, ic_headset_notification)
                )
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent("chen.action.oppopods.show_pods_ui").apply {
                        putExtra("show_popup", true)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address").setSmallIcon(
                        android.R.drawable.stat_sys_data_bluetooth
                    ).setWhen(0L).setTicker(alias).setDefaults(-1).setContentTitle(alias)
                        .setContentText(content)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice)).setColor(
                            context.getColor(system_notification_accent_color)
                        ).setExtras(bundle2).addAction(action).setVisibility(Notification.VISIBILITY_PUBLIC).build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    notificationManager.cancelAsUser("BTHeadset$address", 10003, SystemApisUtils.getUserAllUserHandle())
                }
            } catch (e: Exception) {
                Log.e("OppoPods", "Failed to cancel Pod Notification!", e)
            }
        }


        "com.android.bluetooth.ble.app.MiuiBluetoothNotification".toClass().apply {
            constructor {
                paramCount = 2
            }.hook {
                after {
                    val context = XposedHelpers.getObjectField(this.instance, "mContext") as Context

                    val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(p0: Context?, p1: Intent?) {
                            if (p1?.action == "chen.action.oppopods.sendstrongtoast") {
                                val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)!!
                                // Use Focus Island (HyperOS 3+) for battery display
                                FocusIslandUtil.showBatteryIsland(context, batteryParams)
                            } else if (p1?.action == "chen.action.oppopods.updatepodsnotification") {
                                val batteryParams = p1.getParcelableExtra<BatteryParams>("batteryParams", BatteryParams::class.java)
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                                createPodsNotification(device, context, batteryParams!!)
                            } else if (p1?.action == "chen.action.oppopods.cancelpodsnotification") {
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) as BluetoothDevice
                                cancelNotification(device, context)
                            }
                        }
                    }

                    val intentFilter = IntentFilter("chen.action.oppopods.sendstrongtoast")
                    intentFilter.addAction("chen.action.oppopods.updatepodsnotification")
                    intentFilter.addAction("chen.action.oppopods.cancelpodsnotification")
                    context.registerReceiver(broadcastReceiver, intentFilter,
                        Context.RECEIVER_EXPORTED)
                }
            }
        }
    }
}
