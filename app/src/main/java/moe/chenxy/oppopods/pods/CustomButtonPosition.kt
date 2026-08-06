package moe.chenxy.oppopods.pods

// MiLink 自定义按钮复用的原生卡片位置。UPPER 保持原有 MiRing 行为，LOWER 使用空间音频单开关卡。
enum class CustomButtonPosition(val preferenceValue: String) {
    UPPER("upper"),
    LOWER("lower");

    companion object {
        const val PREF_KEY = "custom_button_position"

        fun fromPreference(value: String?): CustomButtonPosition {
            return entries.firstOrNull { it.preferenceValue == value } ?: UPPER
        }

        fun fromSelectedIndex(index: Int): CustomButtonPosition {
            return entries.getOrNull(index) ?: UPPER
        }

        fun selectedIndexOf(position: CustomButtonPosition): Int {
            return entries.indexOf(position).takeIf { it >= 0 } ?: 0
        }
    }
}
