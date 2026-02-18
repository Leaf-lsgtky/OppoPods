package moe.chenxy.oppopods.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    debugPadding: MutableState<Float> = mutableStateOf(26f)
) {
    val context = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "OppoPods Logo",
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground)
                    )
                    Text(
                        text = "OppoPods",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp
                    )
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }

            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.based_on),
                    summary = "HyperPods by Art_Chen"
                )
                BasicComponent(
                    title = "Github",
                    summary = "https://github.com/Art-Chen/HyperPods",
                    onClick = {
                        Intent(Intent.ACTION_VIEW).apply {
                            this.data = Uri.parse("https://github.com/Art-Chen/HyperPods")
                            context.startActivity(this)
                        }
                    }
                )
            }

            // Debug: TopAppBar padding adjustment
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = "Debug: horizontalPadding",
                    summary = "%.0f dp  |  %.1f%% of %ddp screen width".format(
                        debugPadding.value,
                        debugPadding.value / screenWidthDp * 100,
                        screenWidthDp
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "-5",
                        onClick = { debugPadding.value = (debugPadding.value - 5f).coerceAtLeast(0f) }
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "-1",
                        onClick = { debugPadding.value = (debugPadding.value - 1f).coerceAtLeast(0f) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "%.0f".format(debugPadding.value),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = "+1",
                        onClick = { debugPadding.value = (debugPadding.value + 1f).coerceAtMost(60f) }
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "+5",
                        onClick = { debugPadding.value = (debugPadding.value + 5f).coerceAtMost(60f) }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        text = "16",
                        onClick = { debugPadding.value = 16f }
                    )
                    TextButton(
                        text = "20",
                        onClick = { debugPadding.value = 20f }
                    )
                    TextButton(
                        text = "26 (default)",
                        onClick = { debugPadding.value = 26f }
                    )
                    TextButton(
                        text = "32",
                        onClick = { debugPadding.value = 32f }
                    )
                }
                BasicComponent(
                    title = "Icon first pixel from edge",
                    summary = "~%.0f dp (padding %.0f + IconButton internal ~8dp)  |  ~%.1f%%".format(
                        debugPadding.value + 8f,
                        debugPadding.value,
                        (debugPadding.value + 8f) / screenWidthDp * 100
                    )
                )
            }
        }
    }
}
