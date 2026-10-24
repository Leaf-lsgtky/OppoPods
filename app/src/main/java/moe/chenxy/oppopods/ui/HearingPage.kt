package moe.chenxy.oppopods.ui

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.CalibrationState
import moe.chenxy.oppopods.pods.HearingProfile
import moe.chenxy.oppopods.ui.components.HearingAnalysisResult
import moe.chenxy.oppopods.ui.components.HearingRadarChart
import moe.chenxy.oppopods.ui.components.HearingRadarData
import moe.chenxy.oppopods.ui.components.calculateHearingAnalysis
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AuditionMode {
    NONE, BEFORE, AFTER
}

/**
 * 黄金听感主页面：
 * 1. 顶部副标题说明与“我的测试”分组。
 * 2. 方案列表聚合在单个 Card 内：标题“记录 X”，副标题为自定义名称或测试时间，中间竖线，右侧 Switch。
 * 3. 底部“开始测试”吸底大按钮：严格固定在屏幕底部，不随列表滑动上升或下降。
 * 4. 测听向导弹层（OverlayBottomSheet）：准备 -> 耳道扫描 -> 听音测试 -> 确认 -> 结果生成与应用。
 */
@Composable
fun HearingPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: CalibrationState,
    supported: Boolean,
    onStart: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onLevel: (Int, Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPreview: (Boolean) -> Unit,
    profiles: List<HearingProfile> = emptyList(),
    requiresEarScan: Boolean = false,
    onSelectProfile: (HearingProfile?) -> Unit = {},
    onDeleteProfile: (HearingProfile) -> Unit = {},
    onRenameProfile: (HearingProfile, String) -> Unit = { _, _ -> },
) {
    var showWizardSheet by remember { mutableStateOf(false) }
    var viewingProfile by remember { mutableStateOf<HearingProfile?>(null) }

    LaunchedEffect(state.stage) {
        if (state.stage in setOf(
                CalibrationState.STAGE_CHECKING_WEAR,
                CalibrationState.STAGE_SCANNING,
                CalibrationState.STAGE_HEARING,
                CalibrationState.STAGE_PROCESSING,
                CalibrationState.STAGE_RESULT,
            )
        ) {
            showWizardSheet = true
        } else if (state.stage == CalibrationState.STAGE_DONE) {
            showWizardSheet = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                start = contentPadding.calculateStartPadding(LayoutDirection.Ltr),
                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = contentPadding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            item {
                Text(
                    text = stringResource(R.string.hearing_summary),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }

            item {
                SmallTitle(text = stringResource(R.string.hearing_my_test))
            }

            // 方案列表（欢律官方样式：聚合在单个卡片内）
            if (profiles.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            profiles.forEachIndexed { index, profile ->
                                if (index > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(0.6.dp)
                                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    )
                                }
                                val isCurrent = (state.hearingEnabled == true && state.currentUid == profile.uid) ||
                                        (state.hearingEnabled == true && state.currentUid == null && index == 0)

                                HearingProfileItemRow(
                                    title = "记录 ${profile.localIndex}",
                                    subtitle = profile.name.ifBlank {
                                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(
                                            Date(if (profile.savedAt > 0) profile.savedAt else System.currentTimeMillis())
                                        )
                                    },
                                    isChecked = isCurrent,
                                    enabled = supported,
                                    onClick = { viewingProfile = profile },
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            onSelectProfile(profile)
                                        } else {
                                            onSelectProfile(null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            } else if (state.currentName.isNotBlank() || (state.currentUid != null && state.currentUid != 0) || state.hearingEnabled == true) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        HearingProfileItemRow(
                            title = "记录 1",
                            subtitle = state.currentName.ifBlank {
                                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                            },
                            isChecked = state.hearingEnabled == true,
                            enabled = supported,
                            onClick = {
                                viewingProfile = HearingProfile(
                                    uid = state.currentUid ?: 1,
                                    name = state.currentName.ifBlank {
                                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
                                    },
                                    localIndex = 1,
                                    levels = state.levels,
                                )
                            },
                            onCheckedChange = { onEnabledChange(it) },
                        )
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.hearing_empty),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.hearing_empty_desc),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            if (state.stage == CalibrationState.STAGE_ERROR) {
                item {
                    Text(
                        text = state.message ?: stringResource(R.string.hearing_unsupported),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // 底部吸底大按钮：严格固定在屏幕底部（参考实机截图 1 & 830）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            TextButton(
                text = stringResource(R.string.hearing_start),
                onClick = { showWizardSheet = true },
                enabled = supported && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    // 全屏高度测听向导
    if (showWizardSheet) {
        HearingWizardSheet(
            show = showWizardSheet,
            state = state,
            requiresEarScan = requiresEarScan,
            onDismissRequest = {
                if (state.isBusy) {
                    onCancel()
                }
                showWizardSheet = false
            },
            onStart = onStart,
            onAnswer = onAnswer,
            onLevel = onLevel,
            onNext = onNext,
            onPrevious = onPrevious,
            onSave = onSave,
            onCancel = onCancel,
            onPreview = onPreview,
        )
    }

    // 历史方案详情弹层
    viewingProfile?.let { profile ->
        val isCurrent = (state.hearingEnabled == true && state.currentUid == profile.uid)
        HearingDetailSheet(
            profile = profile,
            isCurrent = isCurrent,
            onDismissRequest = { viewingProfile = null },
            onApply = {
                onSelectProfile(profile)
                viewingProfile = null
            },
            onDelete = {
                onDeleteProfile(profile)
                viewingProfile = null
            },
            onRename = { newName ->
                onRenameProfile(profile, newName)
                viewingProfile = profile.copy(name = newName)
            },
            onPreview = onPreview,
        )
    }
}

/** 欢律列表卡片项：左侧标题+副标题，中间竖线，右侧 Switch */
@Composable
private fun HearingProfileItemRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        )

        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = isChecked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
            )
        }
    }
}

/** 全屏高度的测听向导 OverlayBottomSheet */
@Composable
private fun HearingWizardSheet(
    show: Boolean,
    state: CalibrationState,
    requiresEarScan: Boolean,
    onDismissRequest: () -> Unit,
    onStart: () -> Unit,
    onAnswer: (Boolean) -> Unit,
    onLevel: (Int, Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

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
        title = if (state.stage == CalibrationState.STAGE_IDLE || state.stage == CalibrationState.MODE_NONE) {
            stringResource(R.string.hearing_title)
        } else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.stage) {
                CalibrationState.STAGE_SCANNING,
                CalibrationState.STAGE_CHECKING_WEAR -> {
                    HearingStepTopBar(currentStep = 0, hasEarScan = requiresEarScan)
                    EarScanningView(
                        state = state,
                        isComplete = false,
                        onCancel = onCancel,
                    )
                }

                CalibrationState.STAGE_HEARING -> {
                    HearingStepTopBar(currentStep = 1, hasEarScan = requiresEarScan)
                    HearingDetectingView(
                        state = state,
                        onAnswer = onAnswer,
                        onLevel = onLevel,
                        onNext = {
                            if (state.step >= state.totalSteps - 1) {
                                showConfirmDialog = true
                            } else {
                                onNext()
                            }
                        },
                        onPrevious = onPrevious,
                    )
                }

                CalibrationState.STAGE_PROCESSING -> {
                    HearingStepTopBar(currentStep = 1, hasEarScan = requiresEarScan)
                    GeneratingSchemeView()
                }

                // 保持显示结果视图，避免保存和关闭时瞬间跳回准备视图发生闪烁
                CalibrationState.STAGE_RESULT,
                CalibrationState.STAGE_SAVING,
                CalibrationState.STAGE_DONE -> {
                    HearingResultSheetContent(
                        state = state,
                        isSaving = state.stage == CalibrationState.STAGE_SAVING,
                        onSave = { name ->
                            onSave(name)
                        },
                        onPreview = onPreview,
                    )
                }

                else -> {
                    PrepareWizardView(
                        hasEarScan = requiresEarScan,
                        onContinue = onStart,
                    )
                }
            }
        }
    }

    if (showConfirmDialog) {
        OverlayDialog(
            show = showConfirmDialog,
            onDismissRequest = { showConfirmDialog = false },
            title = stringResource(R.string.hearing_finish_confirm_title),
            summary = stringResource(R.string.hearing_finish_confirm_message),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = {
                        showConfirmDialog = false
                        onNext()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 顶部步骤指示器（耳道扫描 ······ 听音测试） */
@Composable
private fun HearingStepTopBar(
    currentStep: Int,
    hasEarScan: Boolean,
) {
    if (!hasEarScan) return

    val primaryColor = MiuixTheme.colorScheme.primary
    val disabledColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val earColor = if (currentStep == 0) primaryColor else disabledColor
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_hearing_detect_ear),
                contentDescription = null,
                tint = earColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hearing_step_scan_title),
                fontSize = 11.sp,
                color = earColor,
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(6) { index ->
                val dotActive = (currentStep > 0) || (currentStep == 0 && index < 3)
                Box(
                    modifier = Modifier
                        .size(3.5.dp)
                        .clip(CircleShape)
                        .background(if (dotActive) primaryColor else disabledColor),
                )
            }
        }

        val soundColor = if (currentStep == 1) primaryColor else disabledColor
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_hearing_detect_hearing),
                contentDescription = null,
                tint = soundColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hearing_step_test_title),
                fontSize = 11.sp,
                color = soundColor,
            )
        }
    }
}

/** 阶段 1：准备测试视图 */
@Composable
private fun PrepareWizardView(
    hasEarScan: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        Image(
            painter = painterResource(R.drawable.melody_ear_left),
            contentDescription = null,
            modifier = Modifier.height(200.dp),
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.hearing_prepare_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.hearing_prepare_desc),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasEarScan) {
                    HearingStepRowItem(
                        iconRes = R.drawable.ic_hearing_detect_ear,
                        title = stringResource(R.string.hearing_step_scan_title),
                        summary = stringResource(R.string.hearing_step_scan_desc),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 64.dp, end = 16.dp)
                            .height(0.5.dp)
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    )
                }
                HearingStepRowItem(
                    iconRes = R.drawable.ic_hearing_detect_hearing,
                    title = stringResource(R.string.hearing_step_test_title),
                    summary = stringResource(R.string.hearing_step_test_desc),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        TextButton(
            text = stringResource(R.string.hearing_continue),
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun HearingStepRowItem(
    iconRes: Int,
    title: String,
    summary: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 阶段 2：耳道扫描视图 */
@Composable
private fun EarScanningView(
    state: CalibrationState,
    isComplete: Boolean,
    onCancel: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "scan_pulse")
    val scanAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan_alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(30.dp))

        Image(
            painter = painterResource(R.drawable.melody_ear_left),
            contentDescription = null,
            modifier = Modifier
                .height(220.dp)
                .alpha(if (isComplete) 1f else scanAlpha),
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.height(36.dp))

        Text(
            text = if (isComplete) stringResource(R.string.hearing_scan_completed)
            else stringResource(R.string.hearing_scan_in_progress),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (isComplete) stringResource(R.string.hearing_scan_can_start_test)
            else stringResource(R.string.hearing_scan_duration_hint),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        Spacer(Modifier.weight(1f))

        if (!isComplete) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/** 阶段 3：听音测试视图 */
@Composable
private fun HearingDetectingView(
    state: CalibrationState,
    onAnswer: (Boolean) -> Unit,
    onLevel: (Int, Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val levelIndex = state.levelIndex.coerceIn(0, CalibrationState.V2_DECIBELS.lastIndex)
    var sliderValue by remember(state.step, state.version) {
        mutableFloatStateOf(levelIndex.toFloat())
    }

    val pulseTransition = rememberInfiniteTransition(label = "tone_wave")
    val waveScale by pulseTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave_scale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .graphicsLayer {
                        scaleX = waveScale
                        scaleY = waveScale
                    }
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.05f)),
            )
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)),
            )
            Image(
                painter = painterResource(R.drawable.melody_hearing_analysis),
                contentDescription = null,
                modifier = Modifier.size(150.dp),
                contentScale = ContentScale.Fit,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.hearing_detecting_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = stringResource(
                if (state.side == 1) R.string.hearing_testing_left else R.string.hearing_testing_right,
            ),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        Spacer(Modifier.height(18.dp))

        // 12 频点小胶囊指示条（左耳 6 点 + 右耳 6 点，当前测试点采用醒目暖金非白色）
        HearingBandsIndicator(
            currentStep = state.step,
            totalSteps = state.totalSteps,
        )

        Spacer(Modifier.height(24.dp))

        // 滑块区域（V2 带节点）或二分法按钮（V1）
        if (state.version == 2) {
            val keyPoints = remember {
                (0 until CalibrationState.V2_DECIBELS.size).map { it.toFloat() }
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    onLevel(it.roundToInt(), false)
                },
                onValueChangeFinished = {
                    onLevel(sliderValue.roundToInt(), true)
                },
                valueRange = 0f..(CalibrationState.V2_DECIBELS.lastIndex).toFloat(),
                steps = CalibrationState.V2_DECIBELS.size - 2,
                showKeyPoints = true,
                keyPoints = keyPoints,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val currentDb = CalibrationState.V2_DECIBELS[
                sliderValue.roundToInt().coerceIn(0, CalibrationState.V2_DECIBELS.lastIndex)
            ]
            Text(
                text = "$currentDb dB",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.hearing_heard),
                    onClick = { onAnswer(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.hearing_not_heard),
                    onClick = { onAnswer(false) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.hearing_previous),
                onClick = onPrevious,
                enabled = state.step > 0,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.hearing_next),
                onClick = onNext,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/** 12 个频点的指示横条：左耳 6 根 + 右耳 6 根（对齐官方 DetectingProgressBar） */
@Composable
private fun HearingBandsIndicator(
    currentStep: Int,
    totalSteps: Int = 12,
) {
    val primary = MiuixTheme.colorScheme.primary
    val activeColor = Color(0xFFFF9800) // 暖橙金/琥珀色，明确有别于未测与已测，且绝非白色
    val uncompletedColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左耳 6 根
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(6) { index ->
                val isCurrent = currentStep == index
                val isCompleted = index < currentStep
                val barColor = when {
                    isCurrent -> activeColor
                    isCompleted -> primary
                    else -> uncompletedColor
                }
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = 3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
        }

        // 右耳 6 根
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(6) { index ->
                val actualStep = 6 + index
                val isCurrent = currentStep == actualStep
                val isCompleted = actualStep < currentStep
                val barColor = when {
                    isCurrent -> activeColor
                    isCompleted -> primary
                    else -> uncompletedColor
                }
                Box(
                    modifier = Modifier
                        .size(width = 18.dp, height = 3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
        }
    }
}

/** 阶段 4：生成方案中加载动画视图 */
@Composable
private fun GeneratingSchemeView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        InfiniteProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.hearing_processing),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 阶段 5：测听完成结果视图（在向导 Sheet 内部展示）
 * 精细调整留白、字号与间距，一屏装下无需垂直滑动。
 */
@Composable
private fun HearingResultSheetContent(
    state: CalibrationState,
    isSaving: Boolean = false,
    onSave: (String) -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    val radarData = remember(state.levels) {
        HearingRadarData.fromLevels(state.levels)
    }
    val analysis = remember(radarData) {
        calculateHearingAnalysis(radarData)
    }

    var currentName by remember(state.savedName) {
        mutableStateOf(
            state.savedName.ifBlank {
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
            }
        )
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(currentName) }

    var auditionMode by remember { mutableStateOf(AuditionMode.NONE) }
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        try {
            mediaPlayer = MediaPlayer.create(context, R.raw.hearing_enhancement_audition)?.apply {
                isLooping = true
            }
        } catch (_: Exception) {}

        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            onPreview(false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 雷达图（紧凑 210dp）
        HearingRadarChart(
            data = radarData,
            isPlaying = auditionMode != AuditionMode.NONE,
            isEnhancedPreview = auditionMode == AuditionMode.AFTER,
        )

        Spacer(Modifier.height(6.dp))

        // 方案标题与直接重命名入口
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    editingName = currentName
                    showRenameDialog = true
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = currentName,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = MiuixIcons.Edit,
                contentDescription = stringResource(R.string.hearing_rename),
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        // 分析文案
        AnalysisTextView(analysis)

        Spacer(Modifier.height(8.dp))

        // 试听卡片
        AuditionSection(
            currentMode = auditionMode,
            onToggleBefore = {
                if (auditionMode == AuditionMode.BEFORE) {
                    mediaPlayer?.pause()
                    auditionMode = AuditionMode.NONE
                    onPreview(false)
                } else {
                    onPreview(false)
                    mediaPlayer?.start()
                    auditionMode = AuditionMode.BEFORE
                }
            },
            onToggleAfter = {
                if (auditionMode == AuditionMode.AFTER) {
                    mediaPlayer?.pause()
                    auditionMode = AuditionMode.NONE
                    onPreview(false)
                } else {
                    onPreview(true)
                    mediaPlayer?.start()
                    auditionMode = AuditionMode.AFTER
                }
            },
        )

        Spacer(Modifier.height(14.dp))

        // 应用按钮
        TextButton(
            text = if (isSaving) stringResource(R.string.hearing_saving) else stringResource(R.string.hearing_apply),
            onClick = {
                onSave(currentName)
            },
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        Spacer(Modifier.height(10.dp))
    }

    if (showRenameDialog) {
        OverlayDialog(
            show = showRenameDialog,
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.hearing_rename),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextField(
                    value = editingName,
                    onValueChange = { if (it.length <= 20) editingName = it },
                    label = stringResource(R.string.hearing_name),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
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
                        onClick = {
                            if (editingName.isNotBlank()) {
                                currentName = editingName.trim()
                                showRenameDialog = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/** 查看已保存历史方案详情的 OverlayBottomSheet */
@Composable
private fun HearingDetailSheet(
    profile: HearingProfile,
    isCurrent: Boolean,
    onDismissRequest: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onPreview: (Boolean) -> Unit,
) {
    val radarData = remember(profile.levels) {
        HearingRadarData.fromLevels(profile.levels)
    }
    val analysis = remember(radarData) {
        calculateHearingAnalysis(radarData)
    }

    var auditionMode by remember { mutableStateOf(AuditionMode.NONE) }
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(profile.name) }

    DisposableEffect(Unit) {
        try {
            mediaPlayer = MediaPlayer.create(context, R.raw.hearing_enhancement_audition)?.apply {
                isLooping = true
            }
        } catch (_: Exception) {}

        onDispose {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (_: Exception) {}
            onPreview(false)
        }
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    OverlayBottomSheet(
        show = true,
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
        endAction = {
            Box(modifier = Modifier.padding(end = 12.dp)) {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(
                        imageVector = MiuixIcons.More,
                        contentDescription = null,
                    )
                }
            }
        },
        title = "记录 ${profile.localIndex}",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HearingRadarChart(
                data = radarData,
                isPlaying = auditionMode != AuditionMode.NONE,
                isEnhancedPreview = auditionMode == AuditionMode.AFTER,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "记录 ${profile.localIndex}",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = profile.name,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Spacer(Modifier.height(4.dp))

            AnalysisTextView(analysis)

            Spacer(Modifier.height(8.dp))

            AuditionSection(
                currentMode = auditionMode,
                onToggleBefore = {
                    if (auditionMode == AuditionMode.BEFORE) {
                        mediaPlayer?.pause()
                        auditionMode = AuditionMode.NONE
                        onPreview(false)
                    } else {
                        onPreview(false)
                        mediaPlayer?.start()
                        auditionMode = AuditionMode.BEFORE
                    }
                },
                onToggleAfter = {
                    if (auditionMode == AuditionMode.AFTER) {
                        mediaPlayer?.pause()
                        auditionMode = AuditionMode.NONE
                        onPreview(false)
                    } else {
                        onPreview(true)
                        mediaPlayer?.start()
                        auditionMode = AuditionMode.AFTER
                    }
                },
            )

            Spacer(Modifier.height(14.dp))

            TextButton(
                text = if (isCurrent) stringResource(R.string.hearing_in_use) else stringResource(R.string.hearing_apply),
                onClick = onApply,
                enabled = !isCurrent,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showMenu) {
        OverlayDialog(
            show = showMenu,
            onDismissRequest = { showMenu = false },
            title = profile.name,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.hearing_rename),
                    onClick = {
                        showMenu = false
                        newName = profile.name
                        showRenameDialog = true
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.hearing_delete),
                    onClick = {
                        showMenu = false
                        showDeleteDialog = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showRenameDialog) {
        OverlayDialog(
            show = showRenameDialog,
            onDismissRequest = { showRenameDialog = false },
            title = stringResource(R.string.hearing_rename),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = newName,
                    onValueChange = { if (it.length <= 20) newName = it },
                    label = stringResource(R.string.hearing_name),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
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
                        onClick = {
                            if (newName.isNotBlank()) {
                                onRename(newName.trim())
                                showRenameDialog = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        OverlayDialog(
            show = showDeleteDialog,
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.hearing_delete),
            summary = if (isCurrent) stringResource(R.string.hearing_delete_in_use_warning)
            else stringResource(R.string.hearing_delete_confirm),
        ) {
            if (isCurrent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.confirm),
                        onClick = {
                            showDeleteDialog = false
                            onDelete()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/** 动态分析富文本 */
@Composable
private fun AnalysisTextView(analysis: HearingAnalysisResult) {
    Text(
        text = buildAnnotatedString {
            append("经过耳道结构、听力特征、心理声学、耳机声学系统等多维建模分析，你的听感在")
            withStyle(SpanStyle(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append(analysis.bandName)
            }
            append("区间可提升度较高，已为你针对性提升声音的")
            withStyle(SpanStyle(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append(analysis.soundFeature)
            }
            append("，快去听歌体验，你会发现歌曲中的")
            withStyle(SpanStyle(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) {
                append(analysis.songIntro)
            }
            append("。")
        },
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 试听卡片：增强前与增强后（使用 Miuix 图标） */
@Composable
private fun AuditionSection(
    currentMode: AuditionMode,
    onToggleBefore: () -> Unit,
    onToggleAfter: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleBefore() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.hearing_audition_before),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onToggleBefore) {
                        Icon(
                            imageVector = if (currentMode == AuditionMode.BEFORE) MiuixIcons.Pause else MiuixIcons.Play,
                            contentDescription = stringResource(R.string.hearing_audition_before),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.6.dp)
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleAfter() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.hearing_audition_after),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onToggleAfter) {
                        Icon(
                            imageVector = if (currentMode == AuditionMode.AFTER) MiuixIcons.Pause else MiuixIcons.Play,
                            contentDescription = stringResource(R.string.hearing_audition_after),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
