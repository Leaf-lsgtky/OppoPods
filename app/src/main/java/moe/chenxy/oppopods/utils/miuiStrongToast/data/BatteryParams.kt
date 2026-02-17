package moe.chenxy.oppopods.utils.miuiStrongToast.data
import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class PodParams (
    var battery: Int = 0,
    var isCharging: Boolean = false,
    var isConnected: Boolean = false,
    var rawStatus: Int = 0
) : Parcelable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@Parcelize
data class BatteryParams(
    var left: PodParams? = null,
    var right: PodParams? = null,
    var case: PodParams? = null
) : Parcelable
