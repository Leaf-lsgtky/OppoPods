package moe.chenxy.oppopods.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.EqDefaults
import moe.chenxy.oppopods.pods.EqDevicePreset
import moe.chenxy.oppopods.pods.EqPreset
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 音效/大师调音页的一级页面：内置预设和设备端自定义预设以卡片单选列表呈现。
 * 自定义预设的编辑器由 [EqualizerEditPage] 从底部以波形曲线图抽屉弹出。
 */
@Composable
fun EqualizerPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    builtInPresets: List<EqPreset>,
    devicePresets: List<EqDevicePreset>,
    selectedId: Int,
    customEqVisible: Boolean,
    customEqFrequencies: List<Int> = emptyList(),
    customEqMaxPresets: Int = 0,
    onSelectPreset: (Int) -> Unit = {},
    onOpenCustomEq: (EqDevicePreset) -> Unit = { onSelectPreset(it.id) },
    onSavePreset: (Int, String, List<Int>, List<Int>, Int, Int) -> Unit =
        { _, _, _, _, _, _ -> },
    onDeletePreset: (EqDevicePreset) -> Unit = {},
) {
    val builtInIds = remember(builtInPresets) { builtInPresets.map { it.id }.toSet() }
    val customPresets = devicePresets.filter {
        it.id !in builtInIds && it.name.isNotBlank()
    }
    val canCreate = customEqVisible &&
            (customEqMaxPresets <= 0 || customPresets.size < customEqMaxPresets)

    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingCreationName by remember { mutableStateOf<String?>(null) }

    var deleteTarget by remember { mutableStateOf<EqDevicePreset?>(null) }

    var editorState by remember { mutableStateOf<EqEditorState?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameName by remember { mutableStateOf("") }

    fun openCustomEditor(preset: EqDevicePreset) {
        onOpenCustomEq(preset)
        editorState = createEditorState(preset, customEqFrequencies)
    }

    LaunchedEffect(devicePresets, pendingCreationName) {
        val name = pendingCreationName ?: return@LaunchedEffect
        val created = devicePresets.firstOrNull {
            it.id !in builtInIds && it.name == name
        }
        if (created != null) {
            pendingCreationName = null
            openCustomEditor(created)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
    ) {
        if (builtInPresets.isNotEmpty()) {
            item(key = "recommended_title") {
                SmallTitle(text = stringResource(R.string.eq_recommended))
            }
            item(key = "recommended_card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    builtInPresets.forEach { preset ->
                        val showDynaudio = preset.tag == 3 ||
                                preset.name.contains("Dynaudio", ignoreCase = true) ||
                                preset.name.contains("丹拿")
                        val isSelected = preset.id == selectedId
                        if (showDynaudio) {
                            BasicComponent(
                                onClick = { onSelectPreset(preset.id) },
                                endActions = {
                                    Checkbox(
                                        state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                        onClick = { onSelectPreset(preset.id) },
                                        modifier = Modifier.scale(0.75f),
                                    )
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = preset.name,
                                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Image(
                                        painter = painterResource(R.drawable.melody_ui_equalizer_dynaudio),
                                        contentDescription = "Dynaudio",
                                        modifier = Modifier.size(width = 48.dp, height = 14.dp),
                                    )
                                }
                                getPresetSummary(preset)?.let { summary ->
                                    Text(
                                        text = summary,
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        } else {
                            BasicComponent(
                                title = preset.name,
                                summary = getPresetSummary(preset),
                                onClick = { onSelectPreset(preset.id) },
                                endActions = {
                                    Checkbox(
                                        state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                        onClick = { onSelectPreset(preset.id) },
                                        modifier = Modifier.scale(0.75f),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (customEqVisible) {
            item(key = "custom_title") {
                SmallTitle(text = stringResource(R.string.eq_custom))
            }
            item(key = "custom_card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    if (customPresets.isEmpty() && !canCreate) {
                        Text(
                            text = stringResource(R.string.eq_no_presets),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    customPresets.forEach { preset ->
                        val isSelected = preset.id == selectedId
                        BasicComponent(
                            title = preset.name,
                            onClick = { openCustomEditor(preset) },
                            endActions = {
                                IconButton(
                                    onClick = { deleteTarget = preset },
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .size(28.dp),
                                    minWidth = 28.dp,
                                    minHeight = 28.dp,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = stringResource(R.string.eq_delete),
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Checkbox(
                                    state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                    onClick = { openCustomEditor(preset) },
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .scale(0.75f),
                                )
                            },
                        )
                    }
                    if (canCreate) {
                        if (customPresets.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .padding(horizontal = 16.dp)
                                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            )
                        }
                        BasicComponent(
                            modifier = Modifier.fillMaxWidth(),
                            title = stringResource(R.string.eq_add),
                            titleColor = BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary,
                            ),
                            enabled = canCreate,
                            onClick = {
                                newName = ""
                                showNewDialog = true
                            },
                        )
                    }
                }
            }
        }

        if (builtInPresets.isEmpty() && !customEqVisible) {
            item(key = "no_presets") {
                Text(
                    text = stringResource(R.string.eq_no_presets),
                    modifier = Modifier.padding(28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    OverlayDialog(
        title = stringResource(R.string.eq_new_title),
        show = showNewDialog,
        onDismissRequest = { showNewDialog = false },
    ) {
        TextField(
            value = newName,
            onValueChange = { newName = it.take(32) },
            label = stringResource(R.string.eq_name),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showNewDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                enabled = newName.trim().isNotEmpty(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val name = newName.trim()
                    val frequencies = customEqFrequencies.ifEmpty { EqDefaults.FREQUENCIES }
                    pendingCreationName = name
                    onSavePreset(
                        0,
                        name,
                        frequencies,
                        frequencies.map { 0 },
                        -6,
                        6,
                    )
                    showNewDialog = false
                },
            )
        }
    }

    val target = deleteTarget
    OverlayDialog(
        title = stringResource(R.string.eq_delete_title),
        summary = target?.name?.let { stringResource(R.string.eq_delete_confirm, it) } ?: "",
        show = target != null,
        onDismissRequest = { deleteTarget = null },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { deleteTarget = null },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    target?.let { preset ->
                        onDeletePreset(preset)
                        if (editorState?.preset?.id == preset.id) {
                            editorState = null
                        }
                    }
                    deleteTarget = null
                },
            )
        }
    }

    OverlayDialog(
        title = stringResource(R.string.eq_rename_title),
        show = showRenameDialog,
        onDismissRequest = { showRenameDialog = false },
    ) {
        TextField(
            value = renameName,
            onValueChange = { renameName = it.take(32) },
            label = stringResource(R.string.eq_name),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showRenameDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                enabled = renameName.trim().isNotEmpty(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val updatedName = renameName.trim()
                    editorState?.let { current ->
                        val newState = current.copy(name = updatedName)
                        editorState = newState
                        onSavePreset(
                            newState.preset.id,
                            newState.name,
                            newState.frequencies,
                            newState.gains,
                            newState.minValue,
                            newState.maxValue,
                        )
                    }
                    showRenameDialog = false
                },
            )
        }
    }

    EqualizerEditPage(
        state = editorState,
        onStateChange = { editorState = it },
        onDismissRequest = { editorState = null },
        onRename = {
            renameName = editorState?.name.orEmpty()
            showRenameDialog = true
        },
        onSave = {
            editorState?.let { state ->
                onSavePreset(
                    state.preset.id,
                    state.name,
                    state.frequencies,
                    state.gains,
                    state.minValue,
                    state.maxValue,
                )
            }
        },
    )
}

/** 编辑器的临时状态 */
private data class EqEditorState(
    val preset: EqDevicePreset,
    val name: String,
    val frequencies: List<Int>,
    val gains: List<Int>,
    val minValue: Int,
    val maxValue: Int,
)

private fun createEditorState(
    preset: EqDevicePreset,
    fallbackFrequencies: List<Int>,
): EqEditorState {
    val frequencies = preset.frequencies
        .ifEmpty { fallbackFrequencies }
        .ifEmpty { EqDefaults.FREQUENCIES }
    val minValue = preset.minValue.coerceAtMost(preset.maxValue)
    val maxValue = preset.maxValue.coerceAtLeast(minValue)
    val gains = frequencies.mapIndexed { index, _ ->
        preset.gains.getOrNull(index)?.coerceIn(minValue, maxValue)
            ?: 0.coerceIn(minValue, maxValue)
    }
    return EqEditorState(
        preset = preset,
        name = preset.name,
        frequencies = frequencies,
        gains = gains,
        minValue = minValue,
        maxValue = maxValue,
    )
}

/**
 * 二级均衡器编辑页，作为 OverlayBottomSheet 从底部弹出。
 * 顶部左侧为关闭图标按钮，中间为方案名称及类似黄金听感的编辑图标；中下部为带留白的平滑波形曲线图。
 */
@Composable
private fun EqualizerEditPage(
    state: EqEditorState?,
    onStateChange: (EqEditorState) -> Unit,
    onDismissRequest: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
) {
    OverlayBottomSheet(
        show = state != null,
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = {
            onSave()
            onDismissRequest()
        },
    ) {
        state?.let { current ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶部操作栏：左侧关闭按钮，中间方案名称及编辑图标
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    IconButton(
                        onClick = {
                            onSave()
                            onDismissRequest()
                        },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = stringResource(R.string.eq_close),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRename() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = current.name,
                            style = MiuixTheme.textStyles.title4,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = MiuixIcons.Edit,
                            contentDescription = stringResource(R.string.eq_rename),
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                InteractiveEqualizerCurveView(
                    frequencies = current.frequencies,
                    gains = current.gains,
                    minValue = current.minValue,
                    maxValue = current.maxValue,
                    onGainChange = { index, value ->
                        val updatedGains = current.gains.toMutableList()
                        updatedGains[index] = value
                        onStateChange(current.copy(gains = updatedGains))
                    },
                    onDragEnd = onSave,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private data class ChartBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 仿官方 HeyMelody 的可拖拽交互式均衡器波形曲线图。
 *
 * 具有充足的呼吸留白：
 * - 顶部留白充足，实时蓝色增益数字不贴顶
 * - 左右有对称网格边距，首尾频段节点不贴边
 * - 背景水平参考线（+6 dB、0 dB、-6 dB）与各频段竖直网格线
 * - 曲线下方半透明渐变区域填充（Area Fill）
 * - 平滑三次贝塞尔连续曲线
 * - 各频段可拖拽/触控节点圆点（白底蓝边），带触感震动反馈
 * - 底部频率文字（62、250、1k、4k、8k、16k）及“低频”/“高频”标注
 */
@Composable
private fun InteractiveEqualizerCurveView(
    frequencies: List<Int>,
    gains: List<Int>,
    minValue: Int,
    maxValue: Int,
    onGainChange: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (frequencies.isEmpty()) return

    val lower = minValue.coerceAtMost(maxValue)
    val upper = maxValue.coerceAtLeast(lower)
    val range = (upper - lower).coerceAtLeast(1).toFloat()

    val haptic = LocalHapticFeedback.current
    val primaryColor = MiuixTheme.colorScheme.primary
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val gridColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val centerLineColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.15f)

    val textMeasurer = rememberTextMeasurer()
    val lowFreqLabel = stringResource(R.string.eq_low_freq)
    val highFreqLabel = stringResource(R.string.eq_high_freq)

    val currentGains by rememberUpdatedState(gains)
    val currentFrequencies by rememberUpdatedState(frequencies)
    val currentOnGainChange by rememberUpdatedState(onGainChange)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    var chartLayoutBounds by remember { mutableStateOf<ChartBounds?>(null) }

    val gainTextStyle = remember(primaryColor) {
        TextStyle(
            color = primaryColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }

    val scaleTextStyle = remember(labelColor) {
        TextStyle(
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.End,
        )
    }

    val freqTextStyle = remember(labelColor) {
        TextStyle(
            color = labelColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }

    val subFreqTextStyle = remember(labelColor) {
        TextStyle(
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }

    fun handleTouch(touchX: Float, touchY: Float): Int? {
        val bounds = chartLayoutBounds ?: return null
        val n = currentFrequencies.size
        if (n < 2) return null
        val step = (bounds.right - bounds.left) / (n - 1)

        val closestIndex = currentFrequencies.indices.minByOrNull { i ->
            val colX = bounds.left + i * step
            (colX - touchX).absoluteValue
        } ?: return null

        val colX = bounds.left + closestIndex * step
        if ((colX - touchX).absoluteValue <= step * 0.75f) {
            val normalized = 1f - ((touchY - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f)
            val newGain = (lower + normalized * range).roundToInt().coerceIn(lower, upper)
            if (newGain != currentGains.getOrNull(closestIndex)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnGainChange(closestIndex, newGain)
            }
            return closestIndex
        }
        return null
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(frequencies.size, lower, upper) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val activeIndex = handleTouch(down.position.x, down.position.y)
                    if (activeIndex != null) {
                        down.consume()
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                currentOnDragEnd()
                                break
                            }
                            change.consume()
                            val bounds = chartLayoutBounds ?: continue
                            val normalized = 1f - ((change.position.y - bounds.top) / (bounds.bottom - bounds.top)).coerceIn(0f, 1f)
                            val newGain = (lower + normalized * range).roundToInt().coerceIn(lower, upper)
                            if (newGain != currentGains.getOrNull(activeIndex)) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnGainChange(activeIndex, newGain)
                            }
                        }
                    }
                }
            }
    ) {
        val leftMargin = 54.dp.toPx()
        val rightMargin = 28.dp.toPx()
        val topMargin = 38.dp.toPx()
        val bottomMargin = 56.dp.toPx()

        val chartLeft = leftMargin
        val chartRight = size.width - rightMargin
        val chartTop = topMargin
        val chartBottom = size.height - bottomMargin
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        chartLayoutBounds = ChartBounds(chartLeft, chartTop, chartRight, chartBottom)

        val n = currentFrequencies.size
        val step = if (n > 1) chartWidth / (n - 1) else 0f

        // 1. 左侧刻度数值（+6 dB, 0 dB, -6 dB）
        val maxLabel = formatDbValue(upper)
        val maxMeas = textMeasurer.measure(maxLabel, scaleTextStyle)
        drawText(
            textLayoutResult = maxMeas,
            topLeft = Offset(chartLeft - maxMeas.size.width - 10.dp.toPx(), chartTop - maxMeas.size.height / 2f),
        )

        val zeroRatio = ((0 - lower) / range).coerceIn(0f, 1f)
        val zeroY = chartBottom - zeroRatio * chartHeight
        val zeroLabel = formatDbValue(0.coerceIn(lower, upper))
        val zeroMeas = textMeasurer.measure(zeroLabel, scaleTextStyle)
        drawText(
            textLayoutResult = zeroMeas,
            topLeft = Offset(chartLeft - zeroMeas.size.width - 10.dp.toPx(), zeroY - zeroMeas.size.height / 2f),
        )

        val minLabel = formatDbValue(lower)
        val minMeas = textMeasurer.measure(minLabel, scaleTextStyle)
        drawText(
            textLayoutResult = minMeas,
            topLeft = Offset(chartLeft - minMeas.size.width - 10.dp.toPx(), chartBottom - minMeas.size.height / 2f),
        )

        // 2. 水平参考线
        drawLine(
            color = gridColor,
            start = Offset(chartLeft, chartTop),
            end = Offset(chartRight, chartTop),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = centerLineColor,
            start = Offset(chartLeft, zeroY),
            end = Offset(chartRight, zeroY),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = gridColor,
            start = Offset(chartLeft, chartBottom),
            end = Offset(chartRight, chartBottom),
            strokeWidth = 1.dp.toPx(),
        )

        // 3. 各频段竖直参考网格线
        for (i in 0 until n) {
            val colX = chartLeft + i * step
            drawLine(
                color = gridColor,
                start = Offset(colX, chartTop),
                end = Offset(colX, chartBottom),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 4. 计算各节点坐标
        val points = mutableListOf<Offset>()
        for (i in 0 until n) {
            val colX = chartLeft + i * step
            val gain = currentGains.getOrNull(i) ?: 0
            val normalized = ((gain - lower) / range).coerceIn(0f, 1f)
            val ptY = chartBottom - normalized * chartHeight
            points.add(Offset(colX, ptY))
        }

        // 5. 绘制平滑波形曲线与渐变区域（Cubic Spline）
        if (points.isNotEmpty()) {
            val curvePath = Path()
            curvePath.moveTo(points[0].x, points[0].y)

            val fillPath = Path()
            fillPath.moveTo(points[0].x, chartBottom)
            fillPath.lineTo(points[0].x, points[0].y)

            for (i in 0 until points.size - 1) {
                val p0 = if (i > 0) points[i - 1] else points[i]
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = if (i + 2 < points.size) points[i + 2] else p2

                val cp1x = p1.x + (p2.x - p0.x) / 6f
                var cp1y = p1.y + (p2.y - p0.y) / 6f
                val cp2x = p2.x - (p3.x - p1.x) / 6f
                var cp2y = p2.y - (p3.y - p1.y) / 6f

                if ((p2.y - p1.y).absoluteValue < 1f) {
                    cp1y = p1.y
                    cp2y = p2.y
                } else {
                    cp1y = cp1y.coerceIn(chartTop, chartBottom)
                    cp2y = cp2y.coerceIn(chartTop, chartBottom)
                }

                curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                fillPath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }

            fillPath.lineTo(points.last().x, chartBottom)
            fillPath.close()

            // 区域渐变填充
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.45f),
                        primaryColor.copy(alpha = 0.04f),
                    ),
                    startY = chartTop,
                    endY = chartBottom,
                ),
            )

            // 波形轮廓曲线
            drawPath(
                path = curvePath,
                color = primaryColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // 节点触控圆点（白底蓝边）
            points.forEach { pt ->
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = pt,
                )
                drawCircle(
                    color = primaryColor,
                    radius = 6.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }

        // 6. 各频段顶部实时增益数字与底部频率标签
        for (i in 0 until n) {
            val colX = chartLeft + i * step
            val gain = currentGains.getOrNull(i) ?: 0

            // 顶部蓝色增益值
            val gainStr = gain.toString()
            val gainMeas = textMeasurer.measure(gainStr, gainTextStyle)
            drawText(
                textLayoutResult = gainMeas,
                topLeft = Offset(colX - gainMeas.size.width / 2f, chartTop - gainMeas.size.height - 8.dp.toPx()),
            )

            // 底部频率
            val freqStr = formatFrequency(currentFrequencies[i])
            val freqMeas = textMeasurer.measure(freqStr, freqTextStyle)
            drawText(
                textLayoutResult = freqMeas,
                topLeft = Offset(colX - freqMeas.size.width / 2f, chartBottom + 10.dp.toPx()),
            )
        }

        // 7. 底部首尾频段的“低频”与“高频”标注
        if (n >= 2) {
            val lowMeas = textMeasurer.measure(lowFreqLabel, subFreqTextStyle)
            drawText(
                textLayoutResult = lowMeas,
                topLeft = Offset(chartLeft - lowMeas.size.width / 2f, chartBottom + 30.dp.toPx()),
            )

            val highMeas = textMeasurer.measure(highFreqLabel, subFreqTextStyle)
            drawText(
                textLayoutResult = highMeas,
                topLeft = Offset(chartRight - highMeas.size.width / 2f, chartBottom + 30.dp.toPx()),
            )
        }
    }
}

/** 格式化刻度 dB 文本 */
private fun formatDbValue(value: Int): String = when {
    value > 0 -> "+$value dB"
    else -> "$value dB"
}

/** 格式化频率标签（62, 250, 1k, 4k, 8k, 16k） */
private fun formatFrequency(frequency: Int): String = when {
    frequency >= 1000 && frequency % 1000 == 0 -> "${frequency / 1000}k"
    else -> frequency.toString()
}

/** 官方已确认的内置预设对应描述（匹配白名单中的 summaryId 1..5） */
private fun getPresetSummary(preset: EqPreset): String? {
    return when (preset.summaryId) {
        1 -> "经久耐听，完美兼容多种音乐风格"
        2 -> "清晰还原每一个乐器的细节，适合交响乐与钢琴曲"
        3 -> "专门为表现人声所调制，适合经典流行歌曲"
        4 -> "领略低音的量感和质感，适合电子乐与摇滚乐"
        5 -> "还原经典丹拿音响的原生音色"
        else -> null
    }
}
