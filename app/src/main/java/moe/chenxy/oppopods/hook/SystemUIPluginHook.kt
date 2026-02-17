package moe.chenxy.oppopods.hook

import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import de.robv.android.xposed.XposedHelpers


object SystemUIPluginHook : YukiBaseHooker() {
    override fun onHook() {
        var pluginLoaderClassLoader: ClassLoader? = null

        fun loadPluginHooker(hooker: YukiBaseHooker) {
            hooker.appClassLoader = pluginLoaderClassLoader
            loadHooker(hooker)
        }

        fun initPluginHook() {
            loadPluginHooker(DeviceCardHook)
        }

        // Load plugin hooker
        // get Classloader for plugin on Android U
        "com.android.systemui.shared.plugins.PluginInstance".toClass().method {
            name = "loadPlugin"
        }.hook {
            after {
                val pkgName = XposedHelpers.callMethod(this.instance, "getPackage")
                if (pkgName == "miui.systemui.plugin") {
                    val factory =
                        XposedHelpers.getObjectField(this.instance, "mPluginFactory")
                    val clsLoader = XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(
                            factory,
                            "mClassLoaderFactory"
                        ), "get"
                    ) as ClassLoader
                    if (pluginLoaderClassLoader != clsLoader) {
                        Log.i(
                            "OppoPods",
                            "[loadPlugin] initPluginHook"
                        )
                        pluginLoaderClassLoader = clsLoader
                        initPluginHook()
                    }
                }
            }
        }
    }
}
