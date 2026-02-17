package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun PodDetailPage(
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                modifier = Modifier.padding(12.dp)
            ) {
                PodStatus(batteryParams, modifier = Modifier.padding(12.dp))
            }

            AncSwitch(ancMode, onAncModeChange)
        }
    }
}
