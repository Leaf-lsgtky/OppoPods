package moe.chenxy.oppopods.pods

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * OPPO earphone RFCOMM protocol packet definitions.
 *
 * Packet format (Little Endian for multi-byte fields):
 * Header(AA) + TotalLen(1B) + Res(0000) + Cmd(2B) + Seq(1B) + PayLen(2B) + Payload
 */

object OppoPackets {

    private var seqCounter: Int = 0x01

    private fun nextSeq(): Int {
        val seq = seqCounter
        seqCounter = if (seqCounter >= 0xFE) 0x01 else seqCounter + 1
        return seq
    }

    /** Build a complete OPPO protocol packet with auto-incrementing seq. */
    fun buildPacket(cmd: Int, seq: Int = nextSeq(), payload: ByteArray = byteArrayOf()): ByteArray {
        val payLen = payload.size
        val totalLen = 7 + payLen
        val packet = ByteArray(2 + totalLen)
        packet[0] = 0xAA.toByte()
        packet[1] = totalLen.toByte()
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = (cmd and 0xFF).toByte()
        packet[5] = ((cmd shr 8) and 0xFF).toByte()
        packet[6] = seq.toByte()
        packet[7] = (payLen and 0xFF).toByte()
        packet[8] = ((payLen shr 8) and 0xFF).toByte()
        payload.copyInto(packet, 9)
        return packet
    }

    /** Build with fixed seq (for seed profile packets that need deterministic output). */
    fun buildPacketFixedSeq(cmd: Int, seq: Int = 0xF0, payload: ByteArray = byteArrayOf()): ByteArray {
        val payLen = payload.size
        val totalLen = 7 + payLen
        val packet = ByteArray(2 + totalLen)
        packet[0] = 0xAA.toByte()
        packet[1] = totalLen.toByte()
        packet[2] = 0x00
        packet[3] = 0x00
        packet[4] = (cmd and 0xFF).toByte()
        packet[5] = ((cmd shr 8) and 0xFF).toByte()
        packet[6] = seq.toByte()
        packet[7] = (payLen and 0xFF).toByte()
        packet[8] = ((payLen shr 8) and 0xFF).toByte()
        payload.copyInto(packet, 9)
        return packet
    }

    /** Query notification capability: 0x0200 */
    fun buildQueryBroadcastCodes(): ByteArray = buildPacket(Cmd.QUERY_BROADCAST_CODES)

    /** Subscribe to notification events: 0x0205 */
    fun buildSubscribeBroadcast(codes: List<Int>): ByteArray {
        val payload = byteArrayOf(codes.size.toByte()) + codes.map { it.toByte() }.toByteArray()
        return buildPacket(Cmd.SUBSCRIBE_BROADCAST, payload = payload)
    }
}

class OppoPacketFramer {
    private var pending = ByteArray(0)

    fun append(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()

        pending += buffer.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()

        while (pending.isNotEmpty()) {
            val start = pending.indexOf(OPPO_PACKET_HEADER)
            if (start < 0) {
                pending = ByteArray(0)
                break
            }
            if (start > 0) {
                pending = pending.copyOfRange(start, pending.size)
            }
            if (pending.size < 2) break

            val totalLen = pending[1].toInt() and 0xFF
            val frameLen = totalLen + 2
            if (totalLen < OPPO_PACKET_MIN_TOTAL_LEN || frameLen > OPPO_PACKET_MAX_FRAME_LEN) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < frameLen) break

            frames += pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
        }

        return frames
    }

    companion object {
        private val OPPO_PACKET_HEADER = 0xAA.toByte()
        private const val OPPO_PACKET_MIN_TOTAL_LEN = 7
        private const val OPPO_PACKET_MAX_FRAME_LEN = 512
    }
}

/** ANC mode values for OPPO earphones (used in SET commands). */
object AncMode {
    const val OFF = 0x01
    const val NOISE_CANCELLATION = 0x02
    const val TRANSPARENCY = 0x04
    const val ADAPTIVE_HIGH = 0x00
    const val ADAPTIVE_LOW = 0x08
}

/** Noise control mode enum for UI. */
enum class NoiseControlMode {
    OFF, NOISE_CANCELLATION, ADAPTIVE, TRANSPARENCY
}

/** Battery component index in response payload. */
object BatteryComponent {
    const val LEFT = 1
    const val RIGHT = 2
    const val CASE = 3
}

/** Feature IDs used by the switch-feature command/query. */
object GameModeFeature {
    const val LOW_LATENCY = 0x06
    const val MAIN = 0x28
}

/** Batch status query parameter IDs (0x810D response). */
object BatchParamId {
    const val AUTO_PLAY_PAUSE = 0x04
    const val DUAL_DEVICE = 0x11
}

/** Spatial audio mode values. */
object SpatialAudioMode {
    const val OFF = 0x00
    const val FIXED = 0x01
    const val HEAD_TRACKING = 0x02
}

/** Protocol command codes. */
object Cmd {
    /** Set ANC mode */
    const val SET_ANC = 0x0404
    /** Set game mode */
    const val SET_GAME_MODE = 0x0403
    /** Set spatial audio mode */
    const val SET_SPATIAL_AUDIO = 0x0422
    /** Query battery */
    const val QUERY_BATTERY = 0x0106
    /** Battery response from earphone */
    const val BATTERY_RESPONSE = 0x8106
    /** Query ANC mode */
    const val QUERY_ANC_MODE = 0x010C
    /** ANC mode response */
    const val ANC_MODE_RESPONSE = 0x810C
    /** ANC mode change notification */
    const val ANC_MODE_NOTIFY = 0x0204
    /** Batch parameter query */
    const val QUERY_STATUS = 0x010D
    /** Batch parameter query response */
    const val QUERY_STATUS_RESPONSE = 0x810D
    /** Switch-feature response */
    const val SET_GAME_MODE_RESPONSE = 0x8403
    /** Spatial audio mode response */
    const val SET_SPATIAL_AUDIO_RESPONSE = 0x8422
    /** Spatial audio mode notification */
    const val SPATIAL_AUDIO_NOTIFY = 0x0510
    /** Query notification capability */
    const val QUERY_BROADCAST_CODES = 0x0200
    /** Notification capability response */
    const val BROADCAST_CODES_RESPONSE = 0x8200
    /** Subscribe to notification events */
    const val SUBSCRIBE_BROADCAST = 0x0205
    /** Subscribe response */
    const val SUBSCRIBE_BROADCAST_RESPONSE = 0x8205
}

/**
 * Parser for OPPO earphone battery response packets.
 *
 * Response packet format: AA + TotalLen + 0000 + Cmd(0x8106 = 06 81) + Seq + PayLen + Payload
 * Payload consists of pairs: [Index(1B), RawValue(1B)]
 *   Index: 1=Left, 2=Right, 3=Case
 *   RawValue: battery = value & 0x7F, charging = (value & 0x80) != 0
 */
object BatteryParser {

    data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean
    )

    data class BatteryResult(
        val left: BatteryInfo?,
        val right: BatteryInfo?,
        val case: BatteryInfo?
    )

    /**
     * Parse a raw packet buffer for battery response (query response, Cmd=0x8106).
     * Returns null if the packet is not a valid battery response.
     */
    fun parse(data: ByteArray): BatteryResult? {
        // Minimum packet: AA + TotalLen + 00 00 + Cmd(2) + Seq(1) + PayLen(2) = 9 bytes header
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        // Check command = 0x8106 (stored as 06 81 in little endian at offsets 4,5)
        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.BATTERY_RESPONSE) return null

        // PayLen at offsets 7,8 (little endian)
        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9

        if (data.size < payloadStart + payLen) return null

        var left: BatteryInfo? = null
        var right: BatteryInfo? = null
        var case: BatteryInfo? = null

        var i = payloadStart
        while (i + 1 < payloadStart + payLen) {
            val index = data[i].toInt() and 0xFF
            val rawValue = data[i + 1].toInt() and 0xFF
            val level = rawValue and 0x7F
            val charging = (rawValue and 0x80) != 0
            val info = BatteryInfo(level, charging)

            when (index) {
                BatteryComponent.LEFT -> left = info
                BatteryComponent.RIGHT -> right = info
                BatteryComponent.CASE -> case = info
            }
            i += 2
        }

        return BatteryResult(left, right, case)
    }

    /**
     * Parse an active/unsolicited battery report (Cmd=0x0204, payload type=0x01).
     *
     * Active report format:
     * Payload[0] = 0x01 (report type: battery)
     * Payload[1] = count (number of index-value pairs)
     * Payload[2..] = [Index(1B), StatusValue(1B)] * count
     *
     * Returns null if the packet is not a valid active battery report.
     */
    fun parseActiveReport(data: ByteArray): BatteryResult? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.ANC_MODE_NOTIFY) return null // 0x0204 = active status report

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9
        if (data.size < payloadStart + payLen) return null
        if (payLen < 2) return null

        // Check report type = 0x01 (battery)
        val reportType = data[payloadStart].toInt() and 0xFF
        if (reportType != 0x01) return null

        val count = data[payloadStart + 1].toInt() and 0xFF
        if (payLen < 2 + count * 2) return null

        var left: BatteryInfo? = null
        var right: BatteryInfo? = null
        var case: BatteryInfo? = null

        for (j in 0 until count) {
            val idx = payloadStart + 2 + j * 2
            if (idx + 1 >= data.size) break
            val index = data[idx].toInt() and 0xFF
            val rawValue = data[idx + 1].toInt() and 0xFF
            val level = rawValue and 0x7F
            val charging = (rawValue and 0x80) != 0
            val info = BatteryInfo(level, charging)

            when (index) {
                BatteryComponent.LEFT -> left = info
                BatteryComponent.RIGHT -> right = info
                BatteryComponent.CASE -> case = info
            }
        }

        return BatteryResult(left, right, case)
    }
}

/**
 * Parser for OPPO earphone ANC mode response/notification packets.
 *
 * Cmd: 0x810C (mode query response) or 0x0204 (mode change notification)
 * Scan payload for consecutive bytes 01 01 [Val1] [Val2]
 * Val mapping: 0x10 0x00=NC, 0x00 0x01=Transparency, 0x08 0x00=Off, 0x00 0x08=Adaptive
 * For NC mode, Val1 can also be a noise level: 0x80=Smart, 0x40=Light, 0x20=Medium, 0x10=Deep
 */
object AncModeParser {

    data class AncResult(val mode: NoiseControlMode, val noiseLevel: Int? = null)

    fun parse(data: ByteArray): AncResult? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)

        if (cmd != Cmd.ANC_MODE_RESPONSE && cmd != Cmd.ANC_MODE_NOTIFY) return null

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9

        if (data.size < payloadStart + payLen) return null

        // For 0x0204, only process ANC-related eventCodes (0x03=mode change, 0x04=fit detection)
        if (cmd == Cmd.ANC_MODE_NOTIFY && payLen > 0) {
            val reportType = data[payloadStart].toInt() and 0xFF
            if (reportType != 0x03 && reportType != 0x04) return null
        }

        // Scan for pattern: 01 01 [Val1] [Val2]
        for (i in payloadStart until minOf(payloadStart + payLen - 3, data.size - 3)) {
            if (data[i] == 0x01.toByte() && data[i + 1] == 0x01.toByte()) {
                val val1 = data[i + 2].toInt() and 0xFF
                val val2 = data[i + 3].toInt() and 0xFF

                return when {
                    val1 == 0x10 && val2 == 0x00 -> AncResult(NoiseControlMode.NOISE_CANCELLATION)
                    val1 == 0x00 && val2 == 0x01 -> AncResult(NoiseControlMode.TRANSPARENCY)
                    val1 == 0x08 && val2 == 0x00 -> AncResult(NoiseControlMode.OFF)
                    val1 == 0x00 && val2 == 0x08 -> AncResult(NoiseControlMode.ADAPTIVE)
                    val2 == 0x00 && val1 in NoiseLevel.ALL ->
                        AncResult(NoiseControlMode.NOISE_CANCELLATION, noiseLevel = val1)
                    else -> null
                }
            }
        }
        return null
    }

    /** Legacy wrapper returning only the mode (backward compat for callers that don't need noise level). */
    fun parseMode(data: ByteArray): NoiseControlMode? = parse(data)?.mode
}

/**
 * Parser for game mode status from batch parameter query response (Cmd=0x810D).
 */
object GameModeParser {

    data class Status(
        val mainEnabled: Boolean?,
        val lowLatencyEnabled: Boolean?,
        val autoPlayPause: Boolean? = null,
        val dualDevice: Boolean? = null
    )

    fun parseForFeature(data: ByteArray, featureId: Int): Boolean? {
        val status = parseStatus(data) ?: return null
        return when (featureId) {
            GameModeFeature.LOW_LATENCY -> status.lowLatencyEnabled
            else -> status.mainEnabled
        }
    }

    fun parseStatus(data: ByteArray): Status? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.QUERY_STATUS_RESPONSE) return null

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9

        if (data.size < payloadStart + payLen) return null

        val structuredStatus = parseStructuredFeaturePairs(data, payloadStart, payLen)
        if (structuredStatus != null) return structuredStatus

        var mainEnabled: Boolean? = null
        var lowLatencyEnabled: Boolean? = null
        var autoPlayPause: Boolean? = null
        var dualDevice: Boolean? = null
        for (i in payloadStart until minOf(payloadStart + payLen - 1, data.size - 1)) {
            val value = data[i + 1].toInt() and 0xFF
            if (value != 0x00 && value != 0x01) continue
            when (data[i].toInt() and 0xFF) {
                GameModeFeature.MAIN -> mainEnabled = value == 0x01
                GameModeFeature.LOW_LATENCY -> lowLatencyEnabled = value == 0x01
                BatchParamId.AUTO_PLAY_PAUSE -> autoPlayPause = value == 0x01
                BatchParamId.DUAL_DEVICE -> dualDevice = value == 0x01
            }
        }
        return if (mainEnabled != null || lowLatencyEnabled != null || autoPlayPause != null || dualDevice != null) {
            Status(mainEnabled, lowLatencyEnabled, autoPlayPause, dualDevice)
        } else {
            null
        }
    }

    private fun parseStructuredFeaturePairs(data: ByteArray, payloadStart: Int, payLen: Int): Status? {
        if (payLen < 2) return null

        val statusByte = data[payloadStart].toInt() and 0xFF
        val count = data[payloadStart + 1].toInt() and 0xFF
        if (statusByte != 0x00 || count <= 0 || payLen < 2 + count * 2) return null

        var mainEnabled: Boolean? = null
        var lowLatencyEnabled: Boolean? = null
        var autoPlayPause: Boolean? = null
        var dualDevice: Boolean? = null
        for (j in 0 until count) {
            val index = payloadStart + 2 + j * 2
            val featureId = data[index].toInt() and 0xFF
            val enabled = (data[index + 1].toInt() and 0xFF) == 0x01
            when (featureId) {
                GameModeFeature.MAIN -> mainEnabled = enabled
                GameModeFeature.LOW_LATENCY -> lowLatencyEnabled = enabled
                BatchParamId.AUTO_PLAY_PAUSE -> autoPlayPause = enabled
                BatchParamId.DUAL_DEVICE -> dualDevice = enabled
            }
        }
        return if (mainEnabled != null || lowLatencyEnabled != null || autoPlayPause != null || dualDevice != null) {
            Status(mainEnabled, lowLatencyEnabled, autoPlayPause, dualDevice)
        } else {
            null
        }
    }
}

object SpatialAudioParser {
    fun parseModeNotify(packet: ByteArray): Int? {
        if (packet.size < 10 || packet[0] != 0xAA.toByte()) return null
        val cmd = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        if (cmd != Cmd.SPATIAL_AUDIO_NOTIFY) return null
        val payLen = (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8)
        if (payLen < 1 || packet.size < 9 + payLen) return null
        val mode = packet[9].toInt() and 0xFF
        return mode.takeIf { it in SpatialAudioMode.OFF..SpatialAudioMode.HEAD_TRACKING }
    }

    fun parseSetResponseStatus(packet: ByteArray): Int? {
        if (packet.size < 10 || packet[0] != 0xAA.toByte()) return null
        val cmd = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        if (cmd != Cmd.SET_SPATIAL_AUDIO_RESPONSE) return null
        val payLen = (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8)
        if (payLen < 1 || packet.size < 9 + payLen) return null
        return packet[9].toInt() and 0xFF
    }
}

object SwitchFeatureSetParser {
    data class Result(
        val status: Int,
        val value: Int?
    )

    fun parse(data: ByteArray): Result? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.SET_GAME_MODE_RESPONSE) return null

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9
        if (payLen <= 0 || data.size < payloadStart + payLen) return null

        val status = data[payloadStart].toInt() and 0xFF
        val value = if (payLen > 1) data[payloadStart + 1].toInt() and 0xFF else null
        return Result(status, value)
    }
}

/** 已连接设备信息（来自耳机 0x0204 eventCode=0x06 主动上报）。 */
@Parcelize
data class ConnectedDevice(
    val mac: String,
    val connected: Boolean,
    val active: Boolean,
    val name: String
) : Parcelable

/** 解析 0x0204 主动上报中的已连接设备信息（eventCode=0x06, MultiConnectInformations）。 */
object ConnectedDevicesParser {
    private const val EVENT_CODE = 0x06

    /**
     * 从原始外层包解析已连接设备列表。
     * payload 格式: [0x06] [Count] [Device1] [Device2] ...
     * 每台设备: [MAC 6B LE] [ProfileFlags 1B] [ConnState 1B] [IsActive 1B] [NameLen 1B] [Name UTF-8]
     */
    fun parse(data: ByteArray): List<ConnectedDevice>? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.ANC_MODE_NOTIFY) return null // 0x0204

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9
        if (data.size < payloadStart + payLen) return null
        if (payLen < 2) return null

        val eventCode = data[payloadStart].toInt() and 0xFF
        if (eventCode != EVENT_CODE) return null

        val count = data[payloadStart + 1].toInt() and 0xFF
        val devices = mutableListOf<ConnectedDevice>()
        var i = payloadStart + 2

        for (d in 0 until count) {
            if (i + 11 > data.size) break
            val macBytes = data.sliceArray(i until i + 6)
            val mac = macBytes.reversed().joinToString(":") { "%02X".format(it) }
            i += 6
            i += 1 // profileFlags
            val connectionState = data[i].toInt() and 0xFF
            i += 1
            val isActive = data[i].toInt() and 0xFF == 0x01
            i += 1
            val nameLen = data[i].toInt() and 0xFF
            i += 1
            val name = if (nameLen > 0 && i + nameLen <= data.size) {
                data.sliceArray(i until i + nameLen).decodeToString()
            } else ""
            i += nameLen

            devices.add(ConnectedDevice(
                mac = mac,
                connected = connectionState == 0x02,
                active = isActive,
                name = name
            ))
        }
        return devices
    }
}

/** 解析 0x8200 通知能力响应，返回耳机支持的 eventCode 列表。 */
object BroadcastCodesParser {
    fun parse(data: ByteArray): List<Int>? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)
        if (cmd != Cmd.BROADCAST_CODES_RESPONSE) return null

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9
        if (data.size < payloadStart + payLen || payLen < 2) return null

        val status = data[payloadStart].toInt() and 0xFF
        if (status != 0x00) return null

        val count = data[payloadStart + 1].toInt() and 0xFF
        if (payLen < 2 + count) return null

        return (0 until count).mapNotNull { i ->
            val idx = payloadStart + 2 + i
            if (idx < data.size) data[idx].toInt() and 0xFF else null
        }
    }
}

/**
 * 解析智能降噪模式下耳机主动推送的当前自动应用降噪等级通知。
 *
 * cmd 0x0204, type 0x03, key 0x04。
 * bitmap 中 bit 4 = 深度, bit 5 = 中度, bit 6 = 轻度。
 * 返回 [NoiseLevel] 常量，或 null（非智能等级通知）。
 */
object SmartAncLevelParser {
    fun parse(data: ByteArray): Int? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null
        val cmd = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        if (cmd != Cmd.ANC_MODE_NOTIFY) return null
        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9
        if (data.size < payloadStart + payLen || payLen < 4) return null
        if ((data[payloadStart].toInt() and 0xFF) != 0x03) return null
        if ((data[payloadStart + 1].toInt() and 0xFF) != 0x04) return null
        if ((data[payloadStart + 2].toInt() and 0xFF) != 0x01) return null

        val bitmapStart = payloadStart + 3
        val bitmapEnd = payloadStart + payLen
        for (i in bitmapStart until bitmapEnd) {
            val b = data[i].toInt() and 0xFF
            if (b == 0) continue
            for (n in 0..7) {
                if ((b and (1 shl n)) == 0) continue
                val bit = (i - bitmapStart) * 8 + n
                return when (bit) {
                    4 -> NoiseLevel.DEEP
                    5 -> NoiseLevel.MEDIUM
                    6 -> NoiseLevel.LIGHT
                    else -> null
                }
            }
        }
        return null
    }
}
