package moe.chenxy.oppopods.pods

import android.content.Context
import android.util.Log
import moe.chenxy.oppopods.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内嵌机型白名单（assets/device_models.json）的加载与查询。
 *
 * 数据来自 OPPO 官方 HeyMelody 云端配置的 `whiteList` 数组，每条形如：
 * ```
 * { "id": "067410", "name": "OPPO Enco X3", "supportSpp": true,
 *   "function": { "noiseReductionMode": [ {"modeType":5,"protocolIndex":1, "childrenMode":[...]}, ... ], ... } }
 * ```
 * 关键点：机型差异体现在 `protocolIndex`（ANC 位图的位号）上，而非指令本身。
 * 因此这里只解析能力，具体字节由 [CapabilityProfileFactory] 按索引生成。
 */
object DeviceModelRegistry {
    private const val TAG = "OppoPods-ModelRegistry"
    private const val ASSET_NAME = "device_models.json"
    private const val EQ_MODE_NAMES_ASSET_NAME = "eq_mode_names.json"
    private const val EQ_MODE_NAMES_EN_ASSET_NAME = "eq_mode_names.en.json"

    @Volatile
    private var entries: List<JSONObject> = emptyList()
    @Volatile
    private var byId: Map<String, JSONObject> = emptyMap()
    @Volatile
    private var eqModeNames: Map<Int, String> = emptyMap()
    private val capsCache = HashMap<String, DeviceCapabilities>()

    /** 首次使用前调用（幂等）。解析约 137 条，耗时可忽略。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (entries.isNotEmpty()) return
        eqModeNames = loadEqModeNames(context)
        val text = runCatching { readAsset(context) }.getOrElse {
            Log.e(TAG, "failed to read $ASSET_NAME", it)
            return
        } ?: return
        val list = runCatching {
            val array = JSONObject(text).optJSONArray("whiteList") ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrElse {
            Log.e(TAG, "failed to parse $ASSET_NAME", it)
            return
        }
        // 名称长的排前面：按名称模糊匹配时优先命中更具体的型号（"Enco X3s" 先于 "Enco X3"）。
        val sorted = list.sortedByDescending { it.optString("name").length }
        entries = sorted
        byId = buildMap<String, JSONObject> {
            for (entry in sorted) {
                val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
                // 同 id 多条时优先保留带 function 的那条。
                val existing: JSONObject? = get(id)
                if (existing != null && existing.has("function") && !entry.has("function")) continue
                put(id, entry)
            }
        }
        Log.d(TAG, "loaded ${sorted.size} models, ${byId.size} unique ids")
    }

    /**
     * 读取内嵌白名单。
     *
     * Hook 进程跑在 com.android.bluetooth 里，那里的 `context.assets` 是宿主应用的
     * 资源，不含本模块的 JSON。所以先按本模块包名取一次 Context，失败再回落到
     * 传入的 Context（应用进程里两者等价）。
     */
    private fun readAsset(context: Context): String? {
        val ownContext = if (context.packageName == BuildConfig.APPLICATION_ID) {
            context
        } else {
            runCatching {
                context.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_IGNORE_SECURITY,
                )
            }.getOrElse {
                Log.w(TAG, "createPackageContext failed, falling back to host assets", it)
                context
            }
        }
        return ownContext.assets.open(ASSET_NAME).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun loadEqModeNames(context: Context): Map<Int, String> {
        val language = context.resources.configuration.locales[0].language.lowercase()
        val preferred = if (language == "zh") EQ_MODE_NAMES_ASSET_NAME else EQ_MODE_NAMES_EN_ASSET_NAME
        val candidates = listOf(preferred, EQ_MODE_NAMES_ASSET_NAME).distinct()
        for (assetName in candidates) {
            val text = runCatching {
                val ownContext = if (context.packageName == BuildConfig.APPLICATION_ID) {
                    context
                } else {
                    runCatching { context.createPackageContext(
                        BuildConfig.APPLICATION_ID,
                        Context.CONTEXT_IGNORE_SECURITY,
                    ) }.getOrElse { context }
                }
                ownContext.assets.open(assetName).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: continue
            val mapping = runCatching { JSONObject(text).optJSONObject("mapping") }.getOrNull() ?: continue
            return buildMap {
                for (key in mapping.keys()) {
                    val value = mapping.optString(key).takeIf { it.isNotBlank() } ?: continue
                    key.toIntOrNull()?.let { put(it, value) }
                }
            }
        }
        Log.w(TAG, "failed to load EQ mode names")
        return emptyMap()
    }

    /** 全部机型（id 与显示名），供设置页手动选型列表使用。按名称排序。 */
    fun allModels(context: Context): List<ModelInfo> {
        ensureLoaded(context)
        return byId.entries
            .mapNotNull { (id, entry) ->
                val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ModelInfo(id, name)
            }
            .sortedBy { it.name }
    }

    /** 按 productId（0x8103 返回的 6 位大写 hex）精确查询。 */
    fun byProductId(context: Context, productId: String): DeviceCapabilities? {
        ensureLoaded(context)
        if (productId.isBlank()) return null
        val entry = byId[productId.uppercase()] ?: return null
        return cached(productId.uppercase()) { parse(entry, entry.optString("name")) }
    }

    /** 按蓝牙设备名模糊匹配（精确 → 包含 → 被包含），作为 productId 不可用时的回退。 */
    fun byDeviceName(context: Context, deviceName: String?): DeviceCapabilities? {
        ensureLoaded(context)
        if (deviceName.isNullOrBlank()) return null
        val target = normalize(deviceName)
        if (target.isEmpty()) return null

        val match = matchPreferringFunction { normalize(it.optString("name")).let { n -> n.isNotEmpty() && n == target } }
            ?: matchPreferringFunction { normalize(it.optString("name")).let { n -> n.length >= 5 && target.contains(n) } }
            ?: matchPreferringFunction { normalize(it.optString("name")).let { n -> target.length >= 5 && n.contains(target) } }
            ?: return null
        return parse(match, deviceName)
    }

    /** 白名单中带 function 的条目优先，避免命中同名的纯占位条目。 */
    private fun matchPreferringFunction(predicate: (JSONObject) -> Boolean): JSONObject? {
        var fallback: JSONObject? = null
        for (entry in entries) {
            if (!predicate(entry)) continue
            if (entry.has("function")) return entry
            if (fallback == null) fallback = entry
        }
        return fallback
    }

    @Synchronized
    private fun cached(key: String, compute: () -> DeviceCapabilities): DeviceCapabilities =
        capsCache.getOrPut(key, compute)

    private fun normalize(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- 解析

    /** 解析单条白名单条目为能力集。不依赖 Context/Log，可直接单测。 */
    internal fun parse(entry: JSONObject, deviceName: String): DeviceCapabilities {
        val modelName = entry.optString("name").ifBlank { deviceName }
        val modelId = entry.optString("id")
        val supportSpp = entry.optBoolean("supportSpp", true)
        val protocolType = entry.optInt("protocolType", 1)
        val func = entry.optJSONObject("function")
            ?: return DeviceCapabilities(
                modelId = modelId,
                modelName = modelName,
                deviceName = deviceName,
                supportSpp = supportSpp,
                protocolType = protocolType,
                isSupported = supportSpp && protocolType != 0,
            )

        val nrm = func.optJSONArray("noiseReductionMode")
        val ancBuild = buildAncOptions(nrm)

        val gameSoundTypes = func.optJSONArray("gameSoundList")
        val hasGameSound = gameSoundTypes != null &&
                (0 until gameSoundTypes.length()).any {
                    (gameSoundTypes.optJSONObject(it)?.optInt("type") ?: 0) != 0
                }

        return DeviceCapabilities(
            modelId = modelId,
            modelName = modelName,
            deviceName = deviceName,
            supportSpp = supportSpp,
            protocolType = protocolType,
            isSupported = supportSpp && protocolType != 0,
            spatialTypes = func.optJSONArray("spatialTypes")?.let { array ->
                (0 until array.length()).map { array.optInt(it) }.distinct()
            } ?: emptyList(),
            hasDualDevice = flagOn(func, "multiDevicesConnect") ||
                    (func.optJSONArray("multiConnectFunctions")?.length() ?: 0) > 0,
            hasWearDetection = flagOn(func, "wearDetection"),
            hasGameMode = gameModeSupported(func) || hasGameSound,
            hasGameSound = hasGameSound,
            hasCustomEq = flagOn(func, "customEqualizer"),
            eqPresets = parseEqPresets(func),
            customEqFrequencies = func.optJSONArray("customEqFrequency")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optInt(index, 0).takeIf { it > 0 }
                }
            } ?: emptyList(),
            customEqMaxPresets = func.optInt("customEqMax", 0).coerceAtLeast(0),
            hasAdaptiveAnc = ancBuild.hasAdaptive,
            isLegacyAnc = ancBuild.isLegacy,
            ancOptions = ancBuild.options,
            ancIndexToName = ancBuild.indexToName,
            ancNameToIndex = ancBuild.nameToIndex,
        )
    }

    /**
     * 解析型号白名单里的内置 EQ。不同型号只是在 protocolIndex/modeType 上不同，
     * 切换协议本身统一为 0x0406 + [protocolIndex]。
     */
    private fun parseEqPresets(func: JSONObject): List<EqPreset> {
        val result = LinkedHashMap<Int, EqPreset>()
        listOf("equalizerMode", "equalizerModeCompat", "equalizerModeByVersion").forEach { key ->
            val modes = func.optJSONArray(key) ?: return@forEach
            for (i in 0 until modes.length()) {
                val mode = modes.optJSONObject(i) ?: continue
                val id = mode.optInt("protocolIndex", -1)
                if (id < 0 || result.containsKey(id)) continue
                val modeType = mode.optInt("modeType", -1)
                val name = eqModeNames[modeType] ?: "M$id"
                result[id] = EqPreset(id = id, name = name, modeType = modeType)
            }
        }
        return result.values.sortedBy { it.id }
    }

    private fun flagOn(func: JSONObject, key: String): Boolean {
        if (!func.has(key)) return false
        return when (val v = func.opt(key)) {
            is Number -> v.toInt() >= 1
            is Boolean -> v
            else -> false
        }
    }

    /**
     * 游戏模式判定。
     *
     * 白名单在这一项上并不完整：137 条里只有 16 条带 `gameMode` / `gameModeList` /
     * `gameSoundList`，而实测支持低延迟模式的 Enco Free4 恰恰一个都没有。所以这里
     * 只把"显式声明不支持"当作否定信号，其余情况一律认为支持——真正的裁决交给
     * 连接后的 0x810D 批量查询（同时探测 0x06 与 0x28，设备回报哪个才用哪个）。
     */
    private fun gameModeSupported(func: JSONObject): Boolean {
        val list = func.optJSONArray("gameModeList")
        if (list != null && list.length() > 0) {
            return (0 until list.length()).any {
                (list.optJSONObject(it)?.optInt("gameMode") ?: 0) == 1
            }
        }
        if (func.has("gameMode")) return flagOn(func, "gameMode")
        return true
    }

    private class AncBuild(
        val options: List<AncOption>,
        val indexToName: Map<Int, String>,
        val nameToIndex: Map<String, Int>,
        val hasAdaptive: Boolean,
        val isLegacy: Boolean,
    )

    /** modeType → 规范键名。白名单里 6 和 10 都表示自适应。 */
    private fun modeKey(type: Int): String = when (type) {
        1 -> AncKeys.OFF
        2 -> AncKeys.TRANSPARENCY
        3 -> AncKeys.LIGHT
        4 -> AncKeys.DEEP
        5 -> AncKeys.NC
        6, 10 -> AncKeys.ADAPTIVE
        7 -> AncKeys.SMART
        8 -> AncKeys.MEDIUM
        else -> "Mode$type"
    }

    private val NOISE_LEVEL_KEYS = setOf(
        AncKeys.SMART,
        AncKeys.LIGHT,
        AncKeys.MEDIUM,
        AncKeys.DEEP,
    )

    /** UI 展示顺序：降噪/智能 → 通透 → 自适应 → 其他 → 关闭。 */
    private fun mainRank(key: String): Int = when (key) {
        AncKeys.NC, AncKeys.SMART -> 0
        AncKeys.TRANSPARENCY -> 1
        AncKeys.ADAPTIVE -> 2
        AncKeys.OFF -> 99
        else -> 50
    }

    private fun buildAncOptions(nrm: JSONArray?): AncBuild {
        if (nrm == null || nrm.length() == 0) {
            return AncBuild(emptyList(), emptyMap(), emptyMap(), hasAdaptive = false, isLegacy = false)
        }

        val indexToName = LinkedHashMap<Int, String>()
        val nameToIndex = LinkedHashMap<String, Int>()
        val options = mutableListOf<AncOption>()
        var hasAdaptive = false
        var hasAnyChildren = false

        fun register(index: Int, name: String) {
            indexToName[index] = name
            nameToIndex.putIfAbsent(name, index)
        }

        for (i in 0 until nrm.length()) {
            val entry = nrm.optJSONObject(i) ?: continue
            if (!entry.has("modeType")) continue
            val type = entry.optInt("modeType")
            val key = modeKey(type)
            val ownIndex = entry.optInt("protocolIndex", 0)

            val childrenArray = entry.optJSONArray("childrenMode")
            if (childrenArray != null) hasAnyChildren = true

            val childOptions = mutableListOf<AncOption>()
            if (childrenArray != null) {
                for (c in 0 until childrenArray.length()) {
                    val child = childrenArray.optJSONObject(c) ?: continue
                    if (!child.has("protocolIndex")) continue
                    val childIndex = child.optInt("protocolIndex")
                    val childKey = modeKey(child.optInt("modeType", type))
                    if (childKey in NOISE_LEVEL_KEYS) {
                        // 降噪等级（智能/轻/中/深）：作为独立可发送项登记，供等级选择器使用。
                        register(childIndex, childKey)
                    } else {
                        // 其余子档是父档的变体（如通透下的「自适应通透」）。它不是独立主档，
                        // 位图回报要归到父档，也不能进 nameToIndex —— 否则 Enco X2/X3 这类
                        // 只有子级自适应的机型会错误地显示出自适应开关。
                        indexToName[childIndex] = key
                    }
                    childOptions += AncOption(childKey, childIndex)
                }
            }

            // 主档一律使用父级 protocolIndex 发送。子模式的索引只用于位图反查和
            // 降噪等级细分——耳机对主档（关闭/降噪/通透）只认父级的位。
            // 这与本项目已在 Enco X3/Free4 上实测通过的 01 01 01 / 02 / 04 一致。
            register(ownIndex, key)
            nameToIndex[key] = ownIndex
            // 只有顶层自适应才是独立主档。通透下的 Adaptive 子档已经在上面
            // 归并到父档的位图名称中，不能因此让自适应开关显示出来。
            if (key == AncKeys.ADAPTIVE) hasAdaptive = true

            if (childOptions.isEmpty()) {
                options += AncOption(key, ownIndex)
            } else if (childOptions.size == 1 && childOptions[0].key == key) {
                // 唯一子模式与父同名（如 Transparency→[Transparency]）：合并成单档。
                options += AncOption(key, ownIndex)
            } else {
                // 有实际子模式（如降噪→智能/深度/中度/轻度）：父档可发送，子档做等级细分。
                options += AncOption(key, ownIndex, children = childOptions)
            }
        }

        // 无子模式且 NC 落在 idx0：老机型排布，降噪/通透与现代机型相反。
        val isLegacy = !hasAnyChildren && (0 until nrm.length()).any {
            val entry = nrm.optJSONObject(it) ?: return@any false
            entry.optInt("modeType") == 5 && entry.optInt("protocolIndex", -1) == 0
        }

        val sorted = options
            .sortedBy { mainRank(it.key) }
            .map { option ->
                if (option.children.isEmpty()) option
                else option.copy(children = option.children.sortedBy { mainRank(it.key) })
            }

        return AncBuild(sorted, indexToName, nameToIndex, hasAdaptive, isLegacy)
    }

    /** 手动选型列表项。 */
    data class ModelInfo(val id: String, val name: String)
}
