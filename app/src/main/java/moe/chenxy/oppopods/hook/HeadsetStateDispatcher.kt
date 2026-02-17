package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.ContextWrapper
import android.os.Handler
import android.util.Log
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import de.robv.android.xposed.XposedHelpers
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility

object HeadsetStateDispatcher : YukiBaseHooker() {

    override fun onHook() {
        "com.android.bluetooth.a2dp.A2dpService".toClass().apply {
            method {
                name = "handleConnectionStateChanged"
                paramCount = 3
            }.hook {
                after {
                    val currState = this.args[2] as Int
                    val fromState = this.args[1] as Int
                    val device = this.args[0] as BluetoothDevice?
                    val handler = XposedHelpers.getObjectField(this.instance, "mHandler") as Handler
                    if (device == null || currState == fromState) {
                        return@after
                    }
                    handler.post {
                        Log.d("OppoPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(device)}")
                        val context = this.instance as ContextWrapper
                        if (!isOppoPod(device)) return@post

                        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                        if (currState == BluetoothHeadset.STATE_CONNECTED) {
                            statusBarManager.setIconVisibility("wireless_headset", true)
                            RfcommController.connectPod(context, device, prefs)
                        } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                            statusBarManager.setIconVisibility("wireless_headset", false)
                            RfcommController.disconnectedPod(context, device)
                        }
                    }
                }
            }
        }
    }

    /**
     * Detect OPPO earphones by checking if the device name contains "oppo" (case insensitive).
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(device: BluetoothDevice): Boolean {
        val name = device.name ?: return false
        return name.contains("oppo", ignoreCase = true)
    }
}
