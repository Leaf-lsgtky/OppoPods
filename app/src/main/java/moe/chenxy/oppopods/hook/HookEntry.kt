package moe.chenxy.oppopods.hook

import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    private var activeHook: HookContext? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        loadHookForPackage(param.packageName, param.defaultClassLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        activeHook?.onHotReloading()
        detach()
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val oldHooks = param.oldHookHandles
        val classLoader = oldHooks.firstOrNull()?.executable?.declaringClass?.classLoader
        if (classLoader == null) {
            Log.w(TAG, "Hot reload skipped: no target class loader is available")
            return
        }
        // HotReloadedParam exposes the process name (for example com.milink.service:ui),
        // whereas hook selection is keyed by the owning package. Without this normalization a
        // module update silently drops every hook in secondary processes.
        val packageName = param.processName.substringBefore(':')
        Log.d(TAG, "Hot reload package=$packageName process=${param.processName}")
        loadHookForPackage(packageName, classLoader)
        val activeIds = activeHook?.hookIds().orEmpty()
        oldHooks.filter { it.id !in activeIds }.forEach(HookHandle::unhook)
    }

    private fun loadHookForPackage(packageName: String, classLoader: ClassLoader) {
        val hook = when (packageName) {
            "com.android.bluetooth" -> HeadsetStateDispatcher
            "com.milink.service" -> MiLinkServiceHook
            "com.xiaomi.bluetooth" -> MiBluetoothToastHook
            "com.android.settings" -> SettingsHeadsetHook
            else -> return
        }
        loadHook(hook, classLoader)
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader) {
        hook.module = this
        hook.appClassLoader = classLoader
        hook.prefs = getRemotePreferences("oppopods_settings")
        hook.onHook()
        activeHook = hook
    }

    private companion object {
        const val TAG = "OppoPods-HookEntry"
    }
}
