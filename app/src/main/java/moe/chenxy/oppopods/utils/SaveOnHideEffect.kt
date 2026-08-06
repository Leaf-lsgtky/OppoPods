package moe.chenxy.oppopods.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 监听可见性变化，自动管理"隐藏时关闭、显示时恢复"的状态保存逻辑。
 *
 * - visible 从 true→false：保存当前值，调用 onValueChange(hiddenValue)
 * - visible 从 false→true：恢复之前保存的值，调用 onValueChange(saved)
 * - 首次进入（prev == visible）时不做任何操作
 *
 * @param visible   当前可见性（通常由配置档的 xxxVisible 字段控制）
 * @param currentValue 当前状态值
 * @param hiddenValue  隐藏时要设置的值（通常为 false / 0 / OFF 等）
 * @param onValueChange 设置值的回调（需同时更新本地状态 + 广播到 hook 进程）
 */
@Composable
fun <T> SaveOnHideEffect(
    visible: Boolean,
    currentValue: T,
    hiddenValue: T,
    onValueChange: (T) -> Unit
) {
    var saved by remember { mutableStateOf<T?>(null) }
    var prevVisible by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (prevVisible && !visible) {
            // 可见 → 隐藏：保存当前值，关闭功能
            if (currentValue != hiddenValue) {
                saved = currentValue
                onValueChange(hiddenValue)
            }
        } else if (!prevVisible && visible) {
            // 隐藏 → 可见：恢复之前保存的值
            saved?.let { onValueChange(it) }
            saved = null
        }
        prevVisible = visible
    }
}
