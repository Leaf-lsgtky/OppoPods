package moe.chenxy.oppopods.pods

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 欢律官方素材的本地缓存。
 *
 * 目录布局：
 * ```
 * files/official_artwork/current.json
 * files/official_artwork/images/<productId>_<color>/{detail,left,right,box,capsule,hearing}.png
 * files/official_artwork/models/<productId>_<color>/model.bin
 * ```
 *
 * `model.bin` 可能是 `.vfxms`，也可能是 X4 的动态 WebP，由 UI 按魔数判断。
 * 图片与模型分开存储，重新下载图片不会删掉已缓存的 3D 模型。指针文件用临时文件
 * 替换，避免进程中断后留下半个 JSON。
 */
object OfficialArtworkStore {
    private const val TAG = "OppoPods-ArtworkStore"
    private const val ROOT = "official_artwork"
    private const val POINTER = "current.json"

    enum class Kind(val fileName: String) {
        DETAIL("detail.png"),
        LEFT("left.png"),
        RIGHT("right.png"),
        BOX("box.png"),
        CAPSULE("capsule.png"),
        HEARING("hearing.png"),
        MODEL("model.bin"),
    }

    data class Key(val productId: String, val color: Int) {
        val directoryName: String get() = "${productId}_$color"
    }

    private fun root(context: Context): File = File(context.filesDir, ROOT)
    private fun imagesRoot(context: Context): File = File(root(context), "images")
    private fun modelsRoot(context: Context): File = File(root(context), "models")

    fun currentKey(context: Context): Key? = runCatching {
        val file = File(root(context), POINTER)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        val productId = json.optString("productId")
        val color = json.optInt("color", -1)
        if (!productId.matches(Regex("^[0-9A-F]{6}$")) || color < 0) null
        else Key(productId, color)
    }.getOrNull()

    fun imagesDirectory(context: Context, key: Key): File =
        File(imagesRoot(context), key.directoryName)

    fun modelsDirectory(context: Context, key: Key): File =
        File(modelsRoot(context), key.directoryName)

    fun file(context: Context, kind: Kind): File? {
        if (kind == Kind.MODEL) return modelFile(context)
        val key = currentKey(context) ?: return null
        val file = File(imagesDirectory(context, key), kind.fileName)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun modelFile(context: Context): File? {
        val key = currentKey(context) ?: return null
        val file = File(modelsDirectory(context, key), Kind.MODEL.fileName)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun imagesReady(context: Context): Boolean =
        file(context, Kind.DETAIL) != null &&
                file(context, Kind.LEFT) != null &&
                file(context, Kind.RIGHT) != null

    fun hasImages(context: Context, key: Key): Boolean {
        val dir = imagesDirectory(context, key)
        return File(dir, Kind.DETAIL.fileName).isFile &&
                File(dir, Kind.LEFT.fileName).isFile &&
                File(dir, Kind.RIGHT.fileName).isFile
    }

    fun selectExisting(context: Context, key: Key): Boolean {
        if (hasImages(context, key)) {
            writePointer(context, key)
            return true
        }
        return false
    }

    fun modelSize(context: Context): Long = modelFile(context)?.length() ?: 0L

    fun publishImages(
        context: Context,
        key: Key,
        artwork: HeyMelodyArtworkClient.ControlArtwork,
    ): Boolean = runCatching {
        val staging = File(imagesRoot(context), ".staging-${UUID.randomUUID()}")
        if (!staging.mkdirs()) return false
        var count = 0
        fun write(kind: Kind, bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) return
            File(staging, kind.fileName).writeBytes(bytes)
            count += 1
        }
        write(Kind.DETAIL, artwork.detail)
        write(Kind.LEFT, artwork.left)
        write(Kind.RIGHT, artwork.right)
        write(Kind.BOX, artwork.box)
        write(Kind.CAPSULE, artwork.capsule)
        write(Kind.HEARING, artwork.hearing)
        if (count == 0) {
            staging.deleteRecursively()
            return false
        }
        File(staging, "meta.json").writeText(
            JSONObject()
                .put("productId", key.productId)
                .put("color", key.color)
                .put("savedAt", System.currentTimeMillis())
                .toString(),
        )
        val finalDir = imagesDirectory(context, key)
        if (finalDir.exists()) finalDir.deleteRecursively()
        if (!staging.renameTo(finalDir)) {
            if (!staging.copyRecursively(finalDir, overwrite = true)) {
                staging.deleteRecursively()
                return false
            }
            staging.deleteRecursively()
        }
        writePointer(context, key)
        true
    }.onFailure { Log.w(TAG, "publishImages failed", it) }.getOrDefault(false)

    fun publishModel(context: Context, key: Key, glb: ByteArray): Boolean = runCatching {
        if (glb.isEmpty()) return false
        val directory = modelsDirectory(context, key)
        if (!directory.exists() && !directory.mkdirs()) return false
        val target = File(directory, Kind.MODEL.fileName)
        val temp = File(directory, "${Kind.MODEL.fileName}.tmp")
        temp.writeBytes(glb)
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        if (currentKey(context) != key) writePointer(context, key)
        true
    }.onFailure { Log.w(TAG, "publishModel failed", it) }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { root(context).deleteRecursively() }
    }

    private fun writePointer(context: Context, key: Key) {
        val directory = root(context)
        if (!directory.exists()) directory.mkdirs()
        val target = File(directory, POINTER)
        val temp = File(directory, "$POINTER.tmp")
        temp.writeText(
            JSONObject()
                .put("productId", key.productId)
                .put("color", key.color)
                .toString(),
        )
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}

/** 官方素材下载状态，供设置页显示。 */
data class OfficialArtworkState(
    val running: Boolean = false,
    val message: String? = null,
    val key: OfficialArtworkStore.Key? = null,
    val imagesReady: Boolean = false,
    val modelReady: Boolean = false,
    val modelBytes: Long = 0L,
    val error: Boolean = false,
)

/**
 * 序列化下载任务：先取控制包（颜色自动探测），再按需取 3D 包。
 * 所有网络与解压都在 IO 线程；UI 只读 [state]。
 */
object OfficialArtworkRepository {
    private const val TAG = "OppoPods-ArtworkRepo"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(OfficialArtworkState())
    val state: StateFlow<OfficialArtworkState> = _state.asStateFlow()

    fun refresh(context: Context) {
        _state.value = snapshot(context)
    }

    /**
     * 当外部识别到设备具体配色（如 0x810B 回报或持久化恢复）时，
     * 若本地已存在该配色的缓存素材且当前未指向它，则自动将指针切到该配色。
     */
    fun syncColor(context: Context, productId: String, color: Int) {
        if (productId.isBlank() || color < 0) return
        val current = OfficialArtworkStore.currentKey(context)
        if (current == null || (current.productId.equals(productId, ignoreCase = true) && current.color != color)) {
            val key = OfficialArtworkStore.Key(productId, color)
            if (OfficialArtworkStore.selectExisting(context, key)) {
                DeviceAppearanceStore.saveColor(context, null, productId, color)
                refresh(context)
            }
        }
    }

    fun downloadImages(context: Context, productId: String, preferredColor: Int?) {
        if (productId.isBlank()) {
            _state.value = _state.value.copy(
                message = "尚未识别耳机型号，请先连接耳机或手动选择型号。",
                error = true,
            )
            return
        }
        scope.launch {
            mutex.withLock {
                downloadImagesLocked(context.applicationContext, productId, preferredColor)
            }
        }
    }

    fun downloadModel(context: Context, productId: String, preferredColor: Int?) {
        if (productId.isBlank()) {
            _state.value = _state.value.copy(message = "尚未识别耳机型号。", error = true)
            return
        }
        scope.launch {
            mutex.withLock {
                downloadModelLocked(context.applicationContext, productId, preferredColor)
            }
        }
    }

    fun clear(context: Context) {
        OfficialArtworkStore.clear(context)
        refresh(context)
    }

    private fun downloadImagesLocked(
        context: Context,
        productId: String,
        preferredColor: Int?,
    ) {
        _state.value = _state.value.copy(running = true, message = "正在获取官方素材…", error = false)
        Log.d(TAG, "downloadImages product=$productId preferred=$preferredColor")

        val targetColor = preferredColor
            ?: DeviceAppearanceStore.getColor(context, null, productId)
            ?: ModelColorCatalog.getDefaultColor(productId)

        val candidates = buildList {
            targetColor?.let { add(it) }
            addAll(ModelColorCatalog.getColorOptions(productId).map { it.colorId })
            add(1)
            add(2)
            add(3)
            add(0)
        }.distinct()

        // 如果首选配色本地已有完整素材，直接切换指针，避免无谓的网络请求
        if (targetColor != null) {
            val key = OfficialArtworkStore.Key(productId, targetColor)
            if (OfficialArtworkStore.selectExisting(context, key)) {
                DeviceAppearanceStore.saveColor(context, null, productId, targetColor)
                val name = ModelColorCatalog.getColorName(productId, targetColor)
                _state.value = snapshot(context, "官方素材已更新（$name）")
                return
            }
        }

        for (color in candidates) {
            val colorName = ModelColorCatalog.getColorName(productId, color)
            Log.d(TAG, "try artwork color=$color ($colorName)")
            _state.value = _state.value.copy(message = "正在尝试 $colorName…")
            val resource = HeyMelodyArtworkClient.fetchControlMetadata(productId, color)
            if (resource == null) continue
            val temp = tempFile(context, "control")
            try {
                if (!HeyMelodyArtworkClient.downloadToFile(resource, temp)) continue
                val artwork = HeyMelodyArtworkClient.readControl(temp) ?: continue
                if (!artwork.hasCoreImages) continue
                val key = OfficialArtworkStore.Key(productId, color)
                if (OfficialArtworkStore.publishImages(context, key, artwork)) {
                    DeviceAppearanceStore.saveColor(context, null, productId, color)
                    _state.value = snapshot(context, "官方素材已更新（$colorName）")
                    return
                }
            } finally {
                temp.delete()
            }
        }
        _state.value = snapshot(context, "未找到该型号的官方素材，请稍后重试。", error = true)
    }

    private fun downloadModelLocked(
        context: Context,
        productId: String,
        preferredColor: Int?,
    ) {
        val current = OfficialArtworkStore.currentKey(context)
        if (current == null || current.productId != productId) {
            _state.value = snapshot(context, "请先下载官方图片素材。", error = true)
            return
        }
        _state.value = _state.value.copy(running = true, message = "正在获取 3D 模型…", error = false)
        val color = preferredColor?.takeIf { it == current.color } ?: current.color
        val colorName = ModelColorCatalog.getColorName(productId, color)
        val resource = HeyMelodyArtworkClient.fetchModelMetadata(productId, color)
        if (resource == null) {
            _state.value = snapshot(context, "该型号没有可用的 3D 模型。", error = true)
            return
        }
        val temp = tempFile(context, "model")
        try {
            if (!HeyMelodyArtworkClient.downloadToFile(resource, temp)) {
                _state.value = snapshot(context, "3D 模型下载或校验失败。", error = true)
                return
            }
            val glb = HeyMelodyArtworkClient.readModel(temp)
            if (glb == null) {
                _state.value = snapshot(context, "3D 模型格式无效。", error = true)
                return
            }
            if (OfficialArtworkStore.publishModel(context, current, glb)) {
                _state.value = snapshot(context, "3D 模型已缓存（${glb.size / 1024 / 1024} MB · $colorName）")
            } else {
                _state.value = snapshot(context, "3D 模型写入失败。", error = true)
            }
        } finally {
            temp.delete()
        }
    }

    private fun tempFile(context: Context, kind: String): File {
        val directory = File(context.cacheDir, "heymelody-artwork")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "$kind-${UUID.randomUUID()}.zip")
    }

    private fun snapshot(
        context: Context,
        message: String? = null,
        error: Boolean = false,
    ): OfficialArtworkState {
        val key = OfficialArtworkStore.currentKey(context)
        return OfficialArtworkState(
            running = false,
            message = message,
            key = key,
            imagesReady = OfficialArtworkStore.imagesReady(context),
            modelReady = OfficialArtworkStore.modelFile(context) != null,
            modelBytes = OfficialArtworkStore.modelSize(context),
            error = error,
        )
    }
}
