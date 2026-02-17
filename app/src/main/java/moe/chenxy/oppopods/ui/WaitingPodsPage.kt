package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun WaitingPodsPage() {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()) {
        Text(stringResource(R.string.waiting_for_pod))
    }
}
