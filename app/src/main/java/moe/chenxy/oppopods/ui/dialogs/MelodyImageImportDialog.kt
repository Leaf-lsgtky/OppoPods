package moe.chenxy.oppopods.ui.dialogs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.utils.MelodyImageCandidate
import moe.chenxy.oppopods.utils.RootManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从欢律（com.heytap.headset）导入官方机型图片。
 *
 * 欢律连接过耳机后会把该型号的图片缓存到私有目录，这里经 root 读出来直接
 * 写进 [PodImageStore]，省去用户自己找图。需要 root；无 root 时给出提示。
 */
@Composable
internal fun MelodyImageImportDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var candidates by remember(show) { mutableStateOf<List<MelodyImageCandidate>>(emptyList()) }
    var selected by remember(show) { mutableStateOf<MelodyImageCandidate?>(null) }
    var hasRoot by remember(show) { mutableStateOf(true) }
    var loading by remember(show) { mutableStateOf(false) }
    var importing by remember(show) { mutableStateOf(false) }
    var failed by remember(show) { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        loading = true
        failed = false
        hasRoot = withContext(Dispatchers.IO) { RootManager.hasRootAccess() }
        candidates = if (hasRoot) {
            withContext(Dispatchers.IO) { RootManager.scanMelodyImageCandidates() }
        } else {
            emptyList()
        }
        selected = candidates.firstOrNull()
        loading = false
    }

    OverlayDialog(
        title = stringResource(R.string.import_melody_images),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(R.string.import_melody_images_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfiniteProgressIndicator()
            }

            !hasRoot -> Text(
                text = stringResource(R.string.import_melody_images_root_required),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            candidates.isEmpty() -> Text(
                text = stringResource(R.string.import_melody_images_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(candidates, key = { it.imageDir }) { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        selected = candidate.imageDir == selected?.imageDir,
                        onClick = { selected = candidate },
                    )
                }
            }
        }

        if (failed) {
            Text(
                text = stringResource(R.string.import_melody_images_failed),
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = stringResource(R.string.import_melody_images_action),
                enabled = selected != null && !importing,
                onClick = {
                    val candidate = selected ?: return@TextButton
                    importing = true
                    failed = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            // 欢律的 img_left/img_right 是以佩戴者视角命名的，与本模块
                            // 超级岛左右耳槽位一致，直接对应即可。
                            val sources = mapOf(
                                PodImageSlot.HOME_IMAGE to candidate.boxPath,
                                PodImageSlot.ISLAND_LEFT to candidate.leftPath,
                                PodImageSlot.ISLAND_RIGHT to candidate.rightPath,
                            )
                            val loaded = sources.mapNotNull { (slot, path) ->
                                RootManager.readMelodyImage(path)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { slot to it }
                            }
                            if (loaded.size != sources.size) return@withContext false
                            loaded.all { (slot, bytes) -> PodImageStore.save(context, slot, bytes) }
                        }
                        importing = false
                        if (ok) {
                            onImported()
                            onDismissRequest()
                        } else {
                            failed = true
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MelodyImageCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val preview = remember(candidate.imageDir) {
        BitmapFactory.decodeByteArray(candidate.boxBytes, 0, candidate.boxBytes.size)
    }?.let { BitmapPainter(it.asImageBitmap()) } ?: painterResource(R.drawable.img_box)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = preview,
            contentDescription = candidate.label,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = candidate.label, color = MiuixTheme.colorScheme.onSurface)
        }
        if (selected) {
            Text(text = "✓", color = MiuixTheme.colorScheme.primary)
        }
    }
}
