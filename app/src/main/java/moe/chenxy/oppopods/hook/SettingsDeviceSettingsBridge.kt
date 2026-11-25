package moe.chenxy.oppopods.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle

/**
 * Settings 17 / SDK 37 的原生模型适配器。
 * 所有模型、Flow 和 Unit 都由目标 ClassLoader 创建，不能混用模块打包的 Kotlin 类型。
 */
internal class SettingsDeviceSettingsBridge(private val classLoader: ClassLoader) {
    private fun host(name: String) = Class.forName(name, false, classLoader)
    private val base = "com.android.settingslib.bluetooth.devicesettings."
    private val shared = "${base}shared.model."
    private val connection = host("${base}data.repository.DeviceSettingServiceConnection")
    private val fragment = host("com.android.settings.bluetooth.BluetoothDetailsConfigurableFragment")
    private val mainFragment = host(
        "com.android.settings.bluetooth.ui.model.FragmentTypeModel\$DeviceDetailsMainFragment"
    )
    private val fragmentType = host("com.android.settings.bluetooth.ui.model.FragmentTypeModel")
    private val continuation = host("kotlin.coroutines.Continuation")
    private val layout = host("${shared}DeviceSettingLayout")
    private val appItemConstructor = host(
        "${shared}DeviceSettingConfigNodeModel\$Item\$AppProvidedItem"
    ).getDeclaredConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val layoutConstructor = layout.getDeclaredConstructor(List::class.java)
    private val layoutNodes = layout.getMethod("getNodes")
    private val toggleConstructor = host("${base}ToggleInfo").getDeclaredConstructor(
        String::class.java, Bitmap::class.java, Bundle::class.java
    ).apply { isAccessible = true }
    private val preferenceConstructor = host("${base}MultiTogglePreference").getDeclaredConstructor(
        String::class.java, List::class.java, Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Bundle::class.java
    ).apply { isAccessible = true }
    private val settingConstructor = host("${base}DeviceSetting").getDeclaredConstructor(
        Int::class.javaPrimitiveType, host("${base}DeviceSettingPreference"), Bundle::class.java
    ).apply { isAccessible = true }
    private val stateField = host("${base}MultiTogglePreferenceState").getDeclaredField("mState")
        .apply { isAccessible = true }
    private val createFlow = host("kotlinx.coroutines.flow.StateFlowKt")
        .getDeclaredMethod("MutableStateFlow", Any::class.java)
    private val setFlowValue = host("kotlinx.coroutines.flow.MutableStateFlow")
        .getMethod("setValue", Any::class.java)
    private val requestLayout = fragment.getDeclaredMethod("requestUpdateLayout", fragmentType)
    private val constructLayout = fragment.getDeclaredMethod("constructLayout", layout)
        .apply { isAccessible = true }
    private val mainFragmentInstance = mainFragment.getField("INSTANCE").get(null)
    private val toggleCache = mutableMapOf<Int, Any>()

    val unit: Any = host("kotlin.Unit").getField("INSTANCE").get(null)!!
    val getNodes = host("com.android.settings.bluetooth.ui.viewmodel.BluetoothDeviceDetailsViewModel")
        .getDeclaredMethod("getNodes", fragmentType, continuation).apply { isAccessible = true }
    val getDeviceSetting = connection.getDeclaredMethod("getDeviceSetting", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
    val updateDeviceSettings = connection.getDeclaredMethod(
        "updateDeviceSettings", Int::class.javaPrimitiveType,
        host("${base}DeviceSettingPreferenceState"), continuation
    ).apply { isAccessible = true }
    val onViewCreated = fragment.getDeclaredMethod(
        "onViewCreated", android.view.View::class.java, Bundle::class.java
    ).apply { isAccessible = true }

    fun isMainFragment(value: Any?): Boolean = mainFragment.isInstance(value)

    fun requestLayout(fragment: Any) {
        requestLayout.invoke(fragment, mainFragmentInstance)
    }

    fun rebindLayout(fragment: Any, original: Any) {
        // 相同节点布局的 equals 会短路重排；先恢复原布局，取消旧收集器，再绑定新 Flow。
        constructLayout.invoke(fragment, original)
        requestLayout(fragment)
    }

    fun layoutWithAnc(original: Any, showAnc: Boolean): Any {
        if (!showAnc) return original
        val nodes = (layoutNodes.invoke(original) as List<*>).filterNotNull().filterNot { node ->
            appItemConstructor.declaringClass.isInstance(node) &&
                callMethod(node, "getSettingId") == ANC_SETTING_ID
        }.toMutableList()
        // 保留 XML 的全部原生条目及顺序，在操作按钮前插入降噪，而非用自定义配置替换整个页面。
        val position = nodes.indexOfFirst { node ->
            runCatching { callMethod(node, "getPreferenceKey") == "action_buttons" }.getOrDefault(false)
        }.takeIf { it >= 0 } ?: nodes.size
        nodes.add(position, appItemConstructor.newInstance(ANC_SETTING_ID, false))
        return layoutConstructor.newInstance(nodes)
    }

    @Synchronized
    fun setting(state: SettingsDeviceState): Any {
        val toggles = state.toggleModes.map { mode ->
            toggleCache.getOrPut(mode) {
                val label = when (mode) {
                    3 -> "通透"
                    2 -> "降噪"
                    4 -> "自适应"
                    else -> "关闭"
                }
                toggleConstructor.newInstance(label, toggleIcon(mode), Bundle.EMPTY)
            }
        }
        val preference = preferenceConstructor.newInstance(
            "降噪", toggles, state.selectedIndex, state.canControlAnc, state.canControlAnc, Bundle.EMPTY
        )
        return settingConstructor.newInstance(ANC_SETTING_ID, preference, Bundle.EMPTY)
    }

    fun selection(state: Any?): Int? =
        state?.takeIf { stateField.declaringClass.isInstance(it) }?.let { stateField.getInt(it) }

    fun flow(initial: Any): Any = createFlow.invoke(null, initial)!!

    fun updateFlow(flow: Any, state: SettingsDeviceState) {
        setFlowValue.invoke(flow, setting(state))
    }

    private fun toggleIcon(mode: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(24f, 24f, 9f, paint)
        when (mode) {
            1 -> canvas.drawLine(10f, 10f, 38f, 38f, paint)
            3 -> {
                canvas.drawArc(3f, 3f, 45f, 45f, -55f, 110f, false, paint)
                canvas.drawArc(3f, 3f, 45f, 45f, 125f, 110f, false, paint)
            }
            4 -> {
                canvas.drawLine(24f, 3f, 24f, 9f, paint)
                canvas.drawLine(24f, 39f, 24f, 45f, paint)
                canvas.drawLine(3f, 24f, 9f, 24f, paint)
                canvas.drawLine(39f, 24f, 45f, 24f, paint)
            }
            else -> canvas.drawCircle(24f, 24f, 20f, paint)
        }
        return bitmap
    }

    companion object {
        const val ANC_SETTING_ID = 990001
    }
}
