package moe.chenxy.oppopods.ui.util

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 宽屏阈值：窗口宽度达到此值时启用侧边导航栏与内容居中（平板、横屏、展开态折叠屏）。 */
private val WideScreenMinWidth = 600.dp

/** 宽屏下内容的最大宽度，超出后两侧留白居中，避免卡片被过度拉伸。 */
val MaxContentWidth: Dp = 800.dp

/** 当前窗口是否达到宽屏尺寸。 */
@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { containerSize.width.toDp() >= WideScreenMinWidth }
}

/**
 * 宽屏内容居中容器：把单侧留白量回传给 [content]，由调用方加进自己滚动列表的
 * `contentPadding`。
 *
 * 列表本身保持全宽（滚动手势覆盖整屏、两侧没有滚动死区），只有内容被限制到
 * [MaxContentWidth]。窄屏留白为 0，等同无操作。
 */
@Composable
fun WideContentBox(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    val isWideScreen = rememberIsWideScreen()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sidePadding = if (isWideScreen) {
            ((maxWidth - MaxContentWidth) / 2).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        content(sidePadding)
    }
}
