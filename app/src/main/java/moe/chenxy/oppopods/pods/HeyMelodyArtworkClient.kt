package moe.chenxy.oppopods.pods

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 欢律（HeyMelody）官方耳机素材接口。
 *
 * 与桌面版 `VendorResourceClient` 使用同一套服务：
 * - `v1/earphone/resource/control`：左耳、右耳、充电盒、详情图、胶囊图等；
 * - `v1/earphone/resource/fetch` + `moduleId=14`：3D 模型（`.vfxms` 容器内的 GLB）。
 *
 * 请求体必须原样参与 HMAC-SHA1 签名；`ts`/`nonce` 每次随机。返回包按 `sha256`
 * 校验后才解压，压缩包成员做路径穿越、大小和数量限制。
 */
object HeyMelodyArtworkClient {
    private const val TAG = "OppoPods-ArtworkApi"
    private const val API_HOST = "iot-earbuds-cn.heytapmobi.com"
    private const val API_BASE = "https://$API_HOST/"
    private const val APP_KEY = "&*%earphone-OP7u3423**%$"
    private const val COMPAT_VERSION = 17006004

    private val DOWNLOAD_HOSTS = setOf(
        "iot-earbuds-cn.heytapimage.com",
        "iot-heythings-cn.heytapimage.com",
    )
    private val PRODUCT_ID = Regex("^[0-9A-Fa-f]{6}$")

    private const val MAX_METADATA = 1L * 1024 * 1024
    private const val MAX_ARCHIVE = 64L * 1024 * 1024
    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_IMAGE = 8L * 1024 * 1024
    private const val MAX_MEMBERS = 2048

    data class RemoteResource(
        val url: String,
        val sha256: String,
        val fileName: String?,
    )

    /** 控制包中与本模块相关的图片；缺失的项为 null，调用方回退内置资源。 */
    data class ControlArtwork(
        val detail: ByteArray?,
        val left: ByteArray?,
        val right: ByteArray?,
        val box: ByteArray?,
        val capsule: ByteArray?,
        val hearing: ByteArray?,
    ) {
        val hasCoreImages: Boolean
            get() = detail != null && left != null && right != null
    }

    fun fetchControlMetadata(productId: String, color: Int): RemoteResource? =
        fetchMetadata("v1/earphone/resource/control", productId, color, model = false)

    fun fetchModelMetadata(productId: String, color: Int): RemoteResource? =
        fetchMetadata("v1/earphone/resource/fetch", productId, color, model = true)

    private fun fetchMetadata(
        path: String,
        productId: String,
        color: Int,
        model: Boolean,
    ): RemoteResource? {
        if (!PRODUCT_ID.matches(productId) || color !in 0..15) return null
        val body = buildBody(productId, color, model)
        val json = postJson(path, body) ?: return null
        if (json.optInt("code", -1) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        val url = data.optString(if (model) "resourceUrl" else "cartoonUrl").trim()
        val hash = data.optString("sha256").trim().lowercase()
        if (url.isEmpty() || !isAllowedDownloadUri(url)) return null
        if (hash.length != 64 || hash.any { it !in "0123456789abcdef" }) return null
        return RemoteResource(
            url = url,
            sha256 = hash,
            fileName = data.optString("fileName").takeIf { it.isNotBlank() },
        )
    }

    /**
     * 下载并校验资源包。成功返回 true，文件已写入 [target]；失败会删除半成品。
     * 重定向只允许跳到欢律图片域名，最多 3 次。
     */
    fun downloadToFile(resource: RemoteResource, target: File): Boolean {
        var current = runCatching { URL(resource.url) }.getOrNull() ?: return false
        var digest = MessageDigest.getInstance("SHA-256")
        var redirects = 0
        while (true) {
            if (!isAllowedDownloadUri(current.toString())) return false
            val connection = runCatching {
                (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "*/*")
                }
            }.getOrNull() ?: return false
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirects >= 3) return false
                    redirects += 1
                    current = runCatching { URL(current, location) }.getOrNull() ?: return false
                    continue
                }
                if (code !in 200..299) {
                    Log.w(TAG, "download rejected: HTTP $code ${resource.url}")
                    return false
                }
                val length = connection.contentLengthLong
                if (length > MAX_ARCHIVE) {
                    Log.w(TAG, "download too large: $length")
                    return false
                }
                target.parentFile?.mkdirs()
                digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(81920)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ARCHIVE) throw IOException("archive exceeds limit")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (actual != resource.sha256) {
                    Log.w(TAG, "sha256 mismatch: $actual != ${resource.sha256}")
                    target.delete()
                    return false
                }
                return true
            } catch (error: Exception) {
                Log.w(TAG, "download failed: ${resource.url}", error)
                target.delete()
                return false
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 从控制包中读取 config.json 声明的图片。 */
    fun readControl(zip: File): ControlArtwork? {
        if (!zip.isFile || zip.length() <= 0 || zip.length() > MAX_ARCHIVE) return null
        return runCatching {
            ZipFile(zip).use { archive ->
                if (archive.size() > MAX_MEMBERS) return null
                val configText = readEntryText(archive, "config.json", MAX_METADATA) ?: return null
                val root = JSONObject(configText)
                fun image(key: String): ByteArray? {
                    val path = findPath(root.opt(key)) ?: return null
                    return readEntryBytes(archive, path, MAX_IMAGE)
                }
                ControlArtwork(
                    detail = image("detailImageRes"),
                    left = image("leftImageRes"),
                    right = image("rightImageRes"),
                    box = image("boxImageRes"),
                    capsule = image("capsuleImageRes"),
                    hearing = image("hearingEnhancementRes"),
                )
            }
        }.onFailure { Log.w(TAG, "failed to parse control package", it) }.getOrNull()
    }

    /**
     * 从 3D 包中取出模型资源。
     *
     * 不是所有型号都返回 `.vfxms`：X4 的模块 14 实际是 `modelWebp` 动画
     * （780×780、180 帧的 RIFF WebP）。这里同时接受 `.vfxms` 和 `.webp`，
     * 由 UI 按魔数选择 Filament 或逐帧动画。
     */
    fun readModel(zip: File): ByteArray? {
        if (!zip.isFile || zip.length() <= 0 || zip.length() > MAX_ARCHIVE) return null
        return runCatching {
            ZipFile(zip).use { archive ->
                if (archive.size() > MAX_MEMBERS) return null
                val configText = readEntryText(archive, "config.json", MAX_METADATA) ?: return null
                val root = JSONObject(configText)
                val path = findPath(root.opt("model3D"))
                    ?: findPath(root.opt("modelWebp"))
                    ?: return null
                val raw = readEntryBytes(archive, path, MAX_ARCHIVE) ?: return null
                when {
                    isWebp(raw) -> raw
                    VfxModel.parse(raw) != null -> raw
                    else -> null
                }
            }
        }.onFailure { Log.w(TAG, "failed to parse model package", it) }.getOrNull()
    }

    fun isWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP"
    }

    /** 供单测与诊断使用，生成与桌面端一致的请求体。 */
    fun buildBody(productId: String? = null, color: Int? = null, model: Boolean = false): String {
        val body = StringBuilder()
        body.append("{\"platform\":\"android\",\"channel\":\"2\",\"versionCode\":\"")
        body.append(COMPAT_VERSION)
        body.append('"')
        if (productId != null) {
            body.append(",\"productId\":\"").append(productId).append('"')
            body.append(",\"color\":\"").append(color ?: 0).append('"')
        }
        if (model) body.append(",\"moduleId\":\"14\"")
        body.append('}')
        return body.toString()
    }

    fun isAllowedDownloadUri(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol.equals("https", ignoreCase = true) && url.host in DOWNLOAD_HOSTS
    }.getOrDefault(false)

    private fun postJson(path: String, body: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(API_BASE + path)
            if (url.host != API_HOST) return null
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("appid", "earphone")
                setRequestProperty("sv", "v1")
                setRequestProperty("ts", System.currentTimeMillis().toString())
                setRequestProperty("nonce", UUID.randomUUID().toString())
                setRequestProperty("sign", sign(body))
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "metadata HTTP $code for $path")
                return null
            }
            val bytes = connection.inputStream.use { readLimited(it, MAX_METADATA) }
            JSONObject(bytes.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            Log.w(TAG, "metadata request failed: $path", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(APP_KEY.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val hash = mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun findPath(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 8) return null
        return when (node) {
            is JSONObject -> {
                val path = node.optString("path", "")
                if (path.isNotBlank()) {
                    path
                } else {
                    var found: String? = null
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        found = findPath(node.opt(keys.next()), depth + 1)
                        if (found != null) break
                    }
                    found
                }
            }
            is JSONArray -> {
                var found: String? = null
                for (index in 0 until node.length()) {
                    found = findPath(node.opt(index), depth + 1)
                    if (found != null) break
                }
                found
            }
            is String -> node.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun readEntryText(archive: ZipFile, name: String, limit: Long): String? {
        val bytes = readEntryBytes(archive, name, limit) ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readEntryBytes(archive: ZipFile, name: String, limit: Long): ByteArray? {
        if (!isSafeArchivePath(name)) return null
        var entry = archive.getEntry(name)
        if (entry == null && !name.substringAfterLast('/').contains('.')) {
            entry = archive.getEntry("$name.png")
        }
        if (entry == null || entry.isDirectory) return null
        if (entry.size > limit) return null
        return archive.getInputStream(entry).use { readLimited(it, limit) }
    }

    private fun isSafeArchivePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
        if (path.contains(':')) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(81920)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("resource exceeds limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
