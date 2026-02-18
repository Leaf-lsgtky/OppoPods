package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
 * Does not depend on Xposed / YukiHookAPI.
 */
@SuppressLint("MissingPermission")
class AppRfcommController {
    companion object {
        private const val TAG = "OppoPods-AppRfcomm"
        private const val RFCOMM_CHANNEL = 15
        private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    private var socket: BluetoothSocket? = null
    private var isConnected = false
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

    @SuppressLint("DiscouragedPrivateApi")
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
    }

    fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING) return

        _deviceName.value = device.name ?: device.address
        _connectionState.value = ConnectionState.CONNECTING

        scope.launch {
            try {
                delay(300)
                socket = createRfcommSocket(device)
                socket!!.connect()
                Log.d(TAG, "RFCOMM connected to ${device.name}")
                isConnected = true
                _connectionState.value = ConnectionState.CONNECTED

                startPacketReader(socket!!.inputStream)

                delay(300)
                queryStatus()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                _connectionState.value = ConnectionState.ERROR
                isConnected = false
            }
        }

        batteryPollJob = scope.launch {
            delay(2000)
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) queryStatus()
            }
        }
    }

    private fun startPacketReader(inputStream: InputStream) {
        scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        handlePacket(buffer.copyOfRange(0, bytesRead))
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

        val ancResult = AncModeParser.parse(packet)
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: $ancResult")
            _ancMode.value = ancResult
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
        val packet = if (enabled) Enums.GAME_MODE_ON else Enums.GAME_MODE_OFF
        scope.launch { sendPacket(packet) }
    }

    fun setANCMode(mode: NoiseControlMode) {
        val packet = when (mode) {
            NoiseControlMode.OFF -> Enums.ANC_OFF
            NoiseControlMode.NOISE_CANCELLATION -> Enums.ANC_NOISE_CANCEL
            NoiseControlMode.TRANSPARENCY -> Enums.ANC_TRANSPARENCY
        }
        _ancMode.value = mode
        scope.launch { sendPacket(packet) }
    }

    /**
     * Combo query strategy: send battery query (wake), wait 50ms, then mode query.
     */
    private fun queryStatus() {
        scope.launch {
            sendPacket(Enums.QUERY_BATTERY)
            delay(50)
            sendPacket(Enums.QUERY_ANC)
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
    }
}
