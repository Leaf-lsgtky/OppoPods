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
    private val activeHooks = mutableListOf<HookContext>()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        loadHooksForPackage(param.packageName, param.defaultClassLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        activeHooks.forEach { it.onHotReloading() }
        activeHooks.clear()
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
        loadHooksForPackage(packageName, classLoader)
        val activeIds = activeHooks.flatMap { it.hookIds() }
        oldHooks.filter { it.id !in activeIds }.forEach(HookHandle::unhook)
    }

    private fun loadHooksForPackage(packageName: String, classLoader: ClassLoader) {
        val hooks = when (packageName) {
            "com.android.bluetooth" -> listOf(HeadsetStateDispatcher)
            "com.milink.service" -> listOf(MiLinkServiceHook)
            "com.xiaomi.bluetooth" -> listOf(MiBluetoothToastHook, XiaomiBluetoothHeadsetIconGuard)
            "com.android.settings" -> listOf(SettingsDetailsHook)
            else -> return
        }
        hooks.forEach { loadHook(it, classLoader) }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader) {
        hook.module = this
        hook.appClassLoader = classLoader
        hook.prefs = getRemotePreferences("oppopods_settings")
        activeHooks.add(hook)
        hook.onHook()
    }

    private companion object {
        const val TAG = "OppoPods-HookEntry"
    }
}
