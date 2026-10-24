package moe.chenxy.oppopods.pods

/**
 * 贴合度检测与黄金听感的 OPPO 协议编解码。
 *
 * 帧格式沿用 [OppoPackets]：`AA TotalLen 0000 Cmd(LE16) Seq PayLen(LE16) Payload`。
 * 这里只负责 payload 与响应解析，不做重传/超时；状态机在 [CalibrationEngine]。
 *
 * 关键约定（与欢律 17.6.5 / 无线耳机 17.6.3 及 Enco X4 v116 对拍）：
 * - UID 是 Java signed int 的 **大端** 4 字节；长度/采样数/filter 浮点为小端。
 * - 0x040E action 1/3 才带 count、UID 和名称；action 2/4 只有一个字节。
 * - 耳扫事件 14 必须校验 UID 与 blob 非空。
 */
object CalibrationProtocol {

    const val CMD_FIT = 0x0405
    const val CMD_HEARING_DETECT = 0x040D
    const val CMD_HEARING_PARAMS = 0x040E
    const val CMD_RESTORE = 0x0411
    const val CMD_EAR_SCAN_SAVE = 0x0415

    const val CMD_CURRENT_HEARING = 0x0115
    const val CMD_FILTER = 0x0116
    const val CMD_CURRENT_EAR_SCAN = 0x011E
    const val CMD_EAR_SCAN_FILTER = 0x011F

    const val CMD_WEAR_STATUS = 0x0109
    const val CMD_FEATURE_SWITCH = 0x0403

    const val CMD_SET_ACK_MASK = 0x8000
    const val CMD_EVENT_NOTIFY = 0x0204
    const val CMD_EVENT_NOTIFY_ACK = 0x8202

    const val EVENT_FIT_RESULT = 4
    const val EVENT_HEARING_STATUS = 8
    const val EVENT_EAR_SCAN = 14

    const val FEATURE_HEARING_ENHANCEMENT = 0x0B

    // ------------------------------------------------------------ 构建请求

    /** 贴合度：`[01]` 开始，`[00]` 停止。 */
    fun buildFit(start: Boolean): ByteArray =
        OppoPackets.buildPacket(CMD_FIT, payload = byteArrayOf(if (start) 1 else 0))

    /** 佩戴查询 0x0109（无 payload）。 */
    fun buildWearQuery(): ByteArray = OppoPackets.buildPacket(CMD_WEAR_STATUS)

    /** 主观测听：`[02,01]` 开始 / `[02,00]` 停止。 */
    fun buildHearingDetection(start: Boolean): ByteArray =
        OppoPackets.buildPacket(
            CMD_HEARING_DETECT,
            payload = byteArrayOf(0x02, if (start) 1 else 0),
        )

    /** 耳道扫描：`[04,01,UID:BE32]` / `[04,00,UID:BE32]`。 */
    fun buildEarScan(start: Boolean, uid: Int): ByteArray {
        val payload = ByteArray(6)
        payload[0] = 0x04
        payload[1] = if (start) 1 else 0
        writeIntBe(payload, 2, uid)
        return OppoPackets.buildPacket(CMD_HEARING_DETECT, payload = payload)
    }

    /** 单频点：`[03,01,side,type,db:i8]`。 */
    fun buildFrequency(side: Int, type: Int, db: Int): ByteArray =
        OppoPackets.buildPacket(
            CMD_HEARING_PARAMS,
            payload = byteArrayOf(
                0x03,
                0x01,
                (side and 0xFF).toByte(),
                (type and 0xFF).toByte(),
                (db and 0xFF).toByte(),
            ),
        )

    fun buildPauseHearing(): ByteArray =
        OppoPackets.buildPacket(CMD_HEARING_PARAMS, payload = byteArrayOf(0x04))

    fun buildStopPreview(): ByteArray =
        OppoPackets.buildPacket(CMD_HEARING_PARAMS, payload = byteArrayOf(0x02))

    /**
     * 试听/保存：action 1=试听、3=保存。
     * `[action,count,(side,type,db:i8)*count,UID:BE32,name:UTF8]`
     */
    fun buildHearingParameters(
        action: Int,
        levels: List<HearingPoint>,
        uid: Int,
        name: String = "",
    ): ByteArray {
        require(action == 1 || action == 3) { "action must be 1 or 3" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + levels.size * 3 + 4 + nameBytes.size)
        payload[0] = action.toByte()
        payload[1] = levels.size.toByte()
        var offset = 2
        for (level in levels) {
            payload[offset++] = level.side.toByte()
            payload[offset++] = level.type.toByte()
            payload[offset++] = level.db.toByte()
        }
        writeIntBe(payload, offset, uid)
        offset += 4
        nameBytes.copyInto(payload, offset)
        return OppoPackets.buildPacket(CMD_HEARING_PARAMS, payload = payload)
    }

    /** 当前黄金听感：0x0115，无 payload。 */
    fun buildCurrentHearingQuery(): ByteArray = OppoPackets.buildPacket(CMD_CURRENT_HEARING)

    /** 测听滤波：`[count,(side,type,db)*count,UID:BE32]`。 */
    fun buildFilterRequest(levels: List<HearingPoint>, uid: Int): ByteArray {
        val payload = ByteArray(1 + levels.size * 3 + 4)
        payload[0] = levels.size.toByte()
        var offset = 1
        for (level in levels) {
            payload[offset++] = level.side.toByte()
            payload[offset++] = level.type.toByte()
            payload[offset++] = level.db.toByte()
        }
        writeIntBe(payload, offset, uid)
        return OppoPackets.buildPacket(CMD_FILTER, payload = payload)
    }

    /** 当前耳扫 0x011E。 */
    fun buildCurrentEarScanQuery(): ByteArray = OppoPackets.buildPacket(CMD_CURRENT_EAR_SCAN)

    /** 耳扫滤波：`[len:LE16,blob,UID:BE32]`。 */
    fun buildEarScanFilterRequest(scan: ByteArray, uid: Int): ByteArray {
        val payload = ByteArray(2 + scan.size + 4)
        payload[0] = (scan.size and 0xFF).toByte()
        payload[1] = ((scan.size shr 8) and 0xFF).toByte()
        scan.copyInto(payload, 2)
        writeIntBe(payload, 2 + scan.size, uid)
        return OppoPackets.buildPacket(CMD_EAR_SCAN_FILTER, payload = payload)
    }

    /**
     * 耳扫参数：action 1=试听、2=停止、3=保存。
     * `[action,len:LE16,blob,UID:BE32]`；action 2 只有一个字节。
     */
    fun buildEarScanParameters(action: Int, scan: ByteArray, uid: Int): ByteArray {
        if (action == 2) return OppoPackets.buildPacket(CMD_EAR_SCAN_SAVE, payload = byteArrayOf(0x02))
        val payload = ByteArray(3 + scan.size + 4)
        payload[0] = action.toByte()
        payload[1] = (scan.size and 0xFF).toByte()
        payload[2] = ((scan.size shr 8) and 0xFF).toByte()
        scan.copyInto(payload, 3)
        writeIntBe(payload, 3 + scan.size, uid)
        return OppoPackets.buildPacket(CMD_EAR_SCAN_SAVE, payload = payload)
    }

    /** 保存耳扫：`[03,len:LE16,blob,UID:BE32]`。 */
    fun buildSaveEarScan(scan: ByteArray, uid: Int): ByteArray =
        buildEarScanParameters(3, scan, uid)

    /** 方案描述：`[01,01,01,00,desc]`。 */
    fun buildRestoreDescription(descriptionId: Int): ByteArray =
        OppoPackets.buildPacket(
            CMD_RESTORE,
            payload = byteArrayOf(0x01, 0x01, 0x01, 0x00, (descriptionId and 0xFF).toByte()),
        )

    /** 0x0403 通用功能开关。 */
    fun buildFeatureSwitch(feature: Int, enabled: Boolean): ByteArray =
        OppoPackets.buildPacket(
            CMD_FEATURE_SWITCH,
            payload = byteArrayOf(feature.toByte(), if (enabled) 1 else 0),
        )

    /** 0x010D 批量查询：`[count,feature...]`。 */
    fun buildFeatureQuery(feature: Int): ByteArray =
        OppoPackets.buildPacket(0x010D, payload = byteArrayOf(0x01, feature.toByte()))

    // ------------------------------------------------------------ 解析响应

    data class FitResult(val left: Int, val right: Int)

    data class WearState(val leftInEar: Boolean, val rightInEar: Boolean) {
        val bothInEar: Boolean get() = leftInEar && rightInEar
    }

    data class HearingEvent(val detectionType: Int, val status: Int)

    data class EarScanEvent(val action: Int, val data: ByteArray, val uid: Int)

    data class CurrentHearing(
        val levels: List<HearingPoint>,
        val uid: Int,
        val name: String,
    )

    fun parseFit(packet: ByteArray): FitResult? {
        val data = eventData(packet, EVENT_FIT_RESULT) ?: return null
        if (data.size != 4) return null
        if (data[0].toInt() != 1 || data[2].toInt() != 2) return null
        val left = data[1].toInt() and 0xFF
        val right = data[3].toInt() and 0xFF
        if (!FitQuality.isKnown(left) || !FitQuality.isKnown(right)) return null
        return FitResult(left, right)
    }

    /** 0x8109：`[status][count]([component][bits])*`，bit1 = 入耳。 */
    fun parseWear(packet: ByteArray): WearState? {
        if (commandOf(packet) != (CMD_WEAR_STATUS or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 2) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        return parseWearPairs(payload, 1)
    }

    /** 0x0204 事件 2 的佩戴状态主动上报：`[count]([component][bits])*`。 */
    fun parseWearEvent(packet: ByteArray): WearState? {
        val data = eventData(packet, 2) ?: return null
        return parseWearPairs(data, 0)
    }

    private fun parseWearPairs(data: ByteArray, countOffset: Int): WearState? {
        if (data.size <= countOffset) return null
        val count = data[countOffset].toInt() and 0xFF
        if (data.size < countOffset + 1 + count * 2) return null
        var left = false
        var right = false
        for (index in 0 until count) {
            val component = data[countOffset + 1 + index * 2].toInt() and 0xFF
            val bits = data[countOffset + 2 + index * 2].toInt() and 0xFF
            val inEar = (bits and 0x02) != 0
            when (component) {
                1 -> left = inEar
                2 -> right = inEar
            }
        }
        return WearState(left, right)
    }

    fun parseHearingEvent(packet: ByteArray): HearingEvent? {
        val data = eventData(packet, EVENT_HEARING_STATUS) ?: return null
        if (data.size < 2) return null
        return HearingEvent(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF)
    }

    /** 事件 14：`[action,len:LE16,blob,UID:BE32]`。 */
    fun parseEarScanEvent(packet: ByteArray): EarScanEvent? {
        val data = eventData(packet, EVENT_EAR_SCAN) ?: return null
        return parseEarScanData(data)
    }

    /** 0x8115：`[status,03,0C,(side,type,db)*12,UID:BE32,name]`。 */
    fun parseCurrentHearing(packet: ByteArray): CurrentHearing? {
        if (commandOf(packet) != (CMD_CURRENT_HEARING or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 7) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        if ((payload[1].toInt() and 0xFF) != 3) return null
        val count = payload[2].toInt() and 0xFF
        if (count != 12) return null
        val levelsEnd = 3 + count * 3
        if (payload.size < levelsEnd + 4) return null
        val levels = ArrayList<HearingPoint>(count)
        for (index in 0 until count) {
            val offset = 3 + index * 3
            levels += HearingPoint(
                side = payload[offset].toInt() and 0xFF,
                type = payload[offset + 1].toInt() and 0xFF,
                db = payload[offset + 2].toInt(),
            )
        }
        val uid = readIntBe(payload, levelsEnd)
        val name = runCatching {
            payload.copyOfRange(levelsEnd + 4, payload.size)
                .toString(Charsets.UTF_8)
                .trim('\u0000', ' ')
        }.getOrDefault("")
        return CurrentHearing(levels, uid, name)
    }

    /** 0x811E：`[status,action,len:LE16,blob,UID:BE32]`。 */
    fun parseCurrentEarScan(packet: ByteArray): EarScanEvent? {
        if (commandOf(packet) != (CMD_CURRENT_EAR_SCAN or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 2) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        return parseEarScanData(payload.copyOfRange(1, payload.size))
    }

    /** 0x8116 的 UID（用于过滤请求关联）。 */
    fun parseFilterUid(packet: ByteArray): Int? {
        if (commandOf(packet) != (CMD_FILTER or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 5) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        return readIntBe(payload, 1)
    }

    /** 0x811F 的 UID。 */
    fun parseEarScanFilterUid(packet: ByteArray): Int? {
        if (commandOf(packet) != (CMD_EAR_SCAN_FILTER or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 2) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        return readIntBe(payload, 1)
    }

    /** 0x8403 等设置响应状态；[request] 为原始命令。 */
    fun parseSetAck(packet: ByteArray, request: Int): Int? {
        if (commandOf(packet) != (request or CMD_SET_ACK_MASK)) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.isEmpty()) return null
        return payload[0].toInt() and 0xFF
    }

    /** 0x810D 中某个 feature 的开关；找不到返回 null。 */
    fun parseFeatureSwitch(packet: ByteArray, feature: Int): Boolean? {
        if (commandOf(packet) != 0x810D) return null
        val payload = payloadOf(packet) ?: return null
        if (payload.size < 2) return null
        if ((payload[0].toInt() and 0xFF) != 0) return null
        val count = payload[1].toInt() and 0xFF
        if (payload.size >= 2 + count * 2) {
            for (index in 0 until count) {
                val id = payload[2 + index * 2].toInt() and 0xFF
                if (id == feature) {
                    return (payload[3 + index * 2].toInt() and 0xFF) == 1
                }
            }
        }
        // 部分固件省略 count 或使用扁平键值对，做一次宽松回退。
        for (index in 0 until payload.size - 1) {
            if ((payload[index].toInt() and 0xFF) != feature) continue
            val value = payload[index + 1].toInt() and 0xFF
            if (value == 0 || value == 1) return value == 1
        }
        return null
    }

    /** 事件封套：0x0204 从 0 开始；0x8202 先跳过成功状态。 */
    private fun eventData(packet: ByteArray, eventId: Int): ByteArray? {
        if (packet.size < 9 || packet[0] != 0xAA.toByte()) return null
        val command = commandOf(packet)
        val payload = payloadOf(packet) ?: return null
        val offset = when (command) {
            CMD_EVENT_NOTIFY -> 0
            CMD_EVENT_NOTIFY_ACK -> 1
            else -> return null
        }
        if (payload.size <= offset) return null
        if (offset == 1 && (payload[0].toInt() and 0xFF) != 0) return null
        if ((payload[offset].toInt() and 0xFF) != eventId) return null
        return payload.copyOfRange(offset + 1, payload.size)
    }

    private fun parseEarScanData(data: ByteArray): EarScanEvent? {
        if (data.size < 7) return null
        val length = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        if (length > 32767 || data.size != length + 7) return null
        val scan = data.copyOfRange(3, 3 + length)
        val uid = readIntBe(data, 3 + length)
        return EarScanEvent(data[0].toInt() and 0xFF, scan, uid)
    }

    /**
     * 帧头长度字段是 LEB128 变长整数（1~2 字节），大包会多占一个字节，
     * 所以命令行和 payload 的偏移必须按实际长度字节数计算，不能写死 4/7/9。
     */
    private fun headerOffset(packet: ByteArray): Int {
        if (packet.size < 2 || packet[0] != 0xAA.toByte()) return -1
        var lengthBytes = 0
        while (lengthBytes < 2) {
            if (1 + lengthBytes >= packet.size) return -1
            val value = packet[1 + lengthBytes].toInt() and 0xFF
            lengthBytes++
            if (value and 0x80 == 0) break
        }
        // length 统计的是「control + 保留字节 + inner」，所以 inner 从 3 + lengthBytes 开始。
        return 3 + lengthBytes
    }

    fun commandOf(packet: ByteArray): Int {
        val offset = headerOffset(packet)
        if (offset < 0 || offset + 2 > packet.size) return -1
        return (packet[offset].toInt() and 0xFF) or
                ((packet[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun payloadOf(packet: ByteArray): ByteArray? {
        val offset = headerOffset(packet)
        if (offset < 0) return null
        // offset = cmd(2) + seq(1) + payLen(2) + payload
        val lengthOffset = offset + 3
        if (lengthOffset + 2 > packet.size) return null
        val length = (packet[lengthOffset].toInt() and 0xFF) or
                ((packet[lengthOffset + 1].toInt() and 0xFF) shl 8)
        val payloadStart = offset + 5
        if (packet.size < payloadStart + length) return null
        return packet.copyOfRange(payloadStart, payloadStart + length)
    }

    private fun writeIntBe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readIntBe(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
