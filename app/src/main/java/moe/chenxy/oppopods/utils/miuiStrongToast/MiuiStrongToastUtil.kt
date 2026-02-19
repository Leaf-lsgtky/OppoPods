package moe.chenxy.oppopods.utils.miuiStrongToast

import StringToastBundle
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.serialization.json.Json
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.SystemApisUtils.isHyperOS
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.IconParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.Left
import moe.chenxy.oppopods.utils.miuiStrongToast.data.Right
import moe.chenxy.oppopods.utils.miuiStrongToast.data.StringToastBean
import moe.chenxy.oppopods.utils.miuiStrongToast.data.TextParams

@SuppressLint("WrongConstant")
object MiuiStrongToastUtil {
    var lastPodsTimestamp = -1L

    fun showStringToast(context: Context, text: String?, colorType: Int) {
        if (!isHyperOS) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val textParams = TextParams(text, if (colorType == 1) Color.parseColor("#4CAF50") else Color.parseColor("#E53935"))
            val left = Left(textParams = textParams)
            val iconParams = IconParams(Category.DRAWABLE, FileType.SVG, "ic_launcher", 1)
            val right = Right(iconParams = iconParams)
            val stringToastBean = StringToastBean(left, right)
            val jsonStr = Json.encodeToString(StringToastBean.serializer(), stringToastBean)
            val bundle = StringToastBundle.Builder()
                .setPackageName(BuildConfig.APPLICATION_ID)
                .setStrongToastCategory(StrongToastCategory.TEXT_BITMAP_INTENT)
                .setTarget(null)
                .setParam(jsonStr)
                .onCreate()
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "strong_toast_action", bundle)
        } catch (e: Exception) {
            Log.e("OppoPods", "Failed to show HyperOS String Toast")
        }
    }

    fun showPodsBatteryToast(
        context: Context,
        leftVideoUri: Uri,
        rightVideoUri: Uri,
        lowBatteryThreshold: Int = 20,
        batteryParams: BatteryParams
    ) {
        if (!isHyperOS) return

        val leftConnected = batteryParams.left?.isConnected == true
        val rightConnected = batteryParams.right?.isConnected == true
        val left = batteryParams.left?.battery ?: 0
        val leftCharging = batteryParams.left?.isCharging == true
        val right = batteryParams.right?.battery ?: 0
        val rightCharging = batteryParams.right?.isCharging == true

        val leftText =
            TextParams(if (leftConnected) "$left %" else "", if (leftCharging) Color.GREEN else if (left <= lowBatteryThreshold) Color.RED else Color.WHITE)
        val leftVideo = IconParams(Category.RAW, FileType.MP4, leftVideoUri.toString(), 1)
        val rightText =
            TextParams(if (rightConnected) "$right %" else "", if (rightCharging) Color.GREEN else if (right <= lowBatteryThreshold) Color.RED else Color.WHITE)
        val rightVideo = IconParams(Category.RAW, FileType.MP4, rightVideoUri.toString(), 1)
        val l = Left(textParams = leftText, iconParams = leftVideo)
        val r = Right(textParams = rightText, iconParams = rightVideo)
        val stringToastBean = StringToastBean(l, r)
        val jsonStr = Json.encodeToString(StringToastBean.serializer(), stringToastBean)
        val bundle = StringToastBundle.Builder()
            .setPackageName("com.xiaomi.bluetooth")
            .setStrongToastCategory(StrongToastCategory.VIDEO_TEXT_TEXT_VIDEO)
            .setDuration(5000)
            .setTarget(null)
            .setParam(jsonStr)
            .onCreate()
        try {
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "strong_toast_action", bundle)
            lastPodsTimestamp = System.currentTimeMillis()
        } catch (_: Exception) {
            Log.e("OppoPods", "Failed to show Pods Battery Toast")
        }
    }

    fun showPodsBatteryToastByMiuiBt(
        context: Context,
        batteryParams: BatteryParams
    ) {
        val intent = Intent("chen.action.oppopods.sendstrongtoast")
        intent.putExtra("batteryParams", batteryParams)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    fun showPodsNotificationByMiuiBt(
        context: Context,
        batteryParams: BatteryParams,
        device: BluetoothDevice,
    ) {
        val intent = Intent("chen.action.oppopods.updatepodsnotification")
        intent.putExtra("batteryParams", batteryParams)
        intent.putExtra("device", device)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    fun cancelPodsNotificationByMiuiBt(
        context: Context,
        device: BluetoothDevice,
    ) {
        val intent = Intent("chen.action.oppopods.cancelpodsnotification")
        intent.putExtra("device", device)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    object Category {
        const val RAW = "raw"
        const val DRAWABLE = "drawable"
        const val FILE = "file"
        const val MIPMAP = "mipmap"
    }

    object FileType {
        const val MP4 = "mp4"
        const val PNG = "png"
        const val SVG = "svg"
    }

    object StrongToastCategory {
        const val VIDEO_TEXT = "video_text"
        const val VIDEO_BITMAP_INTENT = "video_bitmap_intent"
        const val TEXT_BITMAP = "text_bitmap"
        const val TEXT_BITMAP_INTENT = "text_bitmap_intent"
        const val VIDEO_TEXT_TEXT_VIDEO = "video_text_text_video"
    }
}
