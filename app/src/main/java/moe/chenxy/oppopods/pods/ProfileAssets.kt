package moe.chenxy.oppopods.pods

import android.content.Context
import android.net.Uri
import moe.chenxy.oppopods.R
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置档的资源文件（首页图/岛图/连接视频）管理：存储在 filesDir/profiles/<id>/，
 * 跨进程经 [ProfileAssetProvider] 以 content:// 读取；导入导出为 zip 捆绑包。
 */
object ProfileAssets {
    const val AUTHORITY = "moe.chenxy.oppopods.assets"
    private const val PROFILE_JSON_ENTRY = "profile.json"

    /** 内置资源文件名 → Android res id，用于导出时回退打包内置资源。 */
    private val BUILTIN_RES_IDS: Map<String, Int> = mapOf(
        "img_box.png" to R.drawable.img_box,
        "img_left.png" to R.drawable.img_left,
        "img_right.png" to R.drawable.img_right,
        "boot_connected_state.mp4" to R.raw.boot_connected_state,
    )

    /** 内置资源的 AssetKey → 文件名映射，用于 assets 为空时回退。 */
    private val BUILTIN_ASSETS: Map<String, String> = mapOf(
        AssetKeys.HOME_IMAGE to "img_box.png",
        AssetKeys.ISLAND_LEFT to "img_left.png",
        AssetKeys.ISLAND_RIGHT to "img_right.png",
        AssetKeys.CONNECT_VIDEO to "boot_connected_state.mp4",
    )

    private fun profilesRoot(context: Context): File = File(context.filesDir, "profiles")
    fun dir(context: Context, profileId: String): File = File(profilesRoot(context), sanitize(profileId))

    /** 该档某资源的实际文件（存在才返回，否则 null → 调用方回退内置资源）。 */
    fun file(context: Context, profile: DeviceProfile, key: String): File? {
        val name = profile.assets[key]?.takeIf { it.isNotBlank() } ?: return null
        val f = File(dir(context, profile.id), sanitize(name))
        return if (f.exists()) f else null
    }

    /** 跨进程读取用的 content URI（content://AUTHORITY/<profileId>/<fileName>）。 */
    fun assetUri(profile: DeviceProfile, key: String): Uri? {
        val name = profile.assets[key]?.takeIf { it.isNotBlank() } ?: return null
        return Uri.parse("content://$AUTHORITY/${Uri.encode(profile.id)}/${Uri.encode(name)}")
    }

    /** ContentProvider 解析 uri → 实际文件（带目录穿越防护）。 */
    fun resolve(context: Context, uri: Uri): File? {
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val f = File(dir(context, segs[0]), sanitize(segs[1]))
        if (!f.canonicalPath.startsWith(profilesRoot(context).canonicalPath + File.separator)) return null
        return if (f.exists()) f else null
    }

    fun deleteAssets(context: Context, profileId: String) {
        runCatching { dir(context, profileId).deleteRecursively() }
    }

    /**
     * 导入 zip：解析 profile.json（强制分配新 id，避免与已有档/资源目录冲突），
     * 其余文件解压到 profiles/<newId>/。返回该 DeviceProfile（其 assets 文件名对应解压出的文件）。
     */
    fun importZip(context: Context, input: InputStream): DeviceProfile {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[File(entry.name).name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val jsonBytes = entries[PROFILE_JSON_ENTRY] ?: error("missing $PROFILE_JSON_ENTRY")
        val profile = DeviceProfileStore.parse(jsonBytes.toString(Charsets.UTF_8))
            .copy(id = "profile_" + UUID.randomUUID().toString().take(8))
        val targetDir = dir(context, profile.id).apply { mkdirs() }
        for ((name, bytes) in entries) {
            if (name == PROFILE_JSON_ENTRY) continue
            File(targetDir, sanitize(name)).writeBytes(bytes)
        }
        return profile
    }

    /** 导出 zip：profile.json + 该档的资源文件（自定义文件优先，否则回退内置 APK 资源）。 */
    fun exportZip(context: Context, profile: DeviceProfile, output: OutputStream) {
        // assets 为空时用内置映射补全，确保导出的 profile.json 和 zip 都包含资源声明
        val effectiveProfile = if (profile.assets.isEmpty()) profile.copy(assets = BUILTIN_ASSETS) else profile
        ZipOutputStream(output).use { zos ->
            zos.putNextEntry(ZipEntry(PROFILE_JSON_ENTRY))
            zos.write(DeviceProfileStore.exportJson(effectiveProfile).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            for (name in effectiveProfile.assets.values) {
                val safeName = sanitize(name)
                val customFile = File(dir(context, profile.id), safeName)
                if (customFile.exists()) {
                    zos.putNextEntry(ZipEntry(safeName))
                    customFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                } else {
                    val resId = BUILTIN_RES_IDS[safeName]
                    if (resId != null) {
                        zos.putNextEntry(ZipEntry(safeName))
                        context.resources.openRawResource(resId).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    private fun sanitize(name: String): String = File(name).name
}
