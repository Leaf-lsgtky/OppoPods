package moe.chenxy.oppopods.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.EqDefaults
import moe.chenxy.oppopods.pods.EqDevicePreset
import moe.chenxy.oppopods.pods.EqPreset
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalSlider
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 音效页的一级页面：内置预设和设备端自定义预设都以单选列表呈现。
 * 自定义预设的编辑器由本文件内的 [EqualizerEditPage] 从底部弹出。
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
    isEditing: Boolean = false,
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
        // The host owns the actual protocol operation (selecting the preset). The
        // editor itself remains local to this page so opening it does not add a
        // navigation destination.
        onOpenCustomEq(preset)
        editorState = createEditorState(preset, customEqFrequencies)
    }

    // A newly-created preset does not have an id until the earphone responds.
    // Once it appears in the refreshed list, select it and open the editor.
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
                        EqualizerPresetRow(
                            title = preset.name,
                            selected = preset.id == selectedId,
                            onClick = { onSelectPreset(preset.id) },
                        )
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
                    if (customPresets.isEmpty()) {
                        Text(
                            text = stringResource(R.string.eq_no_presets),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    customPresets.forEach { preset ->
                        EqualizerPresetRow(
                            title = preset.name,
                            selected = preset.id == selectedId,
                            onClick = { openCustomEditor(preset) },
                            onDelete = if (isEditing) {
                                { deleteTarget = preset }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            item(key = "add_eq") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showNewDialog = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { deleteTarget = null },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
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
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showRenameDialog = false },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.confirm),
                enabled = renameName.trim().isNotEmpty(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f),
                onClick = {
                    editorState = editorState?.copy(name = renameName.trim())
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
                editorState = null
            }
        },
    )
}

/** 单个预设行：使用 Miuix 原生 RadioButtonPreference，单选勾保持在右侧，编辑模式下右侧额外显示删除按钮。 */
@Composable
private fun EqualizerPresetRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    RadioButtonPreference(
        title = title,
        selected = selected,
        onClick = onClick,
        radioButtonLocation = RadioButtonLocation.End,
        endActions = {
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.eq_delete),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        },
    )
}

/** 编辑器的临时状态；只有点击“保存”才会写回耳机。 */
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
        preset.gains.getOrNull(index)?.coerceIn(minValue, maxValue) ?: 0.coerceIn(minValue, maxValue)
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
 * 顶部提供重命名，底部提供关闭和保存；滑块改动只保存在本地编辑状态中。
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
        title = state?.name,
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = onDismissRequest,
        startAction = {
            IconButton(
                onClick = onDismissRequest,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.eq_close),
                )
            }
        },
        endAction = {
            Row(modifier = Modifier.padding(end = 12.dp)) {
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.eq_rename),
                    )
                }
                IconButton(onClick = onSave) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = stringResource(R.string.eq_save),
                    )
                }
            }
        },
    ) {
        state?.let { current ->
            Column(modifier = Modifier.fillMaxWidth()) {
                EqualizerSliderPanel(
                    frequencies = current.frequencies,
                    gains = current.gains,
                    minValue = current.minValue,
                    maxValue = current.maxValue,
                    onGainChange = { index, value ->
                        val updatedGains = current.gains.toMutableList()
                        updatedGains[index] = value
                        onStateChange(current.copy(gains = updatedGains))
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 六段竖直均衡器：滑块内部使用 0..1，再映射到设备的 dB 范围。 */
@Composable
private fun EqualizerSliderPanel(
    frequencies: List<Int>,
    gains: List<Int>,
    minValue: Int,
    maxValue: Int,
    onGainChange: (Int, Int) -> Unit,
) {
    if (frequencies.isEmpty()) return

    val lower = minValue.coerceAtMost(maxValue)
    val upper = maxValue.coerceAtLeast(lower)
    val range = (upper - lower).coerceAtLeast(1).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 全局 dB 纵轴：与滑块轨道等高对齐，占位文本保证与每列标签同构。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier.height(180.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDbValue(upper),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = formatDbValue(0.coerceIn(lower, upper)),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = formatDbValue(lower),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(text = "", fontSize = 12.sp)
            }

            Spacer(Modifier.width(8.dp))

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                frequencies.forEachIndexed { index, frequency ->
                    val gain =
                        gains.getOrNull(index)?.coerceIn(lower, upper) ?: 0.coerceIn(lower, upper)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = formatGainValue(gain),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        VerticalSlider(
                            value = ((gain - lower) / range).coerceIn(0f, 1f),
                            onValueChange = { normalized ->
                                onGainChange(
                                    index,
                                    (lower + normalized.coerceIn(0f, 1f) * range)
                                        .roundToInt()
                                        .coerceIn(lower, upper),
                                )
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.height(180.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatFrequency(frequency),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun formatDbValue(value: Int): String = when {
    value > 0 -> "+$value dB"
    else -> "$value dB"
}

private fun formatGainValue(value: Int): String = when {
    value > 0 -> "+$value"
    else -> value.toString()
}

private fun formatFrequency(frequency: Int): String = when {
    frequency >= 1000 && frequency % 1000 == 0 -> "${frequency / 1000}k"
    else -> frequency.toString()
}
