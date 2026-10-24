package moe.chenxy.oppopods.pods

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * `.vfxms` 容器解析。
 *
 * 欢律的 3D 资源不是裸 GLB，而是一个 40 字节头 + 配置 JSON + GLB + IBL：
 * ```
 * int32 configStart, configLength
 * int32 glbStart,    glbLength
 * int32 iblStart,    iblLength
 * int32 skyboxStart, skyboxLength
 * int32 animationStart, animationLength
 * ```
 * 所有整数为大端。Windows 版只取了 GLB，丢掉了配置里的 modelScale / IBL / 灯光，
 * 所以显示会偏暗、比例和材质也不对；这里完整保留，交给 Filament（欢律同款引擎）渲染。
 */
data class VfxModel(
    val config: Config,
    val glb: ByteArray,
    val ibl: ByteArray?,
    val skybox: ByteArray?,
) {
    data class Config(
        val name: String,
        val aaType: Int,
        val enableBloom: Boolean,
        val enableSSAO: Boolean,
        val enableShadow: Boolean,
        val enableSkyBox: Boolean,
        val backgroundColor: FloatArray,
        val modelScale: Float,
        val lightIntensity: Float,
        val lightDirection: FloatArray,
        val iblIntensity: Float,
        val iblAngle: FloatArray,
        val materials: List<Material>,
    )

    data class Material(val name: String, val properties: List<Property>)

    data class Property(
        val name: String,
        val value: Float,
        val color: FloatArray,
        val type: Int,
    )

    /**
     * 官方模型的基色/法线贴图都是 RGB，材质却声明为 `alphaMode=BLEND`。
     * Filament 对 BLEND 关闭深度写入，31 个子网格会按 renderable 顺序互相穿插，
     * 看起来像耳机跑到盒外。这里把材质改成 OPAQUE 后重建 GLB。
     */
    fun opaqueGlb(): ByteArray {
        if (glb.size < 20) return glb
        val jsonLength = readIntLe(glb, 12)
        if (jsonLength <= 0 || 20 + jsonLength > glb.size) return glb
        val json = String(glb, 20, jsonLength, StandardCharsets.UTF_8)
        val patched = json.replace(
            Regex("\"alphaMode\"\\s*:\\s*\"BLEND\""),
            "\"alphaMode\":\"OPAQUE\"",
        )
        if (patched == json) return glb
        val jsonBytes = patched.toByteArray(StandardCharsets.UTF_8)
        val paddedJsonLength = (jsonBytes.size + 3) and -4
        val binStart = 20 + jsonLength
        if (binStart + 8 > glb.size) return glb
        val binLength = readIntLe(glb, binStart)
        if (binLength < 0 || binStart + 8 + binLength > glb.size) return glb
        val total = 12 + 8 + paddedJsonLength + 8 + binLength
        val output = ByteArray(total)
        val buffer = java.nio.ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(glb, 0, 12)
        buffer.putInt(8, total)
        buffer.putInt(12, paddedJsonLength)
        buffer.position(16)
        buffer.put("JSON".toByteArray(StandardCharsets.US_ASCII))
        buffer.position(20)
        buffer.put(jsonBytes)
        repeat(paddedJsonLength - jsonBytes.size) { buffer.put(' '.code.toByte()) }
        buffer.put(glb, binStart, 8 + binLength)
        return output
    }

    companion object {
        private const val HEADER_SIZE = 40
        private const val MAX_CONFIG = 4 * 1024 * 1024

        fun parse(container: ByteArray): VfxModel? {
            if (container.size < HEADER_SIZE + 12) return null
            val header = ByteBuffer.wrap(container, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val configStart = header.int
            val configLength = header.int
            val glbStart = header.int
            val glbLength = header.int
            val iblStart = header.int
            val iblLength = header.int
            val skyboxStart = header.int
            val skyboxLength = header.int
            // animationStart/animationLength are intentionally ignored for now.

            if (!validRange(container.size, configStart, configLength, MAX_CONFIG)) return null
            if (!validRange(container.size, glbStart, glbLength, container.size)) return null
            val glb = container.copyOfRange(glbStart, glbStart + glbLength)
            if (!isValidGlb(glb)) return null

            val configText = String(
                container, configStart, configLength, StandardCharsets.UTF_8,
            )
            val config = runCatching { parseConfig(JSONObject(configText)) }.getOrNull() ?: return null

            val ibl = if (validRange(container.size, iblStart, iblLength, container.size)) {
                container.copyOfRange(iblStart, iblStart + iblLength).takeIf(::isKtx)
            } else {
                null
            }
            val skybox = if (validRange(container.size, skyboxStart, skyboxLength, container.size)) {
                container.copyOfRange(skyboxStart, skyboxStart + skyboxLength).takeIf(::isKtx)
            } else {
                null
            }
            return VfxModel(config, glb, ibl, skybox)
        }

        private fun validRange(size: Int, start: Int, length: Int, limit: Int): Boolean {
            if (start < HEADER_SIZE || length <= 0 || length > limit) return false
            return start.toLong() + length <= size
        }

        private fun isValidGlb(bytes: ByteArray): Boolean {
            if (bytes.size < 20) return false
            if (bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) != "glTF") return false
            if (readIntLe(bytes, 4) != 2) return false
            if (readIntLe(bytes, 8) != bytes.size) return false
            if (bytes.copyOfRange(16, 20).toString(StandardCharsets.US_ASCII) != "JSON") return false
            val jsonLength = readIntLe(bytes, 12)
            return jsonLength > 0 && 20L + jsonLength <= bytes.size
        }

        private fun isKtx(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val magic = bytes.copyOfRange(0, 12)
            return magic.contentEquals(byteArrayOf(
                0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
            )) || magic.contentEquals(byteArrayOf(
                0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
            ))
        }

        private fun parseConfig(json: JSONObject): Config {
            val light = json.optJSONObject("light")
            return Config(
                name = json.optString("name", "model"),
                aaType = json.optInt("aaType", 1),
                enableBloom = json.optBoolean("enableBloom", true),
                enableSSAO = json.optBoolean("enableSSAO", true),
                enableShadow = json.optBoolean("enableShadow", true),
                enableSkyBox = json.optBoolean("enableSkyBox", true),
                backgroundColor = readFloats(json.optJSONArray("backgroundColor"), floatArrayOf(1f, 1f, 1f, 1f)),
                modelScale = json.optDouble("modelScale", 1.0).toFloat(),
                lightIntensity = light?.optDouble("intensity", 0.0)?.toFloat() ?: 0f,
                lightDirection = readFloats(
                    light?.optJSONArray("direction"), floatArrayOf(0f, 0f, 0f),
                ),
                iblIntensity = light?.optDouble("iblIntensity", 30000.0)?.toFloat() ?: 30000f,
                iblAngle = readFloats(
                    light?.optJSONArray("iblAngle"), floatArrayOf(0f, 90f, 0f),
                ),
                materials = parseMaterials(json.optJSONObject("materials")),
            )
        }

        private fun parseMaterials(group: JSONObject?): List<Material> {
            val rows = group?.optJSONArray("materials") ?: return emptyList()
            val materials = ArrayList<Material>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val properties = row.optJSONArray("property") ?: JSONArray()
                val parsed = ArrayList<Property>(properties.length())
                for (i in 0 until properties.length()) {
                    val item = properties.optJSONObject(i) ?: continue
                    parsed += Property(
                        name = item.optString("name"),
                        value = item.optDouble("value", 1.0).toFloat(),
                        color = readFloats(item.optJSONArray("color"), floatArrayOf(1f, 1f, 1f, 1f)),
                        type = item.optInt("type", -1),
                    )
                }
                materials += Material(row.optString("name"), parsed)
            }
            return materials
        }

        private fun readFloats(array: JSONArray?, fallback: FloatArray): FloatArray {
            if (array == null || array.length() < fallback.size) return fallback
            return FloatArray(fallback.size) { array.optDouble(it, fallback[it].toDouble()).toFloat() }
        }

        private fun readIntLe(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
        }
    }
}
