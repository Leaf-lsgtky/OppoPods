package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun PodStatus(batteryParams: BatteryParams, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        BatteryColumn(
            label = stringResource(R.string.batt_left_pod),
            pod = batteryParams.left,
            modifier = Modifier.weight(1f)
        )
        BatteryColumn(
            label = stringResource(R.string.batt_right_pod),
            pod = batteryParams.right,
            modifier = Modifier.weight(1f)
        )
        BatteryColumn(
            label = stringResource(R.string.pod_case),
            pod = batteryParams.case,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BatteryColumn(label: String, pod: PodParams?, modifier: Modifier = Modifier) {
    val isConnected = pod != null && pod.isConnected
    val level = pod?.battery ?: 0

    // Track last known battery level for disconnected icon
    var lastKnownLevel by remember { mutableIntStateOf(100) }
    if (isConnected && level > 0) {
        lastKnownLevel = level
    }

    val displayLevel = if (isConnected) "$level%" else "-"
    val iconLevel = if (isConnected) level else lastKnownLevel
    val iconCharging = false // disconnected always shows non-charging icon

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.padding(horizontal = 8.dp)
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = displayLevel,
            fontSize = 13.sp,
            color = Color.Gray
        )
        Image(
            painter = painterResource(
                getBatteryIconRes(iconLevel, if (isConnected) pod?.isCharging == true else iconCharging)
            ),
            contentDescription = "$label $displayLevel",
            modifier = Modifier.size(24.dp)
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
