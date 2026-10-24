package moe.chenxy.oppopods.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.CalibrationState
import moe.chenxy.oppopods.pods.FitQuality
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val FitGoodColor = Color(0xFF2FB344)
private val FitFairColor = Color(0xFFE79B24)
private val FitPoorColor = Color(0xFFE1473F)

/**
 * 全屏高度的贴合度检测抽屉（OverlayBottomSheet）。
 * 对齐欢律官方流程与布局规范。
 */
@Composable
fun FitTestSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    state: CalibrationState,
    supported: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    OverlayBottomSheet(
        show = show,
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = onDismissRequest,
        startAction = {
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        },
        title = stringResource(R.string.fit_detection_title),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight)
                .statusBarsPadding(),
        ) {
            FitTestContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = state,
                supported = supported,
            )

            FitTestBottomBar(
                state = state,
                supported = supported,
                onStart = onStart,
                onCancel = onCancel,
            )
        }
    }
}

/**
 * 贴合度独立页面（二级路由回退兜底）。
 */
@Composable
fun FitTestPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: CalibrationState,
    supported: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        FitTestContent(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = state,
            supported = supported,
        )

        FitTestBottomBar(
            state = state,
            supported = supported,
            onStart = onStart,
            onCancel = onCancel,
        )
    }
}

/**
 * 贴合度核心视图内容（卡片、耳机图片、状态徽章与详细结果）。
 */
@Composable
private fun FitTestContent(
    modifier: Modifier = Modifier,
    state: CalibrationState,
    supported: Boolean,
) {
    val busy = state.stage == CalibrationState.STAGE_CHECKING_WEAR ||
            state.stage == CalibrationState.STAGE_FITTING
    val showResult = state.stage == CalibrationState.STAGE_FIT_RESULT &&
            state.fitLeft != null && state.fitRight != null

    val leftBitmap = rememberEarbudBitmap(PodImageSlot.ISLAND_LEFT)
    val rightBitmap = rememberEarbudBitmap(PodImageSlot.ISLAND_RIGHT)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部说明文案
        Text(
            text = if (showResult) {
                stringResource(R.string.fit_detection_summary)
            } else {
                stringResource(R.string.fit_detection_hint)
            },
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            lineHeight = 20.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )

        // 主展示卡片：左右耳机图 + 佩戴/贴合状态提示
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EarbudPair(
                    busy = busy,
                    leftStatus = if (showResult) state.fitLeft else null,
                    rightStatus = if (showResult) state.fitRight else null,
                    leftBitmap = leftBitmap,
                    rightBitmap = rightBitmap,
                )

                val promptText = when {
                    !supported -> stringResource(R.string.fit_detection_unsupported)
                    busy && state.stage == CalibrationState.STAGE_CHECKING_WEAR ->
                        stringResource(R.string.fit_detection_checking_wear)
                    busy -> stringResource(R.string.fit_detection_detecting) +
                            "\n" + stringResource(R.string.fit_detection_keep_on)
                    showResult -> when {
                        state.fitLeft == FitQuality.GOOD && state.fitRight == FitQuality.GOOD ->
                            stringResource(R.string.fit_detection_result_good_title)
                        state.fitLeft != FitQuality.GOOD && state.fitRight == FitQuality.GOOD ->
                            stringResource(R.string.fit_detection_result_adjust_left)
                        state.fitLeft == FitQuality.GOOD && state.fitRight != FitQuality.GOOD ->
                            stringResource(R.string.fit_detection_result_adjust_right)
                        else ->
                            stringResource(R.string.fit_detection_result_adjust_both)
                    }
                    state.stage == CalibrationState.STAGE_ERROR ->
                        state.message ?: stringResource(R.string.fit_detection_failed)
                    else -> null
                }

                if (promptText != null) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = promptText,
                        fontSize = 14.sp,
                        fontWeight = if (showResult) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = if (showResult) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // 测试完成后的详细条目卡片
        if (showResult) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    FitResultRow(
                        label = stringResource(R.string.fit_detection_left),
                        status = state.fitLeft,
                    )
                    Spacer(Modifier.height(12.dp))
                    FitResultRow(
                        label = stringResource(R.string.fit_detection_right),
                        status = state.fitRight,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 底部操作栏。遵循双按钮左右全宽、单按钮纵向全宽规范。
 */
@Composable
private fun FitTestBottomBar(
    state: CalibrationState,
    supported: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = state.stage == CalibrationState.STAGE_CHECKING_WEAR ||
            state.stage == CalibrationState.STAGE_FITTING
    val showResult = state.stage == CalibrationState.STAGE_FIT_RESULT &&
            state.fitLeft != null && state.fitRight != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
    ) {
        if (busy) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(
                text = if (showResult) stringResource(R.string.fit_detection_retry)
                else stringResource(R.string.fit_detection_start),
                onClick = onStart,
                enabled = supported,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun EarbudPair(
    busy: Boolean,
    leftStatus: Int?,
    rightStatus: Int?,
    leftBitmap: ImageBitmap?,
    rightBitmap: ImageBitmap?,
) {
    val transition = rememberInfiniteTransition(label = "fit_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fit_pulse_alpha",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EarbudColumn(
            bitmap = leftBitmap,
            fallbackRes = R.drawable.melody_ear_left,
            earLabel = "L",
            modifier = Modifier.alpha(if (busy) pulse else 1f),
            status = leftStatus,
        )
        EarbudColumn(
            bitmap = rightBitmap,
            fallbackRes = R.drawable.melody_ear_right,
            earLabel = "R",
            modifier = Modifier.alpha(if (busy) pulse else 1f),
            status = rightStatus,
        )
    }
}

@Composable
private fun EarbudColumn(
    bitmap: ImageBitmap?,
    fallbackRes: Int,
    earLabel: String,
    modifier: Modifier = Modifier,
    status: Int?,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 124.dp, height = 184.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(width = 108.dp, height = 168.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Image(
                    painter = painterResource(fallbackRes),
                    contentDescription = null,
                    modifier = Modifier.size(width = 108.dp, height = 168.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (status != null) {
            // 测试结果药丸徽章
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(fitColor(status).copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(fitLabel(status)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = fitColor(status),
                )
            }
        } else {
            // 初始未测时的 L / R 标识圈
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = earLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun FitResultRow(label: String, status: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(fitColor(status)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(fitLabel(status)),
            fontSize = 14.sp,
            color = fitColor(status),
        )
    }
}

@Composable
private fun rememberEarbudBitmap(slot: PodImageSlot): ImageBitmap? {
    val context = LocalContext.current
    val file = remember(context, slot) { PodImageStore.resolvedFile(context, slot) }
    return remember(file?.lastModified()) {
        file?.takeIf { it.isFile && it.length() > 0 }?.let {
            runCatching {
                BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap()
            }.getOrNull()
        }
    }
}

private fun fitLabel(status: Int): Int = when (status) {
    FitQuality.GOOD -> R.string.fit_detection_good
    FitQuality.POOR -> R.string.fit_detection_poor
    else -> R.string.fit_detection_fair
}

private fun fitColor(status: Int): Color = when (status) {
    FitQuality.GOOD -> FitGoodColor
    FitQuality.POOR -> FitPoorColor
    else -> FitFairColor
}
