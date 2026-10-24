package moe.chenxy.oppopods.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.CapabilityProfileFactory
import moe.chenxy.oppopods.pods.DeviceModelRegistry
import moe.chenxy.oppopods.pods.DeviceProfile
import moe.chenxy.oppopods.pods.DeviceProfileStore
import moe.chenxy.oppopods.pods.OfficialArtworkRepository
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.pods.ProfileMode
import moe.chenxy.oppopods.ui.dialogs.MelodyImageImportDialog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备配置页。
 *
 * 机型配置全部来自内嵌的官方白名单，用户只需决定「用哪个型号」：自动识别，
 * 或手动指定。耳机素材（图片/连接动画）与型号无关，可单独替换或恢复默认。
 */
@Composable
fun ProfilesPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    prefs: SharedPreferences,
    activeProfile: DeviceProfile,
    deviceColor: Int? = null,
    onActiveProfileChanged: (DeviceProfile) -> Unit = {},
) {
    val context = LocalContext.current

    val allModels = remember { DeviceModelRegistry.allModels(context) }
    var mode by remember { mutableStateOf(DeviceProfileStore.profileMode(prefs)) }
    var selectedModelId by remember { mutableStateOf(DeviceProfileStore.selectedModelId(prefs)) }
    var modelQuery by remember { mutableStateOf("") }
    val showModelPicker = remember { mutableStateOf(false) }
    val showMelodyImport = remember { mutableStateOf(false) }
    val showResetAllConfirm = remember { mutableStateOf(false) }
    val pickFailed = remember { mutableStateOf(false) }

    // 素材变动后自增，用来让下面的「已自定义/默认」标签重新读取磁盘状态。
    var imageRevision by remember { mutableStateOf(0) }
    var pendingSlot by remember { mutableStateOf<PodImageSlot?>(null) }
    var colorChoice by remember {
        mutableStateOf(prefs.getInt("official_artwork_color", -1))
    }
    val artworkState by OfficialArtworkRepository.state.collectAsState()

    LaunchedEffect(activeProfile.modelId) {
        OfficialArtworkRepository.refresh(context)
    }

    val selectedModelName = remember(selectedModelId, allModels) {
        allModels.firstOrNull { it.id == selectedModelId }?.name
    }

    // 自动模式下若已通过蓝牙名或 0x8103 命中白名单，标出识别到的型号名。
    val autoSummary = if (CapabilityProfileFactory.isGenerated(activeProfile.id)) {
        stringResource(R.string.profiles_mode_auto_identified, activeProfile.name)
    } else {
        stringResource(R.string.profiles_mode_auto_summary)
    }

    fun publish() {
        runCatching { DeviceProfileStore.resolveProfile(context, prefs) }
            .getOrNull()
            ?.let(onActiveProfileChanged)
    }

    fun selectMode(next: ProfileMode) {
        mode = next
        DeviceProfileStore.setProfileMode(prefs, next)
        publish()
    }

    fun selectModel(modelId: String) {
        selectedModelId = modelId
        DeviceProfileStore.setSelectedModelId(prefs, modelId)
        mode = ProfileMode.MODEL
        DeviceProfileStore.setProfileMode(prefs, ProfileMode.MODEL)
        publish()
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri == null || slot == null) return@rememberLauncherForActivityResult
        if (PodImageStore.importFrom(context, slot, uri)) {
            imageRevision++
        } else {
            pickFailed.value = true
        }
    }

    fun pick(slot: PodImageSlot) {
        pendingSlot = slot
        val mimeTypes = if (slot == PodImageSlot.CONNECT_VIDEO) {
            arrayOf("video/*")
        } else {
            arrayOf("image/*")
        }
        imagePicker.launch(mimeTypes)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
    ) {
        item {
            SmallTitle(text = stringResource(R.string.profiles_mode))
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
            ) {
                RadioButtonPreference(
                    title = stringResource(R.string.profiles_mode_auto),
                    summary = autoSummary,
                    selected = mode == ProfileMode.AUTO,
                    onClick = { selectMode(ProfileMode.AUTO) },
                    radioButtonLocation = RadioButtonLocation.End,
                )
                RadioButtonPreference(
                    title = stringResource(R.string.profiles_mode_model),
                    summary = selectedModelName
                        ?: stringResource(R.string.profiles_mode_model_summary, allModels.size),
                    selected = mode == ProfileMode.MODEL,
                    onClick = {
                        modelQuery = ""
                        showModelPicker.value = true
                    },
                    radioButtonLocation = RadioButtonLocation.End,
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.pod_images))
        }
        item {
            val modelId = activeProfile.modelId
            val key = artworkState.key
            val enabled = modelId.isNotBlank() && !artworkState.running
            val summary = when {
                artworkState.running -> artworkState.message
                    ?: stringResource(R.string.official_artwork_summary)
                artworkState.message != null -> artworkState.message!!
                artworkState.imagesReady && key != null ->
                    stringResource(R.string.official_artwork_cached, key.productId, key.color)
                !modelId.isNotBlank() -> stringResource(R.string.official_artwork_need_model)
                else -> stringResource(R.string.official_artwork_summary)
            }
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
            ) {
                BasicComponent(
                    title = stringResource(R.string.official_artwork_title),
                    summary = summary,
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.official_artwork_color),
                    items = listOf(stringResource(R.string.official_artwork_color_auto)) +
                            (0..7).map { stringResource(R.string.official_artwork_color_value, it) },
                    selectedIndex = if (colorChoice >= 0) colorChoice + 1 else 0,
                    onSelectedIndexChange = {
                        colorChoice = if (it == 0) -1 else it - 1
                        prefs.edit().putInt("official_artwork_color", colorChoice).apply()
                    },
                )
                val preferredColor = colorChoice.takeIf { it >= 0 } ?: deviceColor
                if (artworkState.modelReady) {
                    Text(
                        text = stringResource(
                            R.string.official_artwork_model_cached,
                            (artworkState.modelBytes / 1024 / 1024).toInt(),
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        text = stringResource(R.string.official_artwork_download_images),
                        onClick = {
                            OfficialArtworkRepository.downloadImages(context, modelId, preferredColor)
                        },
                        enabled = enabled,
                    )
                    TextButton(
                        text = stringResource(R.string.official_artwork_download_model),
                        onClick = {
                            OfficialArtworkRepository.downloadModel(context, modelId, preferredColor)
                        },
                        enabled = enabled && artworkState.imagesReady,
                    )
                }
                if (artworkState.running) {
                    InfiniteProgressIndicator(
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
            ) {
                BasicComponent(
                    title = stringResource(R.string.import_melody_images),
                    summary = stringResource(R.string.import_melody_images_summary),
                    onClick = { showMelodyImport.value = true },
                )
                ImageSlotRow(
                    titleRes = R.string.pod_image_home,
                    slot = PodImageSlot.HOME_IMAGE,
                    revision = imageRevision,
                    onPick = { pick(PodImageSlot.HOME_IMAGE) },
                    onReset = {
                        PodImageStore.clear(context, PodImageSlot.HOME_IMAGE)
                        imageRevision++
                    },
                )
                ImageSlotRow(
                    titleRes = R.string.pod_image_island_left,
                    slot = PodImageSlot.ISLAND_LEFT,
                    revision = imageRevision,
                    onPick = { pick(PodImageSlot.ISLAND_LEFT) },
                    onReset = {
                        PodImageStore.clear(context, PodImageSlot.ISLAND_LEFT)
                        imageRevision++
                    },
                )
                ImageSlotRow(
                    titleRes = R.string.pod_image_island_right,
                    slot = PodImageSlot.ISLAND_RIGHT,
                    revision = imageRevision,
                    onPick = { pick(PodImageSlot.ISLAND_RIGHT) },
                    onReset = {
                        PodImageStore.clear(context, PodImageSlot.ISLAND_RIGHT)
                        imageRevision++
                    },
                )
                ImageSlotRow(
                    titleRes = R.string.pod_image_box,
                    slot = PodImageSlot.BOX_IMAGE,
                    revision = imageRevision,
                    onPick = { pick(PodImageSlot.BOX_IMAGE) },
                    onReset = {
                        PodImageStore.clear(context, PodImageSlot.BOX_IMAGE)
                        imageRevision++
                    },
                )
                ImageSlotRow(
                    titleRes = R.string.pod_image_connect_video,
                    slot = PodImageSlot.CONNECT_VIDEO,
                    revision = imageRevision,
                    onPick = { pick(PodImageSlot.CONNECT_VIDEO) },
                    onReset = {
                        PodImageStore.clear(context, PodImageSlot.CONNECT_VIDEO)
                        imageRevision++
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.pod_image_reset_all),
                    onClick = { showResetAllConfirm.value = true },
                )
            }
        }
    }

    // ------------------------------------------------------------ 型号选择

    OverlayDialog(
        title = stringResource(R.string.profiles_model_picker),
        show = showModelPicker.value,
        onDismissRequest = { showModelPicker.value = false },
    ) {
        val filtered = remember(modelQuery, allModels) {
            val q = modelQuery.trim().lowercase()
            if (q.isEmpty()) allModels
            else allModels.filter {
                it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
            }
        }

        TextField(
            value = modelQuery,
            onValueChange = { modelQuery = it },
            label = stringResource(R.string.profiles_model_search),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.profiles_model_not_found),
                modifier = Modifier.padding(vertical = 24.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(filtered, key = { it.id }) { model ->
                    RadioButtonPreference(
                        title = model.name,
                        summary = model.id,
                        selected = model.id == selectedModelId,
                        onClick = {
                            selectModel(model.id)
                            showModelPicker.value = false
                        },
                        radioButtonLocation = RadioButtonLocation.End,
                    )
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showModelPicker.value = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    MelodyImageImportDialog(
        show = showMelodyImport.value,
        onDismissRequest = { showMelodyImport.value = false },
        onImported = { imageRevision++ },
    )

    OverlayDialog(
        title = stringResource(R.string.pod_image_pick_failed),
        show = pickFailed.value,
        onDismissRequest = { pickFailed.value = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = { pickFailed.value = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    OverlayDialog(
        title = stringResource(R.string.pod_image_reset_all),
        summary = stringResource(R.string.pod_image_reset_all_confirm),
        show = showResetAllConfirm.value,
        onDismissRequest = { showResetAllConfirm.value = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { showResetAllConfirm.value = false },
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    PodImageStore.clearAll(context)
                    imageRevision++
                    showResetAllConfirm.value = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

/** 素材行：点标题选文件，已自定义时右侧出现「恢复默认」。 */
@Composable
private fun ImageSlotRow(
    titleRes: Int,
    slot: PodImageSlot,
    revision: Int,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val hasCustom = remember(slot, revision) { PodImageStore.hasCustom(context, slot) }

    BasicComponent(
        title = stringResource(titleRes),
        summary = stringResource(
            if (hasCustom) R.string.pod_image_custom else R.string.pod_image_default
        ),
        onClick = onPick,
        endActions = {
            if (hasCustom) {
                TextButton(
                    text = stringResource(R.string.pod_image_reset),
                    onClick = onReset,
                )
            }
        }
    )
}
