package moe.chenxy.oppopods.pods

/**
 * OPPO earphone RFCOMM protocol packet definitions.
 *
 * Packet format (Little Endian for multi-byte fields):
 * Header(AA) + TotalLen(1B) + Res(0000) + Cmd(2B) + Seq(1B) + PayLen(2B) + Payload
 */

object OppoPackets {

    /** Build a complete OPPO protocol packet. */
    fun buildPacket(cmd: Int, seq: Int = 0xF0, payload: ByteArray = byteArrayOf()): ByteArray {
        val payLen = payload.size
        // TotalLen = 7 (header fields after TotalLen: Res(2) + Cmd(2) + Seq(1) + PayLen(2)) + payLen
        val totalLen = 7 + payLen
        val packet = ByteArray(2 + totalLen) // Header(1) + TotalLen(1) + rest
        packet[0] = 0xAA.toByte()           // Header
        packet[1] = totalLen.toByte()        // TotalLen
        packet[2] = 0x00                     // Res byte 1
        packet[3] = 0x00                     // Res byte 2
        packet[4] = (cmd and 0xFF).toByte()          // Cmd low byte
        packet[5] = ((cmd shr 8) and 0xFF).toByte()  // Cmd high byte
        packet[6] = seq.toByte()             // Seq
        packet[7] = (payLen and 0xFF).toByte()        // PayLen low byte
        packet[8] = ((payLen shr 8) and 0xFF).toByte() // PayLen high byte
        payload.copyInto(packet, 9)
        return packet
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
 */
object AncModeParser {

    fun parse(data: ByteArray): NoiseControlMode? {
        if (data.size < 9) return null
        if (data[0] != 0xAA.toByte()) return null

        val cmdLow = data[4].toInt() and 0xFF
        val cmdHigh = data[5].toInt() and 0xFF
        val cmd = cmdLow or (cmdHigh shl 8)

        if (cmd != Cmd.ANC_MODE_RESPONSE && cmd != Cmd.ANC_MODE_NOTIFY) return null

        val payLen = (data[7].toInt() and 0xFF) or ((data[8].toInt() and 0xFF) shl 8)
        val payloadStart = 9

        if (data.size < payloadStart + payLen) return null

        // For 0x0204, skip if this is a battery report (type=0x01) or button report (type=0x02)
        if (cmd == Cmd.ANC_MODE_NOTIFY && payLen > 0) {
            val reportType = data[payloadStart].toInt() and 0xFF
            if (reportType == 0x01 || reportType == 0x02) return null
        }

        // Scan for pattern: 01 01 [Val1] [Val2]
        for (i in payloadStart until minOf(payloadStart + payLen - 3, data.size - 3)) {
            if (data[i] == 0x01.toByte() && data[i + 1] == 0x01.toByte()) {
                val val1 = data[i + 2].toInt() and 0xFF
                val val2 = data[i + 3].toInt() and 0xFF

                return when {
                    val1 == 0x10 && val2 == 0x00 -> NoiseControlMode.NOISE_CANCELLATION
                    val1 == 0x00 && val2 == 0x01 -> NoiseControlMode.TRANSPARENCY
                    val1 == 0x08 && val2 == 0x00 -> NoiseControlMode.OFF
                    val1 == 0x00 && val2 == 0x08 -> NoiseControlMode.ADAPTIVE
                    else -> null
                }
            }
        }
        return null
    }
}

/**
 * Parser for game mode status from batch parameter query response (Cmd=0x810D).
 */
object GameModeParser {

    data class Status(
        val mainEnabled: Boolean?,
        val lowLatencyEnabled: Boolean?
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
        for (i in payloadStart until minOf(payloadStart + payLen - 1, data.size - 1)) {
            val value = data[i + 1].toInt() and 0xFF
            if (value != 0x00 && value != 0x01) continue
            when (data[i].toInt() and 0xFF) {
                GameModeFeature.MAIN -> mainEnabled = value == 0x01
                GameModeFeature.LOW_LATENCY -> lowLatencyEnabled = value == 0x01
            }
        }
        return if (mainEnabled != null || lowLatencyEnabled != null) {
            Status(mainEnabled, lowLatencyEnabled)
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
        for (j in 0 until count) {
            val index = payloadStart + 2 + j * 2
            val featureId = data[index].toInt() and 0xFF
            val enabled = (data[index + 1].toInt() and 0xFF) == 0x01
            when (featureId) {
                GameModeFeature.MAIN -> mainEnabled = enabled
                GameModeFeature.LOW_LATENCY -> lowLatencyEnabled = enabled
            }
        }
        return if (mainEnabled != null || lowLatencyEnabled != null) {
            Status(mainEnabled, lowLatencyEnabled)
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
