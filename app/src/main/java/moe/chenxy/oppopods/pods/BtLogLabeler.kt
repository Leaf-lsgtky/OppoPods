package moe.chenxy.oppopods.pods

/**
 * Generates human-readable labels for OPPO RFCOMM packets.
 */
object BtLogLabeler {

    /**
     * Generate a label for a sent packet.
     * Returns null if the packet is not recognized.
     */
    fun labelSend(packet: ByteArray): String? {
        val cmd = extractCmd(packet) ?: return null
        val payload = extractPayload(packet)
        return when (cmd) {
            Cmd.SET_ANC -> {
                if (payload.size >= 2) {
                    val modeByte = payload[0].toInt() and 0xFF
                    val valueByte = payload[1].toInt() and 0xFF
                    when {
                        modeByte == 0x02 && valueByte == 0x00 -> "切换降噪模式"
                        modeByte == 0x01 -> "设置降噪深度"
                        else -> "设置ANC"
                    }
                } else "设置ANC"
            }
            Cmd.SET_GAME_MODE -> {
                if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x1B) "切换空间音效"
                else "切换功能开关"
            }
            Cmd.SET_SPATIAL_AUDIO -> "切换空间音频"
            Cmd.QUERY_BATTERY -> "查询电量"
            Cmd.QUERY_ANC_MODE -> "查询ANC模式"
            Cmd.QUERY_STATUS -> "查询状态"
            Cmd.HANDSHAKE -> "握手"
            Cmd.QUERY_BROADCAST_CODES -> "查询通知能力"
            Cmd.SUBSCRIBE_BROADCAST -> "订阅通知"
            else -> null
        }
    }

    /**
     * Generate a label for a received packet.
     * Returns null if the packet is not recognized.
     */
    fun labelRecv(packet: ByteArray): String? {
        val cmd = extractCmd(packet) ?: return null
        val payload = extractPayload(packet)
        return when (cmd) {
            Cmd.BATTERY_RESPONSE -> "电量查询响应"
            Cmd.ANC_MODE_RESPONSE -> "ANC模式查询响应"
            Cmd.QUERY_STATUS_RESPONSE -> "状态查询响应"
            Cmd.HANDSHAKE_RESPONSE -> "握手响应"
            Cmd.SET_GAME_MODE_RESPONSE -> {
                if (payload.size >= 2 && (payload[0].toInt() and 0xFF) == 0x1B) "空间音效响应"
                else "功能开关响应"
            }
            Cmd.SET_SPATIAL_AUDIO_RESPONSE -> "空间音频响应"
            Cmd.BROADCAST_CODES_RESPONSE -> "通知能力响应"
            Cmd.SUBSCRIBE_BROADCAST_RESPONSE -> "订阅通知响应"
            Cmd.SPATIAL_AUDIO_NOTIFY -> "空间音频变化通知"
            Cmd.ANC_MODE_NOTIFY -> {
                if (payload.isNotEmpty()) {
                    val eventCode = payload[0].toInt() and 0xFF
                    when (eventCode) {
                        0x01 -> "电量变化通知"
                        0x03 -> "ANC模式变化通知"
                        0x05 -> "游戏模式变化通知"
                        0x06 -> "已连接设备通知"
                        else -> "设备状态通知"
                    }
                } else "设备状态通知"
            }
            else -> null
        }
    }

    private fun extractCmd(packet: ByteArray): Int? {
        if (packet.size < 6) return null
        if (packet[0] != 0xAA.toByte()) return null
        val cmdLow = packet[4].toInt() and 0xFF
        val cmdHigh = packet[5].toInt() and 0xFF
        return cmdLow or (cmdHigh shl 8)
    }

    private fun extractPayload(packet: ByteArray): ByteArray {
        if (packet.size < 9) return byteArrayOf()
        val payLen = (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8)
        if (packet.size < 9 + payLen) return byteArrayOf()
        return packet.copyOfRange(9, 9 + payLen)
    }
}
