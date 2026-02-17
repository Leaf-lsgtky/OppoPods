package moe.chenxy.oppopods.utils.miuiStrongToast.data

import kotlinx.serialization.Serializable

@Serializable
data class StringToastBean(
    var left: Left? = null,
    var right: Right? = null
)
