package moe.chenxy.oppopods.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.CalibrationState
import moe.chenxy.oppopods.pods.FitQuality
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val FitGoodColor = Color(0xFF2FB344)
private val FitFairColor = Color(0xFFE79B24)
private val FitPoorColor = Color(0xFFE1473F)

/**
 * 耳塞贴合度检测。
 *
 * 检测流程由 [moe.chenxy.oppopods.pods.CalibrationEngine] 驱动：
 * 佩戴确认 → 0x0405 开始 → 事件 4 左右结果 → 0x0405 停止。
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
    val busy = state.stage == CalibrationState.STAGE_CHECKING_WEAR ||
            state.stage == CalibrationState.STAGE_FITTING
    val showResult = state.stage == CalibrationState.STAGE_FIT_RESULT &&
            state.fitLeft != null && state.fitRight != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.fit_detection_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.fit_detection_summary),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = when {
                            !supported -> stringResource(R.string.fit_detection_unsupported)
                            busy && state.stage == CalibrationState.STAGE_CHECKING_WEAR ->
                                stringResource(R.string.fit_detection_checking_wear)
                            busy -> stringResource(R.string.fit_detection_detecting) +
                                    "\n" + stringResource(R.string.fit_detection_keep_on)
                            showResult && state.fitLeft == FitQuality.GOOD &&
                                    state.fitRight == FitQuality.GOOD ->
                                stringResource(R.string.fit_detection_result_good_title)
                            showResult -> stringResource(R.string.fit_detection_result_adjust)
                            state.stage == CalibrationState.STAGE_ERROR ->
                                state.message ?: stringResource(R.string.fit_detection_failed)
                            else -> stringResource(R.string.fit_detection_hint)
                        },
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (showResult) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
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
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(
                        text = if (showResult) stringResource(R.string.fit_detection_retry)
                        else stringResource(R.string.fit_detection_start),
                        onClick = onStart,
                        enabled = supported,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EarbudPair(
    busy: Boolean,
    leftStatus: Int?,
    rightStatus: Int?,
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EarbudColumn(
            imageRes = R.drawable.melody_ear_left,
            modifier = Modifier.alpha(if (busy) pulse else 1f),
            status = leftStatus,
        )
        EarbudColumn(
            imageRes = R.drawable.melody_ear_right,
            modifier = Modifier.alpha(if (busy) pulse else 1f),
            status = rightStatus,
        )
    }
}

@Composable
private fun EarbudColumn(
    imageRes: Int,
    modifier: Modifier = Modifier,
    status: Int?,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 180.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                modifier = Modifier.size(width = 104.dp, height = 164.dp),
                contentScale = ContentScale.Fit,
            )
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
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
