package moe.chenxy.oppopods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.ui.AppTheme
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                PopupContent(
                    onMore = {
                        val prefs = getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE)
                        if (prefs.getBoolean("open_heytap", false)) {
                            val intent = packageManager.getLaunchIntentForPackage("com.heytap.headset")
                            if (intent != null) {
                                startActivity(intent)
                            }
                        } else {
                            startActivity(Intent(this@PopupActivity, MainActivity::class.java))
                        }
                        finish()
                    },
                    onDone = { finish() }
                )
            }
        }
    }
}

@Composable
private fun PopupContent(onMore: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(true) }
    val isDarkMode = isSystemInDarkTheme()

    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val gameMode = remember { mutableStateOf(false) }
    val deviceName = remember { mutableStateOf("") }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            else -> NoiseControlMode.OFF
                        }
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryParams.value =
                            p1.getParcelableExtra("status", BatteryParams::class.java)!!
                    }
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        deviceName.value = p1.getStringExtra("device_name") ?: ""
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        onDone()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT))

        onDispose {
            try { context.unregisterReceiver(broadcastReceiver) } catch (_: Exception) {}
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", status)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        gameMode.value = enabled
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            context.sendBroadcast(this)
        }
    }

    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)

    Scaffold(containerColor = Color.Transparent) { _ ->
        SuperDialog(
            title = deviceName.value.ifEmpty { stringResource(R.string.app_name) },
            show = showDialog,
            backgroundColor = dialogBgColor,
            onDismissRequest = {
                showDialog.value = false
            },
            onDismissFinished = {
                onDone()
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    PodStatus(
                        batteryParams.value,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    AncSwitch(ancMode.value, onAncModeChange = { setAncMode(it) })
                }
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperSwitch(
                        title = stringResource(R.string.game_mode),
                        summary = stringResource(R.string.game_mode_summary),
                        checked = gameMode.value,
                        onCheckedChange = { setGameMode(it) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.more),
                        onClick = onMore,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.done),
                        onClick = {
                            showDialog.value = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
