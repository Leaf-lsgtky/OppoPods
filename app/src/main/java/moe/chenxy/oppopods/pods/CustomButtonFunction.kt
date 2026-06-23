package moe.chenxy.oppopods.pods

// MiLink/妙享耳机面板里被劫持的「查找耳机」(MiRing) 控件的功能选择。
// NONE: 不劫持，隐藏该控件；GAME_MODE: 作为游戏模式开关（默认，保持旧行为）。
enum class CustomButtonFunction(val preferenceValue: String) {
    NONE("none"),
    GAME_MODE("game_mode");

    companion object {
        const val PREF_KEY = "custom_button_function"

        fun fromPreference(value: String?): CustomButtonFunction {
            return values().firstOrNull { it.preferenceValue == value } ?: GAME_MODE
        }

        fun fromSelectedIndex(index: Int): CustomButtonFunction {
            return values().getOrNull(index) ?: GAME_MODE
        }

        fun selectedIndexOf(function: CustomButtonFunction): Int {
            return values().indexOf(function).takeIf { it >= 0 } ?: 0
        }
    }
}
