package moe.chenxy.oppopods.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xzakota.hyper.notification.focus.FocusNotification
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams

@SuppressLint("WrongConstant", "MissingPermission", "NotificationPermission")
object FocusIslandUtil {
    private const val TAG = "OppoPods-FocusIsland"
    private const val CHANNEL_ID = "oppopods_focus_island"
    private const val CHANNEL_NAME = "OppoPods Battery"
    private const val NOTIFICATION_ID = 10086
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"
    private const val ISLAND_TIMEOUT_SECONDS = 3
    private const val DISMISS_DELAY_MS = 4000L

    fun showBatteryIsland(
        context: Context,
        batteryParams: BatteryParams
    ): Boolean {
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
            // 优先用用户自定义的岛图（经 ContentProvider 跨进程读取）；缺省回退模块内置资源。
            // 内置资源按名字解析（而非编译期 R 常量），避免模块更新后资源 ID 移位取到错图。
            val leftBitmap = loadCustomBitmap(context, PodImageSlot.ISLAND_LEFT)
                ?: moduleContext.resources.getIdentifier("img_left", "drawable", MODULE_PACKAGE)
                    .takeIf { it != 0 }?.let { BitmapFactory.decodeResource(moduleContext.resources, it) }
            val rightBitmap = loadCustomBitmap(context, PodImageSlot.ISLAND_RIGHT)
                ?: moduleContext.resources.getIdentifier("img_right", "drawable", MODULE_PACKAGE)
                    .takeIf { it != 0 }?.let { BitmapFactory.decodeResource(moduleContext.resources, it) }

            if (leftBitmap == null || rightBitmap == null) {
                Log.e(TAG, "Failed to decode earphone icon bitmaps")
                return false
            }

            // 使用 createWithBitmap 直接嵌入图片数据，SystemUI 无需再访问模块资源
            val leftIcon = Icon.createWithBitmap(leftBitmap)
            val rightIcon = Icon.createWithBitmap(rightBitmap)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setAllowBubbles(true)
                }
            )

            val contentParts = mutableListOf<String>()
            if (leftConnected) contentParts.add("L: ${batteryParams.left!!.battery}%")
            if (rightConnected) contentParts.add("R: ${batteryParams.right!!.battery}%")
            val contentText = contentParts.joinToString("  ")

            val extras = FocusNotification.buildV3 {
                val picLeft = createPicture("key_pic_left", leftIcon)
                val picRight = createPicture("key_pic_right", rightIcon)

                enableFloat = true
                ticker = "OppoPods"
                tickerPic = picLeft

                isShowNotification = false
                island {
                    islandProperty = 1
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picLeft
                            }
                            textInfo {
                                title = leftText
                                content = "%"
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            picInfo {
                                type = 1
                                pic = picRight
                            }
                            textInfo {
                                title = rightText
                                content = "%"
                            }
                        }
                    }
                    shareData {
                        title = "OppoPods"
                        content = contentText
                        shareContent = contentText
                    }
                }
            } ?: return false

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("OppoPods")
                .setContentText(contentText)
                .setTicker("OppoPods")
                .addExtras(extras)
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

    /** 经 ContentProvider 读取用户自定义岛图（跨进程）；未设置则返回 null 由调用方回退内置资源。 */
    private fun loadCustomBitmap(context: Context, slot: PodImageSlot): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(PodImageStore.uri(slot))
                ?.use { BitmapFactory.decodeStream(it) }
        }.onFailure { Log.w(TAG, "loadCustomBitmap failed slot=${slot.key}", it) }.getOrNull()
    }
}
