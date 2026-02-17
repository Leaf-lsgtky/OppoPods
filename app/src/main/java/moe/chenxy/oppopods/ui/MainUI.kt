package moe.chenxy.oppopods.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.MainActivity
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import top.yukonga.miuix.kmp.basic.HorizontalPager
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.Info
import top.yukonga.miuix.kmp.icon.icons.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Volatile
var restoreAncJob: Job? = null

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(FlowPreview::class)
@Composable
fun MainUI() {
    val topAppBarScrollBehavior0 = MiuixScrollBehavior(rememberTopAppBarState())
    val topAppBarScrollBehavior1 = MiuixScrollBehavior(rememberTopAppBarState())

    val topAppBarScrollBehaviorList = listOf(
        topAppBarScrollBehavior0, topAppBarScrollBehavior1
    )

    val pagerState = rememberPagerState(pageCount = { 2 })
    var targetPage by remember { mutableIntStateOf(pagerState.currentPage) }
    val coroutineScope = rememberCoroutineScope()

    val currentScrollBehavior = when (pagerState.currentPage) {
        0 -> topAppBarScrollBehaviorList[0]
        else -> topAppBarScrollBehaviorList[1]
    }

    val mainTitle = remember { mutableStateOf("") }
    val aboutTitle = stringResource(R.string.about)
    val currentTitle = when (pagerState.currentPage) {
        0 -> mainTitle.value
        else -> aboutTitle
    }

    val items = listOf(
        NavigationItem(stringResource(R.string.pod_info), MiuixIcons.Settings),
        NavigationItem(stringResource(R.string.about), MiuixIcons.Info),
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.debounce(150).collectLatest {
            targetPage = pagerState.currentPage
        }
    }
    val context = LocalContext.current

    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val canShowDetailPage = remember { mutableStateOf(false) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val init = remember { mutableStateOf(false) }

    val broadcastReceiver = object : BroadcastReceiver() {
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
                    restoreAncJob?.cancel()
                }

                OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                    batteryParams.value = p1.getParcelableExtra("status", BatteryParams::class.java)!!
                }

                OppoPodsAction.ACTION_PODS_CONNECTED -> {
                    val deviceName = p1.getStringExtra("device_name")
                    mainTitle.value = deviceName ?: ""
                    canShowDetailPage.value = true
                    Log.i("OppoPods", "pod connected deviceName: $deviceName")
                }

                OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                    mainTitle.value = ""
                    canShowDetailPage.value = false
                    if (p0 is MainActivity) {
                        p0.finish()
                    }
                }
            }
        }
    }

    if (!init.value) {
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            this.addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            this.addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            this.addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT))
        init.value = true
    }

    fun setAncMode(mode: NoiseControlMode) {
        if (restoreAncJob?.isActive == true) return
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            context.sendBroadcast(this)
        }
        restoreAncJob = CoroutineScope(Dispatchers.Default).launch {
            val oldAncMode = ancMode.value
            ancMode.value = mode
            delay(1000)
            ancMode.value = oldAncMode
        }
    }

    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = if (currentScrollBehavior.state.heightOffset > -1) Color.Transparent else MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (currentScrollBehavior.state.heightOffset > -1) 1f
                else lerp(1f, 0.67f, (currentScrollBehavior.state.heightOffset + 1) / -143f)
            )
        )
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BoxWithConstraints {
                if (maxWidth > 840.dp) {
                    SmallTopAppBar(
                        color = Color.Transparent,
                        title = currentTitle,
                        modifier = Modifier
                            .hazeChild(hazeState) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                            },
                        scrollBehavior = currentScrollBehavior
                    )
                } else {
                    TopAppBar(
                        color = Color.Transparent,
                        title = currentTitle,
                        scrollBehavior = currentScrollBehavior,
                        modifier = Modifier
                            .hazeChild(hazeState) {
                                style = hazeStyle
                                blurRadius = 25.dp
                                noiseFactor = 0f
                            }
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                color = Color.Transparent,
                modifier = Modifier
                    .hazeChild(hazeState) {
                        style = hazeStyle
                        blurRadius = 25.dp
                        noiseFactor = 0f
                    },
                items = items,
                selected = targetPage,
                onClick = { index ->
                    if (index in 0..1) {
                        targetPage = index
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                }
            )
        },
    ) { padding ->
        AppHorizontalPager(
            modifier = Modifier.imePadding().haze(state = hazeState),
            pagerState = pagerState,
            topAppBarScrollBehaviorList = topAppBarScrollBehaviorList,
            padding = padding,
            canShowDetailPage = canShowDetailPage.value,
            batteryParams = batteryParams.value,
            ancMode = ancMode.value,
            onAncModeChange = { setAncMode(it) },
        )
    }
}

@Composable
fun AppHorizontalPager(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    topAppBarScrollBehaviorList: List<ScrollBehavior>,
    padding: PaddingValues,
    canShowDetailPage: Boolean,
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit
) {
    HorizontalPager(
        modifier = modifier,
        pagerState = pagerState,
        pageContent = { page ->
            when (page) {
                0 -> Crossfade(canShowDetailPage, label = "MainUIShowDetailAnim") { value ->
                    if (value) {
                        PodDetailPage(
                            topAppBarScrollBehavior = topAppBarScrollBehaviorList[0],
                            padding = padding,
                            batteryParams = batteryParams,
                            ancMode = ancMode,
                            onAncModeChange = onAncModeChange
                        )
                    } else {
                        WaitingPodsPage()
                    }
                }

                1 -> AboutPage(
                    topAppBarScrollBehavior = topAppBarScrollBehaviorList[1],
                    padding = padding
                )
            }
        }
    )
}

@Composable
fun Dp.dpToPx() = with(LocalDensity.current) { this@dpToPx.toPx() }

@Composable
fun Int.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }

@Composable
fun Float.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }
