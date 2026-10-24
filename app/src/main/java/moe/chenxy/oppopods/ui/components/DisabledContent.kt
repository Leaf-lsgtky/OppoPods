package moe.chenxy.oppopods.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 未连接时把整页内容置灰，并让其中的**选项**不可操作。
 *
 * 只拦点击、不拦拖动：用户仍然可以左右滑动切换 Tab、上下滚动浏览置灰的选项，
 * 只是点不动——这正是 MIUI「离线时展示上次设备的页面」的交互。
 *
 * 逐个子组件传 `enabled = false` 既容易漏（偏好行、滑块、开关、卡片各有自己的
 * 禁用语义），又会让同一页面出现「一半灰一半亮」的不一致观感。
 *
 * 实现要点：拖动超过 touchSlop 就判定为拖动，**不消费**任何事件，原样传给
 * 分页器 / 列表；只有「按下后没怎么移动就抬起」的点击，才在抬起那一帧把事件
 * 消费掉，子节点的 onClick 就收不到。
 */
@Composable
fun DisabledContent(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 0.4 仍能看清布局与文字层级，同时明显区别于可交互状态。
                .alpha(if (enabled) 1f else 0.4f)
        ) {
            content()
        }
        if (!enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            var isTap = true
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (isTap && event.changes.any {
                                        (it.position - down.position).getDistance() > touchSlop
                                    }
                                ) {
                                    isTap = false
                                }
                                if (event.changes.any { it.changedToUpIgnoreConsumed() }) {
                                    // 点击：吞掉抬起帧，子节点拿不到 onClick。
                                    // 拖动：不消费，让分页器与列表照常滑动。
                                    if (isTap) event.changes.forEach { it.consume() }
                                    break
                                }
                            }
                        }
                    }
            )
        }
    }
}
