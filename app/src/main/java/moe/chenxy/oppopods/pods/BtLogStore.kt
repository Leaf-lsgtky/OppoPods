package moe.chenxy.oppopods.pods

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BtLogEntry(
    val timestamp: Long,
    val isSend: Boolean,
    val hex: String,
    val label: String? = null
) {
    fun timeFormatted(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object BtLogStore {
    private val _entries = MutableStateFlow<List<BtLogEntry>>(emptyList())
    val entries: StateFlow<List<BtLogEntry>> = _entries

    @Volatile
    var isEnabled = false

    @OptIn(ExperimentalStdlibApi::class)
    fun addSend(packet: ByteArray, label: String? = null) {
        if (!isEnabled) return
        val entry = BtLogEntry(
            timestamp = System.currentTimeMillis(),
            isSend = true,
            hex = packet.toHexString(HexFormat.UpperCase),
            label = label
        )
        Log.d("BtLogStore", "SEND: ${entry.hex} label=$label")
        _entries.value += entry
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun addRecv(packet: ByteArray, label: String? = null) {
        if (!isEnabled) return
        val entry = BtLogEntry(
            timestamp = System.currentTimeMillis(),
            isSend = false,
            hex = packet.toHexString(HexFormat.UpperCase),
            label = label
        )
        Log.d("BtLogStore", "RECV: ${entry.hex} label=$label")
        _entries.value += entry
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun addFromBroadcast(isSend: Boolean, hex: String, label: String?) {
        if (!isEnabled) return
        val entry = BtLogEntry(
            timestamp = System.currentTimeMillis(),
            isSend = isSend,
            hex = hex,
            label = label
        )
        Log.d("BtLogStore", "${if (isSend) "SEND" else "RECV"}(hook): $hex label=$label")
        _entries.value += entry
    }
}
