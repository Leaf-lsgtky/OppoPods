package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.ui.util.WideContentBox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一级页（分页 Tab）外壳，对齐 HyperMusicCover 的 `PageScaffold`。
 *
 * 关键点：顶栏属于**页面自己**，而不是挂在分页器外面。这样左右滑动分页时，
 * 顶栏和大标题会跟着内容一起滚动/收起，而不是像固定顶栏那样纹丝不动。
 *
 * 模糊层同样只包住内容：backdrop 记录的是 Scaffold 的 body，顶栏是它的兄弟节点，
 * 因此 `textureBlur` 不会采样到自身。
 */
@Composable
fun PrimaryPageScaffold(
    title: String,
    blurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }

    Scaffold(
        topBar = {
            PageTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
                backdrop = if (blurEnabled) backdrop else null,
                blurEnabled = blurEnabled,
            )
        },
        // 竖直方向的 inset 由顶栏自己处理；底部留给外层 Scaffold 的底栏，
        // 通过 extraBottomPadding 传入，避免和分页器外壳重复计算。
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            // 宽屏把整块内容限宽居中。
            //
            // 这里直接给内容容器加水平 padding，而不是把留白塞进 contentPadding：
            // 各页面都自己重建 `PaddingValues(top, bottom)`（FeaturesPage、SettingsPage
            // 等），塞进去的 start/end 会被它们丢掉，只有直接透传 padding 的页面生效。
            // 加在容器上则对所有页面、以及叠加在内容之上的按钮都一次生效。
            WideContentBox { sidePadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = sidePadding)
                ) {
                    content(
                        PaddingValues(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding() + extraBottomPadding,
                        ),
                        scrollBehavior,
                    )
                }
            }
        }
    }
}
