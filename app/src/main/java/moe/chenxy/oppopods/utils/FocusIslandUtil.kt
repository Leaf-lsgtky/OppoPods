package moe.chenxy.oppopods.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import org.json.JSONObject

@SuppressLint("WrongConstant")
object FocusIslandUtil {
    private const val TAG = "OppoPods-FocusIsland"
    private const val CHANNEL_ID = "oppopods_focus_island"
    private const val CHANNEL_NAME = "OppoPods Battery"
    private const val NOTIFICATION_ID = 10086
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"
    private const val ISLAND_TIMEOUT_SECONDS = 3
    private const val DISMISS_DELAY_MS = 4000L

    fun showBatteryIsland(context: Context, batteryParams: BatteryParams): Boolean {
        try {
            val leftConnected = batteryParams.left?.isConnected == true
            val rightConnected = batteryParams.right?.isConnected == true

            // Need at least one ear connected
            if (!leftConnected && !rightConnected) return false

            val leftText = if (leftConnected) "${batteryParams.left!!.battery}" else "-"
            val rightText = if (rightConnected) "${batteryParams.right!!.battery}" else "-"

            // 从模块 APK 加载耳机图片为 Bitmap，避免跨包资源引用问题
            val moduleContext = context.createPackageContext(
                MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY
            )
            val leftBitmap = BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_left)
            val rightBitmap = BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_right)

            if (leftBitmap == null || rightBitmap == null) {
                Log.e(TAG, "Failed to decode earphone icon bitmaps")
                return false
            }

            // 使用 createWithBitmap 直接嵌入图片数据，SystemUI 无需再访问模块资源
            val leftIcon = Icon.createWithBitmap(leftBitmap)
            val rightIcon = Icon.createWithBitmap(rightBitmap)

            val json = buildIslandJson(leftText, rightText)

            val picsBundle = Bundle().apply {
                putParcelable("miui.focus.pic_left", leftIcon)
                putParcelable("miui.focus.pic_right", rightIcon)
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )

            val contentParts = mutableListOf<String>()
            if (leftConnected) contentParts.add("L: ${batteryParams.left!!.battery}%")
            if (rightConnected) contentParts.add("R: ${batteryParams.right!!.battery}%")

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("OppoPods")
                .setContentText(contentParts.joinToString("  "))
                .addExtras(Bundle().apply {
                    putString("miui.focus.param", json)
                    putBundle("miui.focus.pics", picsBundle)
                })
                .build()

            nm.notify(NOTIFICATION_ID, notification)

            Handler(Looper.getMainLooper()).postDelayed({
                try { nm.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
            }, DISMISS_DELAY_MS)

            Log.d(TAG, "Focus Island shown: L=$leftText% R=$rightText%")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Focus Island", e)
            return false
        }
    }

    private fun buildIslandJson(leftText: String, rightText: String): String {
        return JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("protocol", 3)
                put("enableFloat", true)
                put("updatable", true)
                put("ticker", "OppoPods")
                put("isShowNotification", false)
                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_left")
                            })
                            put("textInfo", JSONObject().apply {
                                put("title", leftText)
                                put("content", "%")
                            })
                        })
                        put("imageTextInfoRight", JSONObject().apply {
                            put("type", 2)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", "miui.focus.pic_right")
                            })
                            put("textInfo", JSONObject().apply {
                                put("title", rightText)
                                put("content", "%")
                            })
                        })
                    })
                })
            })
        }.toString()
    }
}
