package moe.chenxy.oppopods.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import top.yukonga.miuix.kmp.basic.Box
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun BatteryIcon(batteryLevel: Int, isCharging: Boolean, isDarkMode: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(50.dp)
            .height(20.dp)
            .border(1.dp, if (isDarkMode) Color.White else Color.DarkGray, RoundedCornerShape(5.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
            val batteryLevelWidth = size.width * (batteryLevel / 100f)
            val batteryColor = when {
                isCharging -> Color(0xFF34C759)
                batteryLevel > 30 -> if (isDarkMode) Color.LightGray else Color.Gray
                else -> Color(0xFFFF3B30)
            }

            drawRoundRect(
                color = batteryColor,
                size = size.copy(width = batteryLevelWidth, height = 16.dp.roundToPx().toFloat()),
                cornerRadius = CornerRadius(3.dp.roundToPx().toFloat(), 3.dp.roundToPx().toFloat()),
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
        }

        Canvas(modifier = Modifier.align(Alignment.CenterEnd)) {
            drawRoundRect(
                color = if (isDarkMode) Color.White else Color.DarkGray,
                topLeft = androidx.compose.ui.geometry.Offset(x = 1f, y = -1.dp.roundToPx().toFloat()),
                cornerRadius = CornerRadius(2.dp.roundToPx().toFloat(), 2.dp.roundToPx().toFloat()),
                size = androidx.compose.ui.geometry.Size(2.dp.roundToPx().toFloat(), 4.dp.roundToPx().toFloat()),
            )
        }
    }
}

@SuppressLint("ResourceType")
@Composable
fun Battery(isCharging: Boolean, isDarkMode: Boolean, level: Int) {
    Row(modifier = Modifier.width(100.dp).height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        BatteryIcon(level, isCharging, isDarkMode)
        Text("$level %", fontSize = 12.sp)
    }
}

@Composable
fun PodStatus(batteryParams: BatteryParams, modifier: Modifier = Modifier) {
    val currentDarkMode = isSystemInDarkTheme()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Left ear
        AnimatedVisibility(batteryParams.left != null && batteryParams.left?.isConnected == true) {
            Row(
                modifier = Modifier.width(180.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.batt_left_pod), fontSize = 14.sp)
                Battery(batteryParams.left!!.isCharging, currentDarkMode, batteryParams.left!!.battery)
            }
        }

        // Right ear
        AnimatedVisibility(batteryParams.right != null && batteryParams.right?.isConnected == true) {
            Row(
                modifier = Modifier.width(180.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.batt_right_pod), fontSize = 14.sp)
                Battery(batteryParams.right!!.isCharging, currentDarkMode, batteryParams.right!!.battery)
            }
        }

        // Case (may not be present when case is closed with pods out)
        AnimatedVisibility(batteryParams.case != null && batteryParams.case?.isConnected == true) {
            Row(
                modifier = Modifier.width(180.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.pod_case), fontSize = 14.sp)
                Battery(batteryParams.case!!.isCharging, currentDarkMode, batteryParams.case!!.battery)
            }
        }
    }
}
