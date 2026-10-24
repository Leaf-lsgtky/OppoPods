package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import moe.chenxy.oppopods.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 与 Miuix `TopAppBar` 同参的模糊顶栏。
 *
 * 模糊层是 TopAppBar 的兄弟节点，只采样调用方提供的内容 backdrop；不要把
 * `textureBlur` 加在包含顶栏的父节点上，否则会采样到顶栏自身并递归。
 */
@Composable
fun PageTopAppBar(
    title: String,
    largeTitle: String? = null,
    scrollBehavior: ScrollBehavior? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    color: Color = MiuixTheme.colorScheme.surface,
    backdrop: LayerBackdrop? = null,
    blurEnabled: Boolean = true,
) {
    val active = blurEnabled && backdrop != null
    val surface = MiuixTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxWidth()) {
        if (active) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurColors(blendColors = listOf(
                                BlendColorEntry(surface.copy(alpha = 0.87f)),
                            ),
                        ),
                    )
            )
        }
        if (rememberIsWideScreen()) {
            // 宽屏：侧边栏取代底栏后纵向空间紧张，标题栏固定不折叠。
            SmallTopAppBar(
                title = title,
                color = if (active) Color.Transparent else color,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        } else {
            TopAppBar(
                title = title,
                largeTitle = largeTitle ?: title,
                color = if (active) Color.Transparent else color,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
            )
        }
    }
}
