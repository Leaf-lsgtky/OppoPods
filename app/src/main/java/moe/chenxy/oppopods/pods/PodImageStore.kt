package moe.chenxy.oppopods.pods

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * 可自定义的耳机素材槽位。
 *
 * 素材与机型配置无关——配置由白名单自动生成，素材则完全由用户决定，
 * 所以两者分开存储，互不影响。
 */
enum class PodImageSlot(val key: String, val fileName: String) {
    /** 首页耳机／充电盒图 */
    HOME_IMAGE("home_image", "home_image.bin"),

    /** 连接临时超级岛左耳图 */
    ISLAND_LEFT("island_left", "island_left.bin"),

    /** 连接临时超级岛右耳图 */
    ISLAND_RIGHT("island_right", "island_right.bin"),

    /** 连接弹窗动画视频 */
    CONNECT_VIDEO("connect_video", "connect_video.bin");

    companion object {
        fun fromKey(key: String?): PodImageSlot? = entries.firstOrNull { it.key == key }

        fun fromFileName(name: String?): PodImageSlot? =
            entries.firstOrNull { it.fileName == name }

        /** 图片类槽位（不含视频），用于设置页的图片区。 */
        val IMAGES = listOf(HOME_IMAGE, ISLAND_LEFT, ISLAND_RIGHT)
    }
}

/**
 * 用户自定义耳机素材的存储。
 *
 * 文件放在 filesDir/pod_images/，未设置时调用方回退到 APK 内置资源。
 * 被 hook 的系统进程（com.android.bluetooth / SystemUI）经 [PodImageProvider]
 * 以 content:// 读取。
 */
object PodImageStore {
    const val AUTHORITY = "moe.chenxy.oppopods.assets"
    private const val TAG = "OppoPods-PodImageStore"
    private const val DIR_NAME = "pod_images"

    private fun root(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 该槽位的目标文件（无论是否存在）。 */
    fun file(context: Context, slot: PodImageSlot): File = File(root(context), slot.fileName)

    /** 该槽位已设置的自定义文件；未设置返回 null，调用方回退内置资源。 */
    fun customFile(context: Context, slot: PodImageSlot): File? =
        file(context, slot).takeIf { it.exists() && it.length() > 0 }

    fun hasCustom(context: Context, slot: PodImageSlot): Boolean =
        customFile(context, slot) != null

    /**
     * 跨进程读取用的 URI：content://AUTHORITY/<slotKey>。
     *
     * 不做存在性检查——被 hook 的系统进程读不到本模块的 filesDir，只能由
     * [PodImageProvider] 在模块进程内判定；未设置时 openFile 返回 null，
     * 调用方据此回退内置资源。
     */
    fun uri(slot: PodImageSlot): Uri = Uri.parse("content://$AUTHORITY/${slot.key}")

    /** [PodImageProvider] 解析 uri → 实际文件。只认已知槽位，天然免于目录穿越。 */
    fun resolve(context: Context, uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        // 兼容按文件名请求的形式，便于日后调试
        val slot = PodImageSlot.fromKey(segments.last())
            ?: PodImageSlot.fromFileName(segments.last())
            ?: return null
        return customFile(context, slot)
    }

    fun save(context: Context, slot: PodImageSlot, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            root(context).mkdirs()
            file(context, slot).writeBytes(bytes)
            true
        }.onFailure { Log.e(TAG, "save failed slot=${slot.key}", it) }.getOrDefault(false)
    }

    fun save(context: Context, slot: PodImageSlot, input: InputStream): Boolean = runCatching {
        root(context).mkdirs()
        file(context, slot).outputStream().use { output -> input.copyTo(output) }
        file(context, slot).length() > 0
    }.onFailure { Log.e(TAG, "save failed slot=${slot.key}", it) }.getOrDefault(false)

    /** 从用户选择的 content uri 导入。 */
    fun importFrom(context: Context, slot: PodImageSlot, source: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(source)?.use { save(context, slot, it) } ?: false
    }.onFailure { Log.e(TAG, "import failed slot=${slot.key}", it) }.getOrDefault(false)

    /** 清除该槽位的自定义素材，恢复内置资源。 */
    fun clear(context: Context, slot: PodImageSlot) {
        runCatching { file(context, slot).delete() }
    }

    fun clearAll(context: Context) {
        PodImageSlot.entries.forEach { clear(context, it) }
    }
}
