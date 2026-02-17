package moe.chenxy.oppopods.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun PodStatus(batteryParams: BatteryParams, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        AnimatedVisibility(batteryParams.left != null && batteryParams.left?.isConnected == true) {
            BatteryColumn(
                label = stringResource(R.string.batt_left_pod),
                level = batteryParams.left?.battery ?: 0,
                isCharging = batteryParams.left?.isCharging ?: false
            )
        }

        AnimatedVisibility(batteryParams.right != null && batteryParams.right?.isConnected == true) {
            BatteryColumn(
                label = stringResource(R.string.batt_right_pod),
                level = batteryParams.right?.battery ?: 0,
                isCharging = batteryParams.right?.isCharging ?: false
            )
        }

        AnimatedVisibility(batteryParams.case != null && batteryParams.case?.isConnected == true) {
            BatteryColumn(
                label = stringResource(R.string.pod_case),
                level = batteryParams.case?.battery ?: 0,
                isCharging = batteryParams.case?.isCharging ?: false
            )
        }
    }
}

@Composable
private fun BatteryColumn(label: String, level: Int, isCharging: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 14.sp)
        Text("$level%", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Image(
            painter = painterResource(getBatteryIconRes(level, isCharging)),
            contentDescription = "$label $level%",
            modifier = Modifier.size(32.dp)
        )
    }
}

private fun getBatteryIconRes(level: Int, isCharging: Boolean): Int {
    val index = when {
        level <= 20 -> 1
        level <= 40 -> 2
        level <= 60 -> 3
        level <= 80 -> 4
        else -> 5
    }
    return if (isCharging) {
        when (index) {
            1 -> R.drawable.charge_1
            2 -> R.drawable.charge_2
            3 -> R.drawable.charge_3
            4 -> R.drawable.charge_4
            else -> R.drawable.charge_5
        }
    } else {
        when (index) {
            1 -> R.drawable.common_1
            2 -> R.drawable.common_2
            3 -> R.drawable.common_3
            4 -> R.drawable.common_4
            else -> R.drawable.common_5
        }
    }
}
