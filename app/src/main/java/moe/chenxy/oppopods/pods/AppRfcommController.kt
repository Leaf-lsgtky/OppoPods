package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream

/**
 * Standalone RFCOMM controller for direct use from the app process.
 * Does not depend on the hook runtime.
 */
@SuppressLint("MissingPermission")
class AppRfcommController {
    companion object {
        private const val TAG = "OppoPods-AppRfcomm"
        private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    private var socket: BluetoothSocket? = null
    private var isConnected = false
    private lateinit var profile: DeviceProfile
    private var currentEqPresetId = -1
    private val pendingDeletedEqIds = mutableSetOf<Int>()
    private var pendingSavedEqName: String? = null
    private var lastGameModeStatusUpdateMs = 0L
    private var firstBatteryReceived = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batteryPollJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _batteryParams = MutableStateFlow(BatteryParams())
    val batteryParams: StateFlow<BatteryParams> = _batteryParams

    private val _ancMode = MutableStateFlow(NoiseControlMode.OFF)
    val ancMode: StateFlow<NoiseControlMode> = _ancMode

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _gameMode = MutableStateFlow(false)
    val gameMode: StateFlow<Boolean> = _gameMode

    private val _spatialAudioMode = MutableStateFlow(SpatialAudioMode.OFF)
    val spatialAudioMode: StateFlow<Int> = _spatialAudioMode

    private val _noiseLevel = MutableStateFlow(NoiseLevel.DEEP)
    val noiseLevel: StateFlow<Int> = _noiseLevel

    private val _smartAncLevel = MutableStateFlow(-1)
    val smartAncLevel: StateFlow<Int> = _smartAncLevel

    private val _autoPlayPause = MutableStateFlow(false)
    val autoPlayPause: StateFlow<Boolean> = _autoPlayPause

    private val _dualDevice = MutableStateFlow(false)
    val dualDevice: StateFlow<Boolean> = _dualDevice

    private val _spatialSound = MutableStateFlow(false)
    val spatialSound: StateFlow<Boolean> = _spatialSound

    private val _eqPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    /** 型号内置预设 + 设备端 0x8122 回报的自定义预设。 */
    val eqPresets: StateFlow<List<EqPreset>> = _eqPresets

    private val _eqDevicePresets = MutableStateFlow<List<EqDevicePreset>>(emptyList())
    /** 0x0122 的完整设备端条目，供 EQ 二级页面编辑自定义曲线。 */
    val eqDevicePresets: StateFlow<List<EqDevicePreset>> = _eqDevicePresets

    private val _eqPresetId = MutableStateFlow(-1)
    val eqPresetId: StateFlow<Int> = _eqPresetId

    private val _connectedDevices = MutableStateFlow<List<ConnectedDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedDevice>> = _connectedDevices

    private val _connectedDevicesReceived = MutableStateFlow(false)
    val connectedDevicesReceived: StateFlow<Boolean> = _connectedDevicesReceived

    private val _identifiedModel = MutableStateFlow<String?>(null)
    /** 自动识别命中的白名单型号名；null 表示尚未识别或未命中。 */
    val identifiedModel: StateFlow<String?> = _identifiedModel

    /**
     * 收到 0x8103 后按 productId 解析配置档。由调用方注入（需要 Context 与 prefs），
     * 返回 null 表示不切换（非自动模式或型号未收录）。
     */
    var productIdResolver: ((String) -> DeviceProfile?)? = null

    fun setProfile(profile: DeviceProfile) {
        this.profile = profile
        _eqPresets.value = profile.eqPresets
        _eqDevicePresets.value = emptyList()
        pendingDeletedEqIds.clear()
        pendingSavedEqName = null
    }

    private fun mergeDeviceEqPresets(entries: List<EqParser.DevicePreset>) {
        val validEntries = entries.filter { it.id !in pendingDeletedEqIds }
        _eqDevicePresets.value = validEntries.map {
            EqDevicePreset(
                id = it.id,
                name = it.name,
                selected = it.selected,
                minValue = it.minValue,
                maxValue = it.maxValue,
                frequencies = it.frequencies,
                gains = it.gains,
            )
        }
        pendingSavedEqName?.let { name ->
            _eqDevicePresets.value.firstOrNull { it.name == name }?.id?.let { id ->
                currentEqPresetId = id
                _eqPresetId.value = id
                pendingSavedEqName = null
            }
        }
        val merged = LinkedHashMap<Int, EqPreset>()
        profile.eqPresets.forEach { merged[it.id] = it }
        validEntries.forEach { entry ->
            if (entry.name.isNotBlank()) {
                merged[entry.id] = EqPreset(entry.id, entry.name)
            } else if (!merged.containsKey(entry.id)) {
                merged[entry.id] = EqPreset(entry.id, "M${entry.id}")
            }
        }
        _eqPresets.value = merged.values.sortedBy { it.id }
    }

    fun connect(
        device: BluetoothDevice,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID,
        profile: DeviceProfile
    ) {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        this.profile = profile
        _eqPresets.value = profile.eqPresets
        _eqDevicePresets.value = emptyList()
        currentEqPresetId = -1
        _eqPresetId.value = -1
        firstBatteryReceived = false
        _deviceName.value = device.name ?: device.address
        _connectionState.value = ConnectionState.CONNECTING
        batteryPollJob?.cancel()

        scope.launch {
            try {
                delay(300)
                socket = OppoRfcommSocketFactory.connect(device, TAG, connectionMethod)
                Log.d(TAG, "RFCOMM connected to ${device.name}")
                isConnected = true
                _connectionState.value = ConnectionState.CONNECTED

                startPacketReader(socket!!.inputStream)

                delay(300)
                sendPacket(OppoPackets.buildHandshake())
                delay(200)
                // 先取 productId，命中白名单后 handlePacket 会重建 profile，
                // 后续查询与控制即基于该机型的位图索引。
                sendPacket(OppoPackets.buildQueryProductId())
                delay(200)
                sendPacket(OppoPackets.buildQueryBroadcastCodes())

                delay(300)
                queryStatus()

                startBatteryPolling()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                _connectionState.value = ConnectionState.ERROR
                isConnected = false
                batteryPollJob?.cancel()
            }
        }
    }

    private fun startBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = scope.launch {
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) queryStatus()
            }
        }
    }

    private fun startPacketReader(inputStream: InputStream) {
        scope.launch {
            val buffer = ByteArray(1024)
            val framer = OppoPacketFramer()
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        framer.append(buffer, bytesRead).forEach { packet ->
                            handlePacket(packet)
                        }
                    } else if (bytesRead == -1) {
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) Log.e(TAG, "Read error", e)
            }
            if (isConnected) disconnect()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handlePacket(packet: ByteArray) {
        BtLogStore.addRecv(packet, BtLogLabeler.labelRecv(packet))
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")
        }

        val result = BatteryParser.parse(packet)
        if (result != null) {
            val left = PodParams(
                result.left?.level ?: 0,
                result.left?.isCharging == true,
                result.left != null,
                0
            )
            val right = PodParams(
                result.right?.level ?: 0,
                result.right?.isCharging == true,
                result.right != null,
                0
            )
            val case = PodParams(
                result.case?.level ?: 0,
                result.case?.isCharging == true,
                result.case != null,
                0
            )
            _batteryParams.value = BatteryParams(left, right, case)
            // 首次收到电量后补查一次自定义 EQ 列表，防止握手期间 0x8122 响应丢失
            // 导致连接后自定义音效列表为空（参考 OppoPodsManager 的 _wasConnected 补查逻辑）
            if (!firstBatteryReceived) {
                firstBatteryReceived = true
                if (profile.customEqVisible) queryEqDetails()
            }
            return
        }

        // Try parse as active battery report (unsolicited, Cmd=0x0204, type=0x01)
        val activeResult = BatteryParser.parseActiveReport(packet)
        if (activeResult != null) {
            val current = _batteryParams.value
            val left = activeResult.left?.let {
                PodParams(it.level, it.isCharging, true, current.left?.rawStatus ?: 0)
            } ?: current.left
            val right = activeResult.right?.let {
                PodParams(it.level, it.isCharging, true, current.right?.rawStatus ?: 0)
            } ?: current.right
            val case = activeResult.case?.let {
                PodParams(it.level, it.isCharging, true, current.case?.rawStatus ?: 0)
            } ?: current.case
            _batteryParams.value = BatteryParams(left, right, case)
            if (!firstBatteryReceived) {
                firstBatteryReceived = true
                if (profile.customEqVisible) queryEqDetails()
            }
            return
        }

        // Try parse 0x8103 product id -> rebuild profile from the bundled model whitelist
        val productId = ProductIdParser.parse(packet)
        if (productId != null) {
            Log.d(TAG, "Product id received: $productId")
            val resolved = runCatching { productIdResolver?.invoke(productId) }.getOrNull()
            if (resolved != null) {
                profile = resolved
                _eqPresets.value = resolved.eqPresets
                _eqDevicePresets.value = emptyList()
                pendingDeletedEqIds.clear()
                pendingSavedEqName = null
                // profile 重建后允许下次电量回调再补查一次 EQ 列表
                firstBatteryReceived = false
                _identifiedModel.value = resolved.name
                Log.d(TAG, "Auto-identified as ${resolved.name} ($productId)")
                queryStatus()
            }
            return
        }

        val currentEq = EqParser.parseCurrent(packet)
        if (currentEq != null) {
            Log.d(TAG, "EQ preset received: $currentEq")
            currentEqPresetId = currentEq
            _eqPresetId.value = currentEq
            return
        }

        val deviceEqPresets = EqParser.parseAll(packet)
        if (deviceEqPresets.isNotEmpty() || EqParser.isAllResponse(packet)) {
            Log.d(TAG, "Device EQ presets received: ${deviceEqPresets.size}")
            mergeDeviceEqPresets(deviceEqPresets)
            val selected = deviceEqPresets.firstOrNull { it.selected }?.id
            if (selected != null) {
                currentEqPresetId = selected
                _eqPresetId.value = selected
            } else if (deviceEqPresets.isEmpty()) {
                currentEqPresetId = -1
                _eqPresetId.value = -1
            }
            pendingSavedEqName?.let { name ->
                _eqDevicePresets.value.firstOrNull { it.name == name }?.id?.let { id ->
                    currentEqPresetId = id
                    _eqPresetId.value = id
                    pendingSavedEqName = null
                }
            }
            return
        }

        val ancResult = AncModeParser.parse(
            packet,
            profile.ancIndexToName,
            profile.isLegacyAnc
        )
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: ${ancResult.mode}, noiseLevel=${ancResult.noiseLevel}")
            _ancMode.value = ancResult.mode
            if (ancResult.noiseLevel != null) {
                _noiseLevel.value = ancResult.noiseLevel
            }
            return
        }

        val smartLevel = SmartAncLevelParser.parse(packet)
        if (smartLevel != null) {
            Log.d(TAG, "Smart ANC current level: $smartLevel")
            _smartAncLevel.value = smartLevel
            return
        }

        // Try parse as batch query response for game mode (Cmd=0x810D)
        val gameModeResult = GameModeParser.parseForFeature(packet, profile.gameModeFeatureId())
        if (gameModeResult != null) {
            Log.d(TAG, "Game mode received: $gameModeResult")
            lastGameModeStatusUpdateMs = SystemClock.elapsedRealtime()
            _gameMode.value = gameModeResult
            return
        }

        // Try parse 0x8200 broadcast codes response
        val broadcastCodes = BroadcastCodesParser.parse(packet)
        if (broadcastCodes != null) {
            Log.d(TAG, "Broadcast codes received: $broadcastCodes")
            scope.launch {
                delay(100)
                sendPacket(OppoPackets.buildSubscribeBroadcast(broadcastCodes))
            }
            return
        }

        // Try parse batch status for autoPlayPause / dualDevice / spatialSound
        val batchStatus = GameModeParser.parseStatus(packet)
        if (batchStatus != null) {
            batchStatus.autoPlayPause?.let { _autoPlayPause.value = it }
            batchStatus.dualDevice?.let { _dualDevice.value = it }
            batchStatus.spatialSound?.let { _spatialSound.value = it }
            return
        }

        // Try parse 0x0204 connected devices notification (eventCode=0x06)
        val connectedDevicesResult = ConnectedDevicesParser.parse(packet)
        if (connectedDevicesResult != null) {
            Log.d(TAG, "Connected devices received: $connectedDevicesResult")
            _connectedDevices.value = connectedDevicesResult
            _connectedDevicesReceived.value = true
            return
        }

        val spatialAudioResult = SpatialAudioParser.parseModeNotify(packet)
        if (spatialAudioResult != null) {
            Log.d(TAG, "Spatial audio mode received: $spatialAudioResult")
            _spatialAudioMode.value = spatialAudioResult
            return
        }

        val spatialAudioSetStatus = SpatialAudioParser.parseSetResponseStatus(packet)
        if (spatialAudioSetStatus != null) {
            Log.d(TAG, "Spatial audio set response: $spatialAudioSetStatus")
            return
        }

        val setFeatureResult = SwitchFeatureSetParser.parse(packet)
        if (setFeatureResult != null) {
            Log.d(TAG, "Switch feature response: status=${setFeatureResult.status}, value=${setFeatureResult.value}")
            return
        }
    }

    private fun sendPacket(packet: ByteArray) {
        try {
            BtLogStore.addSend(packet, BtLogLabeler.labelSend(packet))
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send failed", e)
        }
    }

    fun setGameMode(enabled: Boolean) {
        _gameMode.value = enabled
        scope.launch { sendGameModePackets(enabled) }
    }

    fun setSpatialAudioMode(mode: Int) {
        val normalizedMode = mode.coerceIn(SpatialAudioMode.OFF, SpatialAudioMode.HEAD_TRACKING)
        _spatialAudioMode.value = normalizedMode
        scope.launch { sendPacket(profile.spatialPacket(normalizedMode)) }
    }

    fun setNoiseLevel(level: Int) {
        _noiseLevel.value = level
        scope.launch { sendPacket(profile.noiseLevelPacket(level)) }
    }

    fun setAutoPlayPause(enabled: Boolean) {
        _autoPlayPause.value = enabled
        scope.launch { sendPacket(profile.autoPlayPausePacket(enabled)) }
    }

    fun setDualDevice(enabled: Boolean) {
        _dualDevice.value = enabled
        _connectedDevicesReceived.value = false
        scope.launch { sendPacket(profile.dualDevicePacket(enabled)) }
    }

    fun setSpatialSound(enabled: Boolean) {
        _spatialSound.value = enabled
        scope.launch { sendPacket(profile.spatialSoundPacket(enabled)) }
    }

    fun setEqPreset(id: Int) {
        if (id < 0) return
        currentEqPresetId = id
        _eqPresetId.value = id
        scope.launch { sendPacket(profile.eqPacket(id)) }
    }

    private fun customEqFrequencies(): List<Int> =
        profile.customEqFrequencies.ifEmpty { EqDefaults.FREQUENCIES }

    private fun queryEqDetails() {
        if (!profile.customEqVisible) return
        scope.launch {
            sendPacket(profile.packet(ProfileKeys.QUERY_EQ))
            delay(80)
            sendPacket(profile.packet(ProfileKeys.QUERY_EQ_ALL))
        }
    }

    fun saveEqPreset(
        id: Int,
        name: String,
        frequencies: List<Int>,
        gains: List<Int>,
        minValue: Int = -6,
        maxValue: Int = 6,
    ) {
        if (!profile.customEqVisible || name.isBlank()) return
        pendingSavedEqName = name
        scope.launch {
            sendPacket(
                OppoPackets.buildSaveEqualizer(
                    id = id,
                    name = name,
                    frequencies = frequencies.ifEmpty { customEqFrequencies() },
                    gains = gains,
                    minValue = minValue,
                    maxValue = maxValue,
                )
            )
            delay(450)
            queryEqDetails()
        }
    }

    fun deleteEqPreset(entry: EqDevicePreset) {
        if (!profile.customEqVisible || entry.id <= 0) return
        pendingDeletedEqIds += entry.id
        if (currentEqPresetId == entry.id) {
            currentEqPresetId = -1
            _eqPresetId.value = -1
        }
        scope.launch {
            val packet = if (entry.frequencies.isNotEmpty() && entry.gains.isNotEmpty()) {
                OppoPackets.buildDeleteEqualizer(entry)
            } else {
                OppoPackets.buildDeleteEqualizer(entry.id)
            }
            sendPacket(packet)
            delay(450)
            queryEqDetails()
            delay(900)
            pendingDeletedEqIds.remove(entry.id)
            queryEqDetails()
        }
    }

    fun setANCMode(mode: NoiseControlMode) {
        val ancInt = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
        }
        _ancMode.value = mode
        scope.launch { sendPacket(profile.ancPacket(ancInt)) }
    }

    /**
     * Combo query strategy: send batch query (wake + game mode), then battery, then ANC.
     */
    private fun queryStatus() {
        scope.launch {
            sendPacket(profile.packet(ProfileKeys.QUERY_STATUS))
            delay(50)
            sendPacket(profile.packet(ProfileKeys.QUERY_BATTERY))
            delay(50)
            sendPacket(profile.packet(ProfileKeys.QUERY_ANC))
            if (profile.eqPresets.isNotEmpty()) {
                delay(50)
                sendPacket(profile.packet(ProfileKeys.QUERY_EQ))
            }
            if (profile.customEqVisible) {
                delay(50)
                sendPacket(profile.packet(ProfileKeys.QUERY_EQ_ALL))
            }
        }
    }

    private suspend fun sendGameModePackets(enabled: Boolean) {
        profile.gameModePackets(enabled).forEachIndexed { index, packet ->
            if (index > 0) delay(120)
            sendPacket(packet)
        }
    }

    /**
     * Public method for UI refresh button.
     */
    fun refreshStatus() {
        if (!isConnected) return
        queryStatus()
    }

    fun disconnect() {
        isConnected = false
        batteryPollJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _batteryParams.value = BatteryParams()
        _ancMode.value = NoiseControlMode.OFF
        _deviceName.value = ""
        _gameMode.value = false
        _spatialAudioMode.value = SpatialAudioMode.OFF
        _noiseLevel.value = NoiseLevel.DEEP
        _smartAncLevel.value = -1
        _autoPlayPause.value = false
        _dualDevice.value = false
        _spatialSound.value = false
        currentEqPresetId = -1
        _eqPresetId.value = -1
        _eqPresets.value = emptyList()
        _eqDevicePresets.value = emptyList()
        pendingDeletedEqIds.clear()
        pendingSavedEqName = null
        _connectedDevices.value = emptyList()
        _connectedDevicesReceived.value = false
        _identifiedModel.value = null
    }
}
