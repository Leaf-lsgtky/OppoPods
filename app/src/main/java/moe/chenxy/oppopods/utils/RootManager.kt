package moe.chenxy.oppopods.utils

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 欢律（com.heytap.headset）中一套已下载的机型素材。
 *
 * 欢律连接过耳机后会把该型号的图片缓存到 melody-model-download/control_<model>/res/image/，
 * 里面的 img_detail/img_left/img_right 正是官方 App 首页那三张图。
 */
data class MelodyImageCandidate(
    val label: String,
    val imageDir: String,
    val boxPath: String,
    val leftPath: String,
    val rightPath: String,
    val boxBytes: ByteArray,
) {
    // ByteArray 在 data class 里默认按引用比较，列表 key 与去重都会失准，这里按内容比。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MelodyImageCandidate) return false
        return imageDir == other.imageDir
    }

    override fun hashCode(): Int = imageDir.hashCode()
}

/**
 * 经 su 读取欢律缓存的机型素材。
 *
 * 欢律的私有目录只有 root 能进，所以全程走 `su -c`。所有路径都用白名单正则校验后
 * 才交给 shell，且一律单引号转义，避免命令注入。
 */
object RootManager {
    private const val TAG = "OppoPods-MelodyImport"

    private val melodyImagePathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)" +
                "/com\\.heytap\\.headset/files/melody-model-download" +
                "/control_[A-Za-z0-9_-]+/res/image/img_(detail|left|right)\\.png$"
    )
    private val melodyDirPathRegex = Regex(
        "^/(data/(data|user/\\d+|user_de/\\d+)|data_mirror/data_(ce|de)/null/\\d+)" +
                "/com\\.heytap\\.headset/files/melody-model-download$"
    )

    /** 欢律私有目录在各 ROM 上的可能位置，按命中优先级排列。 */
    private val melodyModelDirs = listOf(
        "/data_mirror/data_ce/null/0/com.heytap.headset/files/melody-model-download",
        "/data_mirror/data_de/null/0/com.heytap.headset/files/melody-model-download",
        "/data/data/com.heytap.headset/files/melody-model-download",
        "/data/user/0/com.heytap.headset/files/melody-model-download",
        "/data/user_de/0/com.heytap.headset/files/melody-model-download",
    )

    fun hasRootAccess(): Boolean = runRootText("echo yes")?.trim() == "yes"

    /** 扫描欢律已下载的全部机型素材；无 root 或没装欢律时返回空列表。 */
    fun scanMelodyImageCandidates(): List<MelodyImageCandidate> {
        val modelDir = melodyModelDirs.firstOrNull { dir ->
            dir.matches(melodyDirPathRegex) &&
                    runRootText("test -d ${dir.shellQuote()} && echo yes")?.trim() == "yes"
        } ?: return emptyList()

        val command = "for d in ${modelDir.shellQuote()}/control_*; do " +
                "test -f \"\$d/res/image/img_detail.png\" && echo \"\$d/res/image/img_detail.png\"; " +
                "done 2>/dev/null"
        val detailPaths = runRootText(command)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.matches(melodyImagePathRegex) }
            ?.distinct()
            ?.toList()
            .orEmpty()

        return detailPaths.mapNotNull { boxPath ->
            val imageDir = boxPath.removeSuffix("/img_detail.png")
            val leftPath = "$imageDir/img_left.png"
            val rightPath = "$imageDir/img_right.png"
            if (!leftPath.matches(melodyImagePathRegex)) return@mapNotNull null
            if (!rightPath.matches(melodyImagePathRegex)) return@mapNotNull null
            val hasAll = runRootText(
                "test -f ${leftPath.shellQuote()} -a -f ${rightPath.shellQuote()} && echo yes"
            )?.trim() == "yes"
            if (!hasAll) return@mapNotNull null
            val boxBytes = readMelodyImage(boxPath) ?: return@mapNotNull null
            MelodyImageCandidate(
                label = imageDir.substringBeforeLast("/res/image").substringAfterLast('/')
                    .removePrefix("control_"),
                imageDir = imageDir,
                boxPath = boxPath,
                leftPath = leftPath,
                rightPath = rightPath,
                boxBytes = boxBytes,
            )
        }
    }

    fun readMelodyImage(path: String): ByteArray? {
        if (!path.matches(melodyImagePathRegex)) return null
        return runCatching {
            val process = ProcessBuilder("su", "-c", "cat ${path.shellQuote()}")
                .redirectErrorStream(false)
                .start()
            val bytes = ByteArrayOutputStream().use { output ->
                process.inputStream.use { input -> input.copyTo(output) }
                output.toByteArray()
            }
            if (process.waitFor() == 0 && bytes.isNotEmpty()) bytes else null
        }.onFailure { Log.e(TAG, "read failed path=$path", it) }.getOrNull()
    }

    private fun runRootText(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output else null
    }.onFailure { Log.e(TAG, "root command failed: $command", it) }.getOrNull()

    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
}
