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
    private var lastGameModeStatusUpdateMs = 0L
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

    fun setProfile(profile: DeviceProfile) {
        this.profile = profile
    }

    fun connect(
        device: BluetoothDevice,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID,
        profile: DeviceProfile
    ) {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        this.profile = profile
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
            return
        }

        val ancResult = AncModeParser.parse(packet)
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: $ancResult")
            _ancMode.value = ancResult
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
    }
}
