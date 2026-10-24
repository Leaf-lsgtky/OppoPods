package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.chenxy.oppopods.ui.util.WideContentBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 二级页统一外壳。
 *
 * 关键点：模糊采样层只包住**内容**，不包顶栏本身。若把顶栏一起记录进 backdrop，
 * 顶栏上的 textureBlur 会采样到包含自己的层，形成递归并最终栈溢出。
 */
@Composable
fun SecondaryPageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    blurEnabled: Boolean = true,
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
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    }
                },
                actions = actions,
                backdrop = backdrop,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // 宽屏限宽居中；窄屏 sidePadding=0，行为与之前一致。
            // 与一级页一致：加在内容容器上，页面自己重建的 PaddingValues 也不会丢留白。
            WideContentBox { sidePadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = sidePadding)
                ) {
                    content(padding, scrollBehavior)
                }
            }
        }
    }
}
