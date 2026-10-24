package moe.chenxy.oppopods.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.ui.components.FloatingBottomBar
import moe.chenxy.oppopods.ui.components.FloatingBottomBarItem
import moe.chenxy.oppopods.ui.components.FloatingBottomBarMode
import moe.chenxy.oppopods.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage


/**
 * 三页外壳：主页 / 功能 / 设置。
 *
 * 与 HyperMusicCover 一致，外壳**不提供顶栏**。顶栏由每一页自己的
 * [moe.chenxy.oppopods.ui.components.PrimaryPageScaffold] 提供，所以左右滑动或
 * 切换分页时，顶栏和大标题会跟着页面一起移动、一起收起，而不是钉在原地。
 *
 * 底栏悬浮时，外壳只把它的高度作为 `extraBottomPadding` 交给页面，
 * 页面自己决定内容从哪里开始，避免两层 Scaffold 重复计算 inset。
 */
@Composable
fun MainShell(
    floatingNavbar: Boolean,
    liquidGlass: Boolean,
    blurEnabled: Boolean,
    // 宽屏侧边栏的展开态由调用方持久化。
    railExpanded: Boolean = false,
    onRailExpandedChange: (Boolean) -> Unit = {},
    homeContent: @Composable (extraBottomPadding: Dp, isActive: Boolean) -> Unit,
    featuresContent: @Composable (extraBottomPadding: Dp) -> Unit,
    settingsContent: @Composable (extraBottomPadding: Dp) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    var selectedTab by remember { mutableIntStateOf(0) }
    // 与 KernelSU 的 MainPagerState 同源：程序化翻页期间禁止把 pager 的当前页写回选中项。
    // 否则 1→3 的弹簧滚动途中会依次经过第 2 页，把指示器先拽到 2 再弹回 3。
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val wide = rememberIsWideScreen()
    val surface = MiuixTheme.colorScheme.surface
    val blurActive = blurEnabled
    // 底栏采样的是页面内容层；每页顶栏采样的是各自页面内的层。
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val barColor = if (blurActive) Color.Transparent else surface

    val titles = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.settings),
    )
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings)

    fun goToPage(index: Int) {
        if (index == selectedTab) return
        navJob?.cancel()
        selectedTab = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                if (wide) pagerState.scrollToPage(index) else pagerState.springAnimateToPage(index)
            } finally {
                // 只有仍然是当前这次导航时才收尾，避免被后一次点击的 job 覆盖掉状态。
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedTab = pagerState.currentPage
                    }
                }
            }
        }
    }

    // 只在用户自己滑动分页时同步选中项；程序化切换由 goToPage 独占。
    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedTab != pagerState.currentPage) {
            selectedTab = pagerState.currentPage
        }
    }
    // 返回键与点击 Tab 走同一条路径，行为一致（含同步与动画）。
    BackHandler(enabled = selectedTab != 0) {
        goToPage(0)
    }

    val bottomBar: @Composable (Dp) -> Unit = { navBarHeight ->
        if (floatingNavbar) {
            // 与 InstallerX 相同的三档映射：液态玻璃 / 普通模糊 / 纯实心。
            // 三档都是同一个胶囊组件，关掉液态玻璃不会退回 Miuix 的悬浮导航栏。
            val barMode = when {
                liquidGlass && blurActive -> FloatingBottomBarMode.LiquidGlass
                blurActive -> FloatingBottomBarMode.Blur
                else -> FloatingBottomBarMode.None
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                FloatingBottomBar(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp + navBarHeight),
                    selectedIndex = selectedTab,
                    onSelected = { goToPage(it) },
                    backdrop = backdrop,
                    tabsCount = titles.size,
                    mode = barMode,
                    tabMinWidth = 76.dp,
                ) { activateTab ->
                    titles.forEachIndexed { index, label ->
                        FloatingBottomBarItem(
                            selected = selectedTab == index,
                            onClick = { activateTab(index) },
                            // 与 KernelSU 一致：每格最小 76dp，由底栏的固定宽度兜住。
                            modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                        ) {
                            Icon(
                                imageVector = icons[index],
                                contentDescription = label,
                            )
                            Text(text = label, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (blurActive) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurColors(blendColors = listOf(
                                        BlendColorEntry(surface.copy(alpha = 0.87f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                NavigationBar(color = barColor) {
                    titles.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { goToPage(index) },
                            icon = icons[index],
                            label = label,
                        )
                    }
                }
            }
        }
    }

    val pagerContent: @Composable (Dp) -> Unit = { extraBottom ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 3 页全部驻留。留 1 页时，从第 1 页跳到第 3 页会把第 1 页
                    // （连同 Filament 引擎和已解析的 3D 模型）销毁掉，返回时要
                    // 重新加载——表现为每次跨页切换模型都重转一遍。
                    // KernelSU 同样是把所有页都留在组合里（4 页 → 3）。
                    beyondViewportPageCount = 2,
                    overscrollEffect = null,
                    userScrollEnabled = true,
                ) { page ->
                    when (page) {
                        // isActive 让不可见的页暂停渲染，避免后台白烧 GPU；
                        // 组件本身保持存活，不触发重建。
                        0 -> homeContent(extraBottom, page == pagerState.currentPage)
                        1 -> featuresContent(extraBottom)
                        else -> settingsContent(extraBottom)
                    }
                }
            }
        }
    }

    if (wide) {
        // 宽屏：可展开收起的侧边导航栏取代底栏，纵向空间全部让给内容。
        val railState = rememberNavigationRailState(
            initialValue = if (railExpanded) {
                NavigationRailValue.Expanded
            } else {
                NavigationRailValue.Collapsed
            },
        )
        LaunchedEffect(railState.currentValue) {
            if (railState.isExpanded != railExpanded) {
                onRailExpandedChange(railState.isExpanded)
            }
        }
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                state = railState,
                modifier = Modifier.fillMaxHeight(),
            ) {
                titles.forEachIndexed { index, label ->
                    NavigationRailItem(
                        selected = selectedTab == index,
                        onClick = { goToPage(index) },
                        icon = icons[index],
                        label = label,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // rail 已经吸收起始侧的刘海/导航栏 inset，末尾侧在这里补齐并标记为已消费。
                    .consumeWindowInsets(
                        WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Start),
                    )
                    .windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.End),
                    )
            ) {
                pagerContent(
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            }
        }
    } else {
        // 与 HyperMusicCover 一样，外层 Scaffold 只负责底栏，顶栏属于每一页。
        // 底栏实际高度从 innerPadding 读出来交给页面，避免用硬编码的高度去猜。
        Scaffold(
            bottomBar = {
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        bottomBar(
                            WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
                    }
                }
            },
        ) { innerPadding ->
            pagerContent(innerPadding.calculateBottomPadding())
        }
    }
}
