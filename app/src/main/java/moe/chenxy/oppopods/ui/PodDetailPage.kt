package moe.chenxy.oppopods.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.AncKeys
import moe.chenxy.oppopods.pods.AncOption
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.NoiseLevel
import moe.chenxy.oppopods.ui.components.AncSwitch
import moe.chenxy.oppopods.ui.components.PodStatus
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    batteryParams: BatteryParams,
    ancMode: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    adaptiveModeEnabled: Boolean = true,
    noiseLevelVisible: Boolean = false,
    noiseLevel: Int = NoiseLevel.DEEP,
    smartAncLevel: Int = -1,
    onNoiseLevelChange: (Int) -> Unit = {},
    homeImageFile: java.io.File? = null,
    modelFile: java.io.File? = null,
    isActive: Boolean = true,
    /** 未连接时让 3D 模型停在当前角度。 */
    autoRotate: Boolean = true,
    transparencyVariants: List<AncOption> = emptyList(),
    ancVariant: Int = -1,
    onAncVariantChange: (Int) -> Unit = {},
    /** 断开连接；null 表示当前不可断开（例如未连接）。 */
    onDisconnect: (() -> Unit)? = null,
) {
    val smartLevelName = if (noiseLevel == NoiseLevel.SMART) {
        when (smartAncLevel) {
            NoiseLevel.LIGHT -> stringResource(R.string.noise_level_light)
            NoiseLevel.MEDIUM -> stringResource(R.string.noise_level_medium)
            NoiseLevel.DEEP -> stringResource(R.string.noise_level_deep)
            else -> null
        }
    } else null
    val smartLabel = stringResource(R.string.noise_level_smart)
    val noiseLevelOptions = listOf(
        if (smartLevelName != null) "$smartLabel：$smartLevelName" else smartLabel,
        stringResource(R.string.noise_level_light),
        stringResource(R.string.noise_level_medium),
        stringResource(R.string.noise_level_deep)
    )
    val noiseLevelValues = NoiseLevel.ALL
    val currentNoiseLevelIndex = noiseLevelValues
        .indexOf(noiseLevel)
        .takeIf { it >= 0 }
        ?: 3
    val transparencyLabels = transparencyVariants.map {
        when (it.key) {
            AncKeys.VOCAL_ENHANCE -> stringResource(R.string.transparency_vocal_enhance)
            AncKeys.LISTEN -> stringResource(R.string.transparency_listen)
            else -> stringResource(R.string.transparency_standard)
        }
    }
    val transparencySelectedIndex = transparencyVariants
        .indexOfFirst { it.protocolIndex == ancVariant }
        .takeIf { it >= 0 }
        ?: 0
    var show3D by remember(modelFile?.path) { mutableStateOf(modelFile != null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            val homeBitmap = remember(homeImageFile?.path) {
                homeImageFile?.let {
                    runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull()
                }
            }
            val imageModifier = Modifier
                .fillMaxWidth(0.7f)
                // 宽屏下 70% 宽度会把回退图拉得很高，限制最大高度并用 Fit 保持比例。
                .heightIn(max = 320.dp)
                .padding(vertical = 16.dp)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // 3D 模型优先；图片只在没有模型或模型加载失败时作为回退样式。
                if (show3D && modelFile != null) {
                    PodModelViewer(
                        modelFile = modelFile,
                        // 高度与 PodModelViewer 的相机距离成比例（原 320dp↔5.0 → 240dp↔3.75），
                        // 模型在屏幕上的像素尺寸不变，只裁掉上下多余的空白，
                        // 与电量卡片之间的间距随之收紧。
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        isActive = isActive,
                        autoRotate = autoRotate,
                        fallback = {
                            show3D = false
                        },
                    )
                } else if (homeBitmap != null) {
                    Image(
                        bitmap = homeBitmap,
                        contentDescription = "Earphones",
                        modifier = imageModifier,
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.img_box),
                        contentDescription = "Earphones",
                        modifier = imageModifier,
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                PodStatus(batteryParams, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
            }
        }

        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                AncSwitch(ancMode, onAncModeChange, adaptiveModeEnabled = adaptiveModeEnabled)
            }
        }
        if (onDisconnect != null) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    BasicComponent(
                        title = stringResource(R.string.disconnect),
                        // 断开是破坏性操作，文字用 miuix 的错误色提示。
                        titleColor = BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        onClick = onDisconnect,
                    )
                }
            }
        }

        item {
            androidx.compose.animation.AnimatedVisibility(
                visible = noiseLevelVisible && ancMode == NoiseControlMode.NOISE_CANCELLATION,
                enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.noise_level_title),
                        items = noiseLevelOptions,
                        selectedIndex = currentNoiseLevelIndex,
                        onSelectedIndexChange = { onNoiseLevelChange(noiseLevelValues[it]) }
                    )
                }
            }
        }

        item {
            androidx.compose.animation.AnimatedVisibility(
                visible = ancMode == NoiseControlMode.TRANSPARENCY &&
                        transparencyVariants.size > 1,
                enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(),
            ) {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                ) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.transparency_mode),
                        items = transparencyLabels,
                        selectedIndex = transparencySelectedIndex,
                        onSelectedIndexChange = {
                            onAncVariantChange(transparencyVariants[it].protocolIndex)
                        },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }
    }
}
