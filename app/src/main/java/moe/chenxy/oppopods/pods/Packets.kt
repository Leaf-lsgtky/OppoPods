package moe.chenxy.oppopods.pods

/**
 * OPPO earphone RFCOMM protocol packet definitions.
 *
 * Packet format (Little Endian for multi-byte fields):
 * Header(AA) + TotalLen(1B) + Res(0000) + Cmd(2B) + Seq(1B) + PayLen(2B) + Payload
 */

object OppoPackets {

    /** Build a complete OPPO protocol packet. */
    fun buildPacket(cmd: Int, seq: Int = 0, payload: ByteArray = byteArrayOf()): ByteArray {
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

/** ANC mode values for OPPO earphones. */
object AncMode {
    const val OFF = 0x01
    const val NOISE_CANCELLATION = 0x02
    const val TRANSPARENCY = 0x04
}

/** Noise control mode enum for UI. OPPO has 3 modes (no Adaptive). */
enum class NoiseControlMode {
    OFF, NOISE_CANCELLATION, TRANSPARENCY
}

/** Battery component index in response payload. */
object BatteryComponent {
    const val LEFT = 1
    const val RIGHT = 2
    const val CASE = 3
}

/** Protocol command codes. */
object Cmd {
    /** Set ANC mode */
    const val SET_ANC = 0x0404
    /** Query battery */
    const val QUERY_BATTERY = 0x0106
    /** Battery response from earphone */
    const val BATTERY_RESPONSE = 0x8106
}

/** Pre-built packets. */
object Enums {
    /** Switch to Noise Cancellation: AA 0A 00 00 04 04 00 03 00 01 01 02 */
    val ANC_NOISE_CANCEL: ByteArray = OppoPackets.buildPacket(
        cmd = Cmd.SET_ANC, payload = byteArrayOf(0x01, 0x01, AncMode.NOISE_CANCELLATION.toByte())
    )

    /** Switch to Transparency: AA 0A 00 00 04 04 00 03 00 01 01 04 */
    val ANC_TRANSPARENCY: ByteArray = OppoPackets.buildPacket(
        cmd = Cmd.SET_ANC, payload = byteArrayOf(0x01, 0x01, AncMode.TRANSPARENCY.toByte())
    )

    /** Switch to Off: AA 0A 00 00 04 04 00 03 00 01 01 01 */
    val ANC_OFF: ByteArray = OppoPackets.buildPacket(
        cmd = Cmd.SET_ANC, payload = byteArrayOf(0x01, 0x01, AncMode.OFF.toByte())
    )

    /** Query battery: AA 07 00 00 06 01 00 00 00 */
    val QUERY_BATTERY: ByteArray = byteArrayOf(
        0xAA.toByte(), 0x07, 0x00, 0x00, 0x06, 0x01, 0x00, 0x00, 0x00
    )
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
     * Parse a raw packet buffer for battery response.
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
}
