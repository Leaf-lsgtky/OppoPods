package moe.chenxy.oppopods.pods

import android.content.Context
import android.content.SharedPreferences

/**
 * 设备外观与颜色持久化存储（对齐 heymelody-windows 的 device-appearance.json 与官方 Room 数据库）。
 *
 * 解决耳机断连或应用进程重启后颜色状态丢失、导致云端素材回退到错误配色的问题。
 */
object DeviceAppearanceStore {

    private const val PREFS_NAME = "oppopods_settings"
    private const val PREFIX_COLOR_ADDR = "device_color_addr_"
    private const val PREFIX_COLOR_PID = "device_color_pid_"
    private const val KEY_LAST_COLOR = "last_connected_device_color"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeAddress(address: String): String =
        address.replace(":", "").uppercase()

    /**
     * 获取指定设备保存的协议颜色。
     * 优先级：MAC 地址 > 型号 ID > 上次连接颜色。
     */
    fun getColor(context: Context, address: String? = null, productId: String? = null): Int? {
        val sp = prefs(context)
        if (!address.isNullOrBlank()) {
            val byAddr = sp.getInt(PREFIX_COLOR_ADDR + normalizeAddress(address), -1)
            if (byAddr in 0..255) return byAddr
        }
        if (!productId.isNullOrBlank()) {
            val byPid = sp.getInt(PREFIX_COLOR_PID + productId.uppercase(), -1)
            if (byPid in 0..255) return byPid
        }
        val last = sp.getInt(KEY_LAST_COLOR, -1)
        return if (last in 0..255) last else null
    }

    /**
     * 持久化保存设备颜色。
     */
    fun saveColor(
        context: Context,
        address: String? = null,
        productId: String? = null,
        color: Int,
    ) {
        if (color !in 0..255) return
        val editor = prefs(context).edit()
        editor.putInt(KEY_LAST_COLOR, color)
        if (!address.isNullOrBlank()) {
            editor.putInt(PREFIX_COLOR_ADDR + normalizeAddress(address), color)
        }
        if (!productId.isNullOrBlank()) {
            editor.putInt(PREFIX_COLOR_PID + productId.uppercase(), color)
        }
        editor.apply()
    }
}
