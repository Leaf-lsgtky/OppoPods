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
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey

@SuppressLint("WrongConstant", "MissingPermission", "NotificationPermission")
object FocusIslandUtil {
    private const val TAG = "OppoPods-FocusIsland"
    private const val CHANNEL_ID = "oppopods_focus_island"
    private const val CHANNEL_NAME = "OppoPods Battery"
    private const val NOTIFICATION_ID = 10086
    private const val MODULE_PACKAGE = "moe.chenxy.oppopods"

    fun showBatteryIsland(
        context: Context,
        batteryParams: BatteryParams,
        durationSeconds: Int = OppoPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
    ): Boolean {
        try {
            val islandDurationSeconds = durationSeconds.takeIf {
                it in OppoPodsPrefsKey.TEMPORARY_BATTERY_ISLAND_DURATION_SECOND_OPTIONS
            } ?: OppoPodsPrefsKey.DEFAULT_TEMPORARY_BATTERY_ISLAND_DURATION_SECONDS
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
            // 使用编译期资源 ID。release 的 resopt 会重命名资源 entry，按字符串
            // getIdentifier("img_left") 会得到 0；编译期引用会随资源表一起重写。
            val leftBitmap = loadCustomBitmap(context, PodImageSlot.ISLAND_LEFT)
                ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_left)
            val rightBitmap = loadCustomBitmap(context, PodImageSlot.ISLAND_RIGHT)
                ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_right)

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

            val extras = FocusNotification.buildV3 {
                val picLeft = createPicture("key_pic_left", leftIcon)
                val picRight = createPicture("key_pic_right", rightIcon)

                enableFloat = true
                ticker = "OppoPods"
                tickerPic = picLeft

                isShowNotification = false
                island {
                    islandProperty = 1
                    islandTimeout = islandDurationSeconds
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
                }
            } ?: return false

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("OppoPods")
                .setTicker("OppoPods")
                .addExtras(extras)
                .build()

            nm.notify(NOTIFICATION_ID, notification)

            Handler(Looper.getMainLooper()).postDelayed({
                try { nm.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
            }, islandDurationSeconds * 1000L)

            Log.d(
                TAG,
                "Focus Island shown: L=$leftText% R=$rightText%, duration=${islandDurationSeconds}s"
            )
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
