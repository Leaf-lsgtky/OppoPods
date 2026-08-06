package moe.chenxy.oppopods.pods

/**
 * 把 [DeviceCapabilities] 转换成现有的 [DeviceProfile]。
 *
 * 这是白名单接入的核心：OPPO 协议全系统一（ANC 走 0x0404 位图，功能开关走
 * 0x0403 [featureType][0/1]），机型差异只体现在 `protocolIndex` 上。所以除了
 * 少数需要机型信息的地方，所有 payload 都能算出来，不必逐个机型手写。
 *
 * 生成结果仍是普通 [DeviceProfile]，因此收发链路上的既有调用点全部无需改动。
 * 耳机素材（图片/视频）与机型配置无关，由 [PodImageStore] 单独管理。
 */
object CapabilityProfileFactory {

    /** 自动生成的配置 id 前缀。 */
    const val GENERATED_ID_PREFIX = "auto_"

    fun generatedId(modelId: String): String = GENERATED_ID_PREFIX + modelId

    /** 该 id 是否为白名单自动生成。 */
    fun isGenerated(id: String): Boolean = id.startsWith(GENERATED_ID_PREFIX)

    /** 按能力集生成配置档。 */
    fun from(caps: DeviceCapabilities): DeviceProfile {
        val commands = buildMap {
            putAll(ancCommands(caps))
            putAll(noiseLevelCommands(caps))
            putAll(featureCommands(caps))
            putAll(spatialCommands(caps))
            putAll(QUERY_COMMANDS)
        }

        return DeviceProfile(
            id = generatedId(caps.modelId.ifBlank { caps.modelName }),
            name = caps.modelName,
            adaptiveVisible = caps.hasAdaptiveAnc &&
                    caps.ancNameToIndex.containsKey(AncKeys.ADAPTIVE),
            gameModeVisible = caps.hasGameMode,
            noiseLevelVisible = caps.hasNoiseLevels,
            autoPlayPauseVisible = caps.hasWearDetection,
            dualDeviceVisible = caps.hasDualDevice,
            connectedDevicesVisible = caps.hasDualDevice,
            spatialAudioVisible = caps.hasSpatialAudio,
            spatialSoundVisible = caps.hasSpatialSound,
            eqPresets = caps.eqPresets,
            customEqVisible = caps.hasCustomEq,
            customEqFrequencies = caps.customEqFrequencies,
            customEqMaxPresets = caps.customEqMaxPresets,
            commands = commands,
            ancIndexToName = caps.ancIndexToName,
            isLegacyAnc = caps.isLegacyAnc,
            modelId = caps.modelId,
        )
    }

    // ------------------------------------------------------------------ ANC

    /**
     * ANC 位图 payload：`01 01 <bitmap>`，第 protocolIndex 位置 1。
     *
     * 例如 protocolIndex=1 → "01 01 02"，=2 → "01 01 04"，=11 → "01 01 00 08"。
     * 这三个正是此前硬编码在种子配置里的值，现在由索引推导。
     */
    fun ancPayload(protocolIndex: Int): String {
        val byteCount = protocolIndex / 8 + 1
        val bytes = ByteArray(2 + byteCount)
        bytes[0] = 0x01
        bytes[1] = 0x01
        bytes[2 + protocolIndex / 8] = (1 shl (protocolIndex % 8)).toByte()
        return bytes.toHex()
    }

    private fun ancCommands(caps: DeviceCapabilities): Map<String, PodCommand> {
        val map = LinkedHashMap<String, PodCommand>()
        fun put(profileKey: String, ancKey: String) {
            val index = caps.ancNameToIndex[ancKey] ?: return
            map[profileKey] = PodCommand(Cmd.SET_ANC, payload = ancPayload(index))
        }
        put(ProfileKeys.ANC_OFF, AncKeys.OFF)
        put(ProfileKeys.ANC_NC, AncKeys.NC)
        put(ProfileKeys.ANC_TRANSPARENCY, AncKeys.TRANSPARENCY)
        put(ProfileKeys.ANC_ADAPTIVE, AncKeys.ADAPTIVE)

        // 老机型（NC=idx0 且无子模式）在协议上把降噪/通透的位对调了。上面按名字取
        // 索引已经拿到正确的位，无需再交换；这里仅在缺失 NC 时用 Smart 兜底。
        if (!map.containsKey(ProfileKeys.ANC_NC)) put(ProfileKeys.ANC_NC, AncKeys.SMART)
        return map
    }

    private fun noiseLevelCommands(caps: DeviceCapabilities): Map<String, PodCommand> {
        val map = LinkedHashMap<String, PodCommand>()
        fun put(profileKey: String, ancKey: String) {
            val index = caps.ancNameToIndex[ancKey] ?: return
            map[profileKey] = PodCommand(Cmd.SET_ANC, payload = ancPayload(index))
        }
        put(ProfileKeys.SET_NOISE_LEVEL_SMART, AncKeys.SMART)
        put(ProfileKeys.SET_NOISE_LEVEL_LIGHT, AncKeys.LIGHT)
        put(ProfileKeys.SET_NOISE_LEVEL_MEDIUM, AncKeys.MEDIUM)
        put(ProfileKeys.SET_NOISE_LEVEL_DEEP, AncKeys.DEEP)
        return map
    }

    // -------------------------------------------------------------- 功能开关

    /** 0x0403 通用功能开关：payload = [featureType][0/1]。 */
    private fun featurePayload(feature: Int, enabled: Boolean): String =
        "%02X %02X".format(feature, if (enabled) 1 else 0)

    private fun featureCommands(caps: DeviceCapabilities): Map<String, PodCommand> {
        val map = LinkedHashMap<String, PodCommand>()
        fun putSwitch(onKey: String, offKey: String, feature: Int) {
            map[onKey] = PodCommand(Cmd.SET_GAME_MODE, payload = featurePayload(feature, true))
            map[offKey] = PodCommand(Cmd.SET_GAME_MODE, payload = featurePayload(feature, false))
        }
        if (caps.hasGameMode) {
            putSwitch(ProfileKeys.GAME_ON, ProfileKeys.GAME_OFF, caps.gameModeFeatureId)
        }
        if (caps.hasWearDetection) {
            putSwitch(
                ProfileKeys.SET_AUTO_PLAY_PAUSE_ON,
                ProfileKeys.SET_AUTO_PLAY_PAUSE_OFF,
                BatchParamId.AUTO_PLAY_PAUSE,
            )
        }
        if (caps.hasDualDevice) {
            putSwitch(
                ProfileKeys.SET_DUAL_DEVICE_ON,
                ProfileKeys.SET_DUAL_DEVICE_OFF,
                BatchParamId.DUAL_DEVICE,
            )
        }
        if (caps.hasSpatialSound) {
            putSwitch(
                ProfileKeys.SPATIAL_SOUND_ON,
                ProfileKeys.SPATIAL_SOUND_OFF,
                BatchParamId.SPATIAL_SOUND,
            )
        }
        return map
    }

    /** 空间音频三模式走独立命令 0x0422，payload 直接是模式值。 */
    private fun spatialCommands(caps: DeviceCapabilities): Map<String, PodCommand> {
        if (!caps.hasSpatialAudio) return emptyMap()
        return mapOf(
            ProfileKeys.SPATIAL_OFF to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "00"),
            ProfileKeys.SPATIAL_FIXED to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "01"),
            ProfileKeys.SPATIAL_HEAD to PodCommand(Cmd.SET_SPATIAL_AUDIO, payload = "02"),
        )
    }

    // ---------------------------------------------------------------- 查询

    /** 查询类指令全系一致，不随机型变化。 */
    private val QUERY_COMMANDS: Map<String, PodCommand> = mapOf(
        ProfileKeys.QUERY_BATTERY to PodCommand(Cmd.QUERY_BATTERY, seq = 0xF0, payload = ""),
        ProfileKeys.QUERY_ANC to PodCommand(Cmd.QUERY_ANC_MODE, payload = "01 01"),
        ProfileKeys.QUERY_EQ to PodCommand(Cmd.QUERY_EQ, payload = ""),
        ProfileKeys.QUERY_EQ_ALL to PodCommand(Cmd.QUERY_EQ_ALL, payload = "01 05"),
        ProfileKeys.QUERY_STATUS to PodCommand(
            Cmd.QUERY_STATUS, seq = 0x00,
            payload = "0B 05 04 0B 11 13 18 06 1B 1C 27 28",
        ),
    )
}
