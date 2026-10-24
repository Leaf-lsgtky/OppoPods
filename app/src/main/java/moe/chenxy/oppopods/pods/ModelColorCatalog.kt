package moe.chenxy.oppopods.pods

/**
 * 官方已知机型的协议色号与配色名称目录。
 *
 * 依据 heymelody-windows 离线审计与欢律 CDN 素材包命名归纳：
 * 官方协议色号为离散 SKU 编号，非简单的 0..7 连续递增数组。
 */
object ModelColorCatalog {

    data class ColorOption(val colorId: Int, val name: String)

    private val MODEL_COLORS = mapOf(
        // OPPO Enco X3: 2 极夜黑, 3 米白 (官方主打色为米白)
        "067410" to listOf(
            ColorOption(3, "米白"),
            ColorOption(2, "极夜黑"),
        ),
        // OPPO Enco Free4 丹拿版: 3 星瀚银 (唯一有效官方模型与图片请求值)
        "06C010" to listOf(
            ColorOption(3, "星瀚银"),
        ),
        // OPPO Enco Free4: 1 皓月白
        "068C10" to listOf(
            ColorOption(1, "皓月白"),
        ),
        // OPPO Enco X4: 1 丹拿原声白
        "06EC10" to listOf(
            ColorOption(1, "白色"),
        ),
        // OnePlus Buds Pro 4
        "067814" to listOf(
            ColorOption(1, "独奏白"),
            ColorOption(2, "深空黑"),
        ),
        // OPPO Enco Air4 Pro
        "067C10" to listOf(
            ColorOption(1, "月夜黑"),
            ColorOption(2, "晨曦白"),
        ),
        // OPPO Enco X3i
        "066810" to listOf(
            ColorOption(1, "星夜黑"),
            ColorOption(2, "浮光白"),
        ),
    )

    /**
     * 获取指定机型的已知颜色选项列表；未知机型返回空列表。
     */
    fun getColorOptions(productId: String?): List<ColorOption> {
        if (productId.isNullOrBlank()) return emptyList()
        return MODEL_COLORS[productId.uppercase()] ?: emptyList()
    }

    /**
     * 获取指定机型的默认/主打色号。若未收录，返回 null。
     */
    fun getDefaultColor(productId: String?): Int? {
        if (productId.isNullOrBlank()) return null
        return getColorOptions(productId).firstOrNull()?.colorId
    }

    /**
     * 获取指定机型和色号的名称，若未收录则返回通用格式，如 "颜色 3"。
     */
    fun getColorName(productId: String?, colorId: Int): String {
        val options = getColorOptions(productId)
        val matched = options.firstOrNull { it.colorId == colorId }
        return matched?.name ?: "颜色 $colorId"
    }
}
