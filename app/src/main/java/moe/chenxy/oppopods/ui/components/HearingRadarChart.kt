package moe.chenxy.oppopods.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.HearingPoint
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 六维听感雷达图数据，六个频段顺序对齐欢律官方（f3207R0）：
 * 0: 低频 (80Hz)
 * 1: 极高频 (10000Hz)
 * 2: 高频 (4800Hz)
 * 3: 中高频 (2400Hz)
 * 4: 中频 (1200Hz)
 * 5: 中低频 (250Hz)
 */
data class HearingRadarData(
    val left: List<Float>,
    val right: List<Float>,
    val enhanced: List<Float>,
) {
    companion object {
        fun default(): HearingRadarData = HearingRadarData(
            left = listOf(0.85f, 0.82f, 0.76f, 0.74f, 0.76f, 0.83f),
            right = listOf(0.84f, 0.80f, 0.75f, 0.73f, 0.75f, 0.81f),
            enhanced = List(6) { 0.96f },
        )

        fun fromLevels(levels: List<HearingPoint>): HearingRadarData {
            if (levels.isEmpty()) return default()

            val leftMap = levels.filter { it.side == 1 }.associateBy { it.type }
            val rightMap = levels.filter { it.side == 2 }.associateBy { it.type }

            // 严谨对齐欢律官方 C0470e0 / C0483l 的听力补偿与雷达图映射模型：
            // 用户测试 dB 转换为人耳听损补偿后，左右耳在各频点正常分布在 0.68 ~ 0.88 的饱满圆润六边形
            fun toRadius(db: Int?, scale: Float): Float {
                if (db == null) return 0.80f
                val loss = (db - (-55f)).coerceIn(0f, 72f)
                return (0.88f - (loss / 72f) * (scale / 40f) * 0.20f).coerceIn(0.66f, 0.90f)
            }

            val lLow = toRadius(leftMap[1]?.db, 25.0f)
            val lMidLow = toRadius(leftMap[2]?.db, 25.0f)
            val lMid = toRadius(leftMap[3]?.db, 35.0f)
            val lMidHigh = toRadius(leftMap[4]?.db, 35.0f)
            val lHigh = toRadius(leftMap[5]?.db, 40.0f)
            val lVHigh = toRadius(leftMap[6]?.db, 40.0f)

            val rLow = toRadius(rightMap[1]?.db, 25.0f)
            val rMidLow = toRadius(rightMap[2]?.db, 25.0f)
            val rMid = toRadius(rightMap[3]?.db, 35.0f)
            val rMidHigh = toRadius(rightMap[4]?.db, 35.0f)
            val rHigh = toRadius(rightMap[5]?.db, 40.0f)
            val rVHigh = toRadius(rightMap[6]?.db, 40.0f)

            // 0: 低频, 1: 极高频, 2: 高频, 3: 中高频, 4: 中频, 5: 中低频
            val leftRadii = listOf(lLow, lVHigh, lHigh, lMidHigh, lMid, lMidLow)
            val rightRadii = listOf(rLow, rVHigh, rHigh, rMidHigh, rMid, rMidLow)

            // 官方逻辑（f3208S0）：增强区间目标外包络恒定为理想听感（0.96f 外圈覆盖）
            val targetEnhanced = List(6) { 0.96f }

            return HearingRadarData(leftRadii, rightRadii, targetEnhanced)
        }
    }
}

/** 动态文本分析结果 */
data class HearingAnalysisResult(
    val bandName: String,
    val soundFeature: String,
    val songIntro: String,
)

fun calculateHearingAnalysis(data: HearingRadarData): HearingAnalysisResult {
    var minIndex = 4
    var minVal = Float.MAX_VALUE
    for (i in 0 until 6) {
        val avg = (data.left[i] + data.right[i]) / 2f
        if (avg < minVal) {
            minVal = avg
            minIndex = i
        }
    }

    return when (minIndex) {
        0 -> HearingAnalysisResult("低频", "力量感", "底鼓更澎湃有力")
        1 -> HearingAnalysisResult("极高频", "细腻感", "管弦泛音更丰富")
        2 -> HearingAnalysisResult("高频", "清晰感", "细节更透亮清晰")
        3 -> HearingAnalysisResult("中高频", "现场感", "声场更具临场感")
        4 -> HearingAnalysisResult("中频", "力量感", "底鼓更澎湃有力")
        else -> HearingAnalysisResult("中低频", "饱满感", "男低音更温暖醇厚")
    }
}

@Composable
fun HearingRadarChart(
    modifier: Modifier = Modifier,
    data: HearingRadarData = HearingRadarData.default(),
    isPlaying: Boolean = false,
    isEnhancedPreview: Boolean = false,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()

    // 颜色定义 (参考官方实机与 heymelody-windows)
    // 颜色定义 (参考欢律官方实机效果)
    val leftColor = if (dark) Color(0xFF5CD587) else Color(0xFF13A24E)
    val rightColor = if (dark) Color(0xFF60A8FF) else Color(0xFF1B7DEB)
    val enhancedColor = if (dark) Color(0xFFFF9E3D) else Color(0xFFF37510)
    val gridColor = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    // 入场动效：增强区间从左右耳基线向外自然展开（对齐官方 startGoldInAnimator）
    val entranceAnim = remember { Animatable(0f) }
    LaunchedEffect(data) {
        entranceAnim.snapTo(0f)
        entranceAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
        )
    }

    // 试听时的动态音乐波动与自然呼吸
    val infiniteTransition = rememberInfiniteTransition(label = "radar_spectrum")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_pulse",
    )

    val textMeasurer = rememberTextMeasurer()
    val labels = listOf(
        stringResource(R.string.hearing_freq_band_low),
        stringResource(R.string.hearing_freq_band_very_high),
        stringResource(R.string.hearing_freq_band_high),
        stringResource(R.string.hearing_freq_band_mid_high),
        stringResource(R.string.hearing_freq_band_mid),
        stringResource(R.string.hearing_freq_band_mid_low),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(210.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = (min(size.width, size.height) / 2f) - 34.dp.toPx()
                if (maxRadius <= 0) return@Canvas

                // 1. 官方 WebAreaView：4 个同心圆网格与 6 条中心辐射线
                val circleRatios = floatArrayOf(0.25f, 0.50f, 0.75f, 1.0f)
                for (ratio in circleRatios) {
                    drawCircle(
                        color = gridColor,
                        radius = maxRadius * ratio,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                for (i in 0 until 6) {
                    val angle = (-120.0 + i * 60.0) * PI / 180.0
                    val outerPt = Offset(
                        (center.x + cos(angle) * maxRadius).toFloat(),
                        (center.y + sin(angle) * maxRadius).toFloat(),
                    )
                    drawLine(
                        color = gridColor,
                        start = center,
                        end = outerPt,
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // 2. 绘制 6 个频段标签
                val labelDistance = maxRadius + 18.dp.toPx()
                val textStyle = TextStyle(
                    fontSize = 11.sp,
                    color = labelColor,
                    fontWeight = FontWeight.Normal,
                )
                for (i in 0 until 6) {
                    val angle = (-120.0 + i * 60.0) * PI / 180.0
                    val pt = Offset(
                        (center.x + cos(angle) * labelDistance).toFloat(),
                        (center.y + sin(angle) * labelDistance).toFloat(),
                    )
                    val textLayout = textMeasurer.measure(labels[i], textStyle)
                    val textOffset = Offset(
                        pt.x - textLayout.size.width / 2f,
                        pt.y - textLayout.size.height / 2f,
                    )
                    drawText(textLayout, topLeft = textOffset)
                }

                // 3. 动态波动量
                val dynamicOffset = if (isPlaying) (sin(pulse * PI.toFloat() * 2f) * 0.025f) else 0f

                // 增强区间（官方逻辑：覆盖在最外层包裹左右耳；增强前隐藏，增强后动态呼吸展开）
                val showEnhancement = !(isPlaying && !isEnhancedPreview)
                if (showEnhancement) {
                    val enhancedLevels = data.enhanced.mapIndexed { i, targetV ->
                        val base = minOf(data.left[i], data.right[i])
                        val currentV = base + (targetV - base) * entranceAnim.value
                        if (isPlaying && isEnhancedPreview) {
                            (currentV + sin(pulse * 2 * PI.toFloat() + i * 0.6f) * 0.025f).coerceIn(0.7f, 1.0f)
                        } else {
                            (currentV + sin(pulse * 2 * PI.toFloat()) * 0.008f).coerceIn(0.7f, 1.0f)
                        }
                    }
                    drawRoundedPolygonSeries(
                        center = center,
                        maxRadius = maxRadius,
                        levels = enhancedLevels,
                        cornerRadius = 4.dp.toPx(),
                        fillColor = enhancedColor.copy(alpha = 0.12f),
                        strokeColor = enhancedColor,
                        strokeWidth = 1.6.dp.toPx(),
                    )
                }

                // 绘制左耳（绿色，圆角多边形）
                val leftLevels = data.left.mapIndexed { i, v ->
                    if (isPlaying) (v + dynamicOffset * (if (i % 2 == 0) 0.5f else -0.4f)).coerceIn(0.6f, 0.95f)
                    else v
                }
                drawRoundedPolygonSeries(
                    center = center,
                    maxRadius = maxRadius,
                    levels = leftLevels,
                    cornerRadius = 4.dp.toPx(),
                    fillColor = leftColor.copy(alpha = 0.12f),
                    strokeColor = leftColor,
                    strokeWidth = 1.5.dp.toPx(),
                )

                // 绘制右耳（蓝色，圆角多边形）
                val rightLevels = data.right.mapIndexed { i, v ->
                    if (isPlaying) (v + dynamicOffset * (if (i % 2 != 0) 0.5f else -0.4f)).coerceIn(0.6f, 0.95f)
                    else v
                }
                drawRoundedPolygonSeries(
                    center = center,
                    maxRadius = maxRadius,
                    levels = rightLevels,
                    cornerRadius = 4.dp.toPx(),
                    fillColor = rightColor.copy(alpha = 0.12f),
                    strokeColor = rightColor,
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 底部图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(color = leftColor, label = stringResource(R.string.hearing_radar_left))
            Spacer(Modifier.width(18.dp))
            LegendItem(color = rightColor, label = stringResource(R.string.hearing_radar_right))
            Spacer(Modifier.width(18.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showInfoDialog = true },
                ),
            ) {
                LegendItem(color = enhancedColor, label = stringResource(R.string.hearing_radar_enhance))
                Spacer(Modifier.width(3.dp))
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = stringResource(R.string.hearing_radar_enhance_tips),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }

    if (showInfoDialog) {
        OverlayDialog(
            show = showInfoDialog,
            onDismissRequest = { showInfoDialog = false },
            title = stringResource(R.string.hearing_radar_enhance),
            summary = stringResource(R.string.hearing_radar_enhance_tips),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = { showInfoDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 绘制平滑圆角多边形（对齐官方 CustomRadarChartRenderer.java 中 setCornerRadius(4.0f)）
 */
private fun DrawScope.drawRoundedPolygonSeries(
    center: Offset,
    maxRadius: Float,
    levels: List<Float>,
    cornerRadius: Float,
    fillColor: Color,
    strokeColor: Color,
    strokeWidth: Float,
) {
    if (levels.size < 6) return
    val pts = ArrayList<Offset>(6)
    for (i in 0 until 6) {
        val angle = (-120.0 + i * 60.0) * PI / 180.0
        val r = maxRadius * levels[i].coerceIn(0.2f, 1f)
        pts.add(
            Offset(
                (center.x + cos(angle) * r).toFloat(),
                (center.y + sin(angle) * r).toFloat(),
            ),
        )
    }

    val path = Path()
    for (i in 0 until 6) {
        val curr = pts[i]
        val prev = pts[(i + 5) % 6]
        val next = pts[(i + 1) % 6]

        val d1x = prev.x - curr.x
        val d1y = prev.y - curr.y
        val len1 = hypot(d1x, d1y).coerceAtLeast(1e-4f)

        val d2x = next.x - curr.x
        val d2y = next.y - curr.y
        val len2 = hypot(d2x, d2y).coerceAtLeast(1e-4f)

        val r1 = min(cornerRadius, len1 / 2f)
        val r2 = min(cornerRadius, len2 / 2f)

        val p1 = Offset(curr.x + (d1x / len1) * r1, curr.y + (d1y / len1) * r1)
        val p2 = Offset(curr.x + (d2x / len2) * r2, curr.y + (d2y / len2) * r2)

        if (i == 0) {
            path.moveTo(p1.x, p1.y)
        } else {
            path.lineTo(p1.x, p1.y)
        }
        path.quadraticBezierTo(curr.x, curr.y, p2.x, p2.y)
    }
    path.close()

    drawPath(path, color = fillColor)
    drawPath(path, color = strokeColor, style = Stroke(width = strokeWidth))
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape),
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(color = color)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
