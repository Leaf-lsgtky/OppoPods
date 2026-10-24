package moe.chenxy.oppopods.pods

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * 贴合度检测与黄金听感的状态机。
 *
 * 设计要点：
 * - 只依赖一个「发包」回调，因此既能跑在应用内的 [AppRfcommController]，
 *   也能跑在 Bluetooth 进程的 [RfcommController]，两处共用同一套协议与超时。
 * - 所有命令串行执行（[commandMutex]），数据包由连接线程直接喂给 [onPacket]；
 *   UID 校验、超时和取消都在这里收口，UI 只读 [state]。
 * - 不自行切换 ANC / 空间音效 / 播放控制，避免与系统的音频焦点仲裁打架。
 */
class CalibrationEngine(
    private val sender: (ByteArray) -> Unit,
    private val capabilityProvider: () -> CalibrationCapability,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onStateChanged: (CalibrationState) -> Unit = {},
) {
    companion object {
        private const val TAG = "OppoPods-Calibration"
        private const val WEAR_TIMEOUT_MS = 2_500L
        private const val FIT_TIMEOUT_MS = 16_000L
        private const val EAR_SCAN_TIMEOUT_MS = 20_000L

        /** 等待耳扫事件（0x0204 event 14）的单个窗口。 */
        private const val EAR_EVENT_WAIT_MS = 6_000L

        /** 主动读回耳扫（0x011E）的单次超时。 */
        private const val EAR_POLL_TIMEOUT_MS = 2_000L

        /** 官方 CalibrationTimings.FilterTimeout = 5s；耳扫滤波要算两条曲线，3s 明显偏短。 */
        private const val FILTER_TIMEOUT_MS = 5_000L

        /** 官方 StartToneDelay = 500ms。 */
        private const val START_TONE_DELAY_MS = 500L
        private const val LEVEL_THROTTLE_MS = 300L
    }

    private val stateLock = Any()
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private var commandJob: Job? = null
    private var levelJob: Job? = null
    private var levelEpoch = 0

    private var wearWaiter: CompletableDeferred<CalibrationProtocol.WearState>? = null
    private var fitWaiter: CompletableDeferred<CalibrationProtocol.FitResult>? = null
    private var scanWaiter: CompletableDeferred<CalibrationProtocol.EarScanEvent>? = null

    /** 主动读回设备缓存的耳扫数据（0x011E）时使用。 */
    private var currentEarScanWaiter: CompletableDeferred<CalibrationProtocol.EarScanEvent>? = null

    /**
     * 曲线等待：耳扫机型要等 0x8116 和 0x811F 两条都回来，
     * 所以用「还差几条」计数，而不是单个布尔。
     */
    private var filterWaiter: CompletableDeferred<Unit>? = null
    private var pendingFilterReplies = 0
    private var currentHearingWaiter: CompletableDeferred<CalibrationProtocol.CurrentHearing>? = null
    private var ackRequest = 0
    private var ackWaiter: CompletableDeferred<Int>? = null

    private var active = false
    private var hearingStarted = false
    private var previewStarted = false
    private var uid = 0
    private var scanData: ByteArray? = null
    private var levels = mutableListOf<HearingPoint>()

    private var step = 0
    private var levelIndex = CalibrationState.V2_DEFAULT_LEVEL

    /** 旧版二分测听（无耳扫）的搜索区间。 */
    private var v1Lower = -60
    private var v1Upper = 20
    private var v1Current = 0

    private val version: Int
        get() = if (capabilityProvider().earScan) 2 else 1

    private fun currentStepSide(): Int = step / 6 + 1

    private fun currentStepType(): Int = step % 6 + 1

    private fun currentDecibels(): Int =
        if (version == 2) CalibrationState.V2_DECIBELS[levelIndex] else v1Current

    // ------------------------------------------------------------ 公共命令

    fun startFit() = launchCommand {
        val capability = capabilityProvider()
        if (!capability.fitDetection) {
            failState("当前型号未声明支持耳塞贴合度检测。")
            return@launchCommand
        }
        resetFor(CalibrationState.MODE_FIT)
        update { it.copy(stage = CalibrationState.STAGE_CHECKING_WEAR) }
        val wear = queryWear()
        if (wear != null && !wear.bothInEar) {
            failState("请将两只耳机都戴好后重试。")
            return@launchCommand
        }

        update { it.copy(stage = CalibrationState.STAGE_FITTING) }
        val deferred = CompletableDeferred<CalibrationProtocol.FitResult>()
        fitWaiter = deferred
        sender(CalibrationProtocol.buildFit(true))
        val startAck = awaitAck(CalibrationProtocol.CMD_FIT, 1_200L)
        if (startAck != null && startAck != 0) {
            sender(CalibrationProtocol.buildFit(false))
            if (fitWaiter === deferred) fitWaiter = null
            failState(statusMessage(startAck))
            return@launchCommand
        }
        val result = withTimeoutOrNull(FIT_TIMEOUT_MS) { deferred.await() }
        sender(CalibrationProtocol.buildFit(false))
        if (fitWaiter === deferred) fitWaiter = null
        if (result == null) {
            failState("检测超时，请调整耳塞位置后重试。")
        } else {
            active = false
            update {
                it.copy(
                    stage = CalibrationState.STAGE_FIT_RESULT,
                    fitLeft = result.left,
                    fitRight = result.right,
                    message = null,
                )
            }
        }
    }

    fun startHearing() = launchCommand {
        val capability = capabilityProvider()
        if (!capability.hearingEnhancement) {
            failState("当前型号未声明支持黄金听感。")
            return@launchCommand
        }
        resetFor(CalibrationState.MODE_HEARING)
        update {
            it.copy(
                stage = CalibrationState.STAGE_CHECKING_WEAR,
                supportsEarScan = capability.earScan,
                version = version,
            )
        }
        val wear = queryWear()
        if (wear != null && !wear.bothInEar) {
            failState("请将两只耳机都戴好后重试。")
            return@launchCommand
        }

        uid = Random.nextInt(1, Int.MAX_VALUE)
        scanData = null
        previewStarted = false
        if (capability.earScan) {
            update { it.copy(stage = CalibrationState.STAGE_SCANNING) }
            val deferred = CompletableDeferred<CalibrationProtocol.EarScanEvent>()
            scanWaiter = deferred
            sender(CalibrationProtocol.buildEarScan(true, uid))
            Log.d(TAG, "ear scan start uid=$uid")
            val scanAck = awaitAck(CalibrationProtocol.CMD_HEARING_DETECT, 1_000L)
            Log.d(TAG, "ear scan ack=$scanAck")
            if (scanAck != null && scanAck != 0) {
                if (scanWaiter === deferred) scanWaiter = null
                failState(statusMessage(scanAck))
                return@launchCommand
            }
            val scan = withTimeoutOrNull(EAR_SCAN_TIMEOUT_MS) { deferred.await() }
            if (scanWaiter === deferred) scanWaiter = null
            Log.d(
                TAG,
                "ear scan result: received=${scan != null} uid=$uid " +
                        "scanUid=${scan?.uid} scanSize=${scan?.data?.size} action=${scan?.action}",
            )
            if (scan == null || scan.uid != uid || scan.data.isEmpty()) {
                sender(CalibrationProtocol.buildEarScan(false, uid))
                failState("耳道扫描超时或数据无效，请重试。")
                return@launchCommand
            }
            scanData = scan.data
            sender(CalibrationProtocol.buildEarScan(false, uid))
            // 固件停止扫描是异步清理的，留出余量再开测听。
            delay(600)
        }
        beginHearing()
    }

    /** 旧版（无耳扫）测听回答。 */
    fun answer(heard: Boolean) = launchCommand {
        if (!isHearingAwaiting() || version != 1) return@launchCommand
        levelEpoch++
        levelJob?.cancel()
        sender(CalibrationProtocol.buildPauseHearing())
        delay(120)
        var finished = false
        var result = v1Current
        if (heard) {
            v1Upper = v1Current
            if (v1Upper - v1Lower <= 4) {
                finished = true
                result = v1Lower
            } else {
                v1Current = floor((v1Lower + v1Upper) / 2.0).toInt()
            }
        } else {
            v1Lower = v1Current
            if (v1Upper - v1Lower <= 2) {
                finished = true
                result = v1Upper
            } else {
                v1Current = ceil((v1Lower + v1Upper) / 2.0).toInt()
            }
        }
        update { it.copy(decibels = result) }
        if (finished) completePoint(result) else sendTone()
    }

    /** 滑块版测听：停止拖动后提交当前档位。 */
    fun setLevel(index: Int, released: Boolean) {
        if (!isHearingAwaiting() || version != 2) return
        val safe = index.coerceIn(0, CalibrationState.V2_DECIBELS.lastIndex)
        if (safe == levelIndex && released) return
        levelIndex = safe
        update {
            it.copy(
                levelIndex = safe,
                decibels = CalibrationState.V2_DECIBELS[safe],
                message = null,
            )
        }
        val epoch = ++levelEpoch
        levelJob?.cancel()
        levelJob = scope.launch {
            if (!released) delay(LEVEL_THROTTLE_MS)
            if (epoch != levelEpoch || !isHearingAwaiting()) return@launch
            sender(CalibrationProtocol.buildPauseHearing())
            delay(70)
            if (epoch != levelEpoch) return@launch
            sendTone()
        }
    }

    fun next() = launchCommand {
        if (!isHearingAwaiting() || version != 2) return@launchCommand
        levelEpoch++
        levelJob?.cancel()
        val db = currentDecibels()
        recordPoint(db)
        sender(CalibrationProtocol.buildPauseHearing())
        delay(140)
        step += 1
        if (step >= CalibrationState.HEARING_STEPS) {
            finishTest()
        } else {
            levelIndex = CalibrationState.V2_DEFAULT_LEVEL
            updateStep()
            delay(120)
            sendTone()
        }
    }

    fun previous() = launchCommand {
        if (!isHearingAwaiting() || step <= 0) return@launchCommand
        levelEpoch++
        levelJob?.cancel()
        sender(CalibrationProtocol.buildPauseHearing())
        delay(120)
        step -= 1
        levels = levels.take(step).toMutableList()
        resetSearch()
        updateStep()
        update { it.copy(levels = levels.toList()) }
        delay(80)
        sendTone()
    }

    fun resume() = launchCommand {
        if (!isHearingAwaiting()) return@launchCommand
        val level = updateMessage(null)
        sender(CalibrationProtocol.buildPauseHearing())
        delay(100)
        sendTone()
        if (level != null) Log.d(TAG, "resume after interruption: $level")
    }

    fun pause() {
        if (!isHearingAwaiting()) return
        levelEpoch++
        levelJob?.cancel()
        scope.launch {
            sender(CalibrationProtocol.buildPauseHearing())
            update { it.copy(message = "已暂停测听，可随时继续。") }
        }
    }

    fun save(name: String) = launchCommand {
        if (_state.value.mode != CalibrationState.MODE_HEARING) return@launchCommand
        if (_state.value.stage != CalibrationState.STAGE_RESULT) return@launchCommand
        if (levels.size != CalibrationState.HEARING_STEPS) {
            update { it.copy(message = "测听数据不完整，请重新开始检测。") }
            return@launchCommand
        }
        // 官方要求「测听完成 + 曲线就绪」才允许保存；没有 DSP 参数的方案写到耳机里不生效。
        if (!_state.value.filtersReady) {
            update { it.copy(message = "听感曲线尚未就绪，请重新检测后再保存。") }
            return@launchCommand
        }
        val trimmed = name.trim()
        val nameBytes = trimmed.toByteArray(Charsets.UTF_8)
        if (nameBytes.isEmpty() || nameBytes.size > 39) {
            update { it.copy(message = "名称需为 1–39 个字节（中文约 13 个字）。") }
            return@launchCommand
        }

        update {
            it.copy(
                stage = CalibrationState.STAGE_SAVING,
                savedName = trimmed,
                message = "正在把方案写入耳机…",
            )
        }
        sender(CalibrationProtocol.buildRestoreDescription(1))
        delay(160)
        sender(CalibrationProtocol.buildHearingParameters(3, levels, uid, trimmed))
        val saveAck = awaitAck(CalibrationProtocol.CMD_HEARING_PARAMS, 1_500L)
        if (saveAck != null && saveAck != 0) {
            update {
                it.copy(
                    stage = CalibrationState.STAGE_RESULT,
                    message = statusMessage(saveAck),
                )
            }
            return@launchCommand
        }
        delay(300)
        scanData?.let {
            sender(CalibrationProtocol.buildSaveEarScan(it, uid))
            val scanAck = awaitAck(CalibrationProtocol.CMD_EAR_SCAN_SAVE, 1_200L)
            if (scanAck != null && scanAck != 0) {
                update {
                    it.copy(
                        stage = CalibrationState.STAGE_RESULT,
                        message = statusMessage(scanAck),
                    )
                }
                return@launchCommand
            }
            delay(200)
        }
        sender(CalibrationProtocol.buildFeatureSwitch(CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT, true))
        delay(320)

        val deferred = CompletableDeferred<CalibrationProtocol.CurrentHearing>()
        currentHearingWaiter = deferred
        sender(CalibrationProtocol.buildCurrentHearingQuery())
        val current = withTimeoutOrNull(2_000L) { deferred.await() }
        if (currentHearingWaiter === deferred) currentHearingWaiter = null

        // 供 UI 写入本机历史：耳机只保留当前一份，历史列表完全由手机侧维护。
        val savedProfile = HearingProfile(
            uid = uid,
            name = trimmed,
            version = version,
            levels = levels.toList(),
            scan = scanData?.map { it.toInt() and 0xFF } ?: emptyList(),
            descriptionId = 1,
            savedAt = System.currentTimeMillis(),
        )
        if (current != null && current.uid == uid) {
            update {
                it.copy(
                    stage = CalibrationState.STAGE_DONE,
                    hearingEnabled = true,
                    currentName = current.name.ifBlank { trimmed },
                    currentUid = current.uid,
                    savedProfile = savedProfile,
                    message = "已保存并启用。",
                )
            }
        } else {
            update {
                it.copy(
                    stage = CalibrationState.STAGE_DONE,
                    hearingEnabled = true,
                    savedProfile = savedProfile,
                    message = "已发送保存与启用请求；若状态未刷新，请重新进入页面。",
                )
            }
        }
        active = false
    }

    fun setHearingEnabled(enabled: Boolean) {
        scope.launch {
            sender(CalibrationProtocol.buildFeatureSwitch(
                CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT, enabled,
            ))
            delay(220)
            sender(CalibrationProtocol.buildFeatureQuery(CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT))
            update { it.copy(hearingEnabled = enabled) }
        }
    }

    /**
     * 切换到本机历史里的某一份方案。
     *
     * 欢律的耳机端只保存「当前方案」，多份方案存在手机侧；切换时把历史里的
     * 测听数据按保存流程重新写回耳机，再打开发开关。与 [save] 的区别只是数据来源，
     * 写回后同样用 0x0115 回读确认 UID。
     */
    fun applyProfile(profile: HearingProfile) = launchCommand {
        if (profile.levels.size != CalibrationState.HEARING_STEPS) {
            update { it.copy(message = "该方案数据不完整，无法切换。") }
            return@launchCommand
        }
        resetFor(CalibrationState.MODE_HEARING)
        uid = profile.uid
        update {
            it.copy(
                stage = CalibrationState.STAGE_SAVING,
                savedName = profile.name,
                message = "正在切换到「${profile.name}」…",
            )
        }
        sender(CalibrationProtocol.buildRestoreDescription(profile.descriptionId.coerceIn(1, 20)))
        delay(160)
        sender(CalibrationProtocol.buildHearingParameters(3, profile.levels, profile.uid, profile.name))
        val ack = awaitAck(CalibrationProtocol.CMD_HEARING_PARAMS, 1_500L)
        if (ack != null && ack != 0) {
            update { it.copy(stage = CalibrationState.STAGE_IDLE, message = statusMessage(ack)) }
            active = false
            return@launchCommand
        }
        delay(300)
        if (profile.scan.isNotEmpty()) {
            sender(CalibrationProtocol.buildSaveEarScan(profile.scanBytes(), profile.uid))
            val scanAck = awaitAck(CalibrationProtocol.CMD_EAR_SCAN_SAVE, 1_200L)
            if (scanAck != null && scanAck != 0) {
                update { it.copy(stage = CalibrationState.STAGE_IDLE, message = statusMessage(scanAck)) }
                active = false
                return@launchCommand
            }
            delay(200)
        }
        sender(CalibrationProtocol.buildFeatureSwitch(CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT, true))
        delay(320)

        val deferred = CompletableDeferred<CalibrationProtocol.CurrentHearing>()
        currentHearingWaiter = deferred
        sender(CalibrationProtocol.buildCurrentHearingQuery())
        val current = withTimeoutOrNull(2_000L) { deferred.await() }
        if (currentHearingWaiter === deferred) currentHearingWaiter = null
        val applied = current != null && current.uid == profile.uid
        update {
            it.copy(
                stage = CalibrationState.STAGE_IDLE,
                hearingEnabled = true,
                currentName = current?.name?.takeIf { name -> name.isNotBlank() } ?: profile.name,
                currentUid = current?.uid ?: profile.uid,
                message = if (applied) null else "已发送切换请求；若状态未刷新，请重新进入页面。",
            )
        }
        active = false
    }

    /** 结果页试听：临时应用本次参数，用户播放自己的音乐。 */
    fun setPreview(enabled: Boolean) {
        scope.launch {
            if (enabled) {
                if (levels.size != CalibrationState.HEARING_STEPS) return@launch
                sender(CalibrationProtocol.buildHearingParameters(1, levels, uid))
                // 耳扫机型试听同样要下发耳扫参数（action 1），否则只有测听曲线参与。
                scanData?.takeIf { it.isNotEmpty() }?.let {
                    delay(80)
                    sender(CalibrationProtocol.buildEarScanParameters(1, it, uid))
                }
                previewStarted = true
            } else {
                sender(CalibrationProtocol.buildStopPreview())
                scanData?.takeIf { it.isNotEmpty() }?.let { scan ->
                    delay(80)
                    sender(CalibrationProtocol.buildEarScanParameters(2, scan, uid))
                }
                previewStarted = false
            }
        }
    }

    fun refresh() {
        scope.launch {
            sender(CalibrationProtocol.buildCurrentHearingQuery())
            delay(120)
            sender(CalibrationProtocol.buildFeatureQuery(CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT))
        }
    }

    fun cancel() {
        commandJob?.cancel()
        commandJob = null
        levelEpoch++
        levelJob?.cancel()
        val mode = _state.value.mode
        val wasHearing = hearingStarted
        val wasScan = scanWaiter?.isActive == true
        active = false
        hearingStarted = false
        ackRequest = 0
        ackWaiter = null
        wearWaiter = null
        fitWaiter = null
        scanWaiter = null
        filterWaiter = null
        currentHearingWaiter = null
        scope.launch {
            runCatching {
                when {
                    mode == CalibrationState.MODE_FIT -> sender(CalibrationProtocol.buildFit(false))
                    mode == CalibrationState.MODE_HEARING -> {
                        if (wasScan) sender(CalibrationProtocol.buildEarScan(false, uid))
                        if (wasHearing) sender(CalibrationProtocol.buildHearingDetection(false))
                        if (wasHearing) sender(CalibrationProtocol.buildPauseHearing())
                        if (previewStarted) {
                            sender(CalibrationProtocol.buildStopPreview())
                            previewStarted = false
                        }
                    }
                }
            }
            update {
                CalibrationState(
                    hearingEnabled = it.hearingEnabled,
                    currentName = it.currentName,
                    currentUid = it.currentUid,
                )
            }
        }
    }

    fun onPacket(packet: ByteArray) {
        runCatching {
            logPacket(packet)
            completeAck(packet)
            CalibrationProtocol.parseFeatureSwitch(
                packet, CalibrationProtocol.FEATURE_HEARING_ENHANCEMENT,
            )?.let { enabled ->
                if (_state.value.hearingEnabled != enabled) {
                    update { it.copy(hearingEnabled = enabled) }
                }
            }
            CalibrationProtocol.parseCurrentHearing(packet)?.let { current ->
                val restoredProfile = if (current.uid != 0 && current.levels.size == 12) {
                    HearingProfile(
                        uid = current.uid,
                        name = current.name,
                        version = 2,
                        levels = current.levels,
                        savedAt = System.currentTimeMillis(),
                    )
                } else null
                update {
                    it.copy(
                        currentName = current.name,
                        currentUid = current.uid,
                        levels = if (current.levels.isNotEmpty()) current.levels else it.levels,
                        hearingEnabled = if (current.uid == 0) false else it.hearingEnabled,
                        savedProfile = restoredProfile ?: it.savedProfile,
                    )
                }
                currentHearingWaiter?.complete(current)
            }
            CalibrationProtocol.parseWear(packet)?.let { wear -> wearWaiter?.complete(wear) }
            CalibrationProtocol.parseWearEvent(packet)?.let { wear -> wearWaiter?.complete(wear) }
            CalibrationProtocol.parseFit(packet)?.let { fit -> fitWaiter?.complete(fit) }
            CalibrationProtocol.parseEarScanEvent(packet)?.let { event ->
                if (active && _state.value.stage == CalibrationState.STAGE_SCANNING) {
                    scanWaiter?.complete(event)
                }
            }
            CalibrationProtocol.parseCurrentEarScan(packet)?.let { event ->
                currentEarScanWaiter?.complete(event)
            }
            CalibrationProtocol.parseFilterUid(packet)?.let { onFilterReply(it) }
            CalibrationProtocol.parseEarScanFilterUid(packet)?.let { onFilterReply(it) }
            CalibrationProtocol.parseHearingEvent(packet)?.let { handleHearingEvent(it) }
        }.onFailure { Log.w(TAG, "failed to handle packet", it) }
    }

    fun onDisconnected() {
        commandJob?.cancel()
        levelJob?.cancel()
        active = false
        hearingStarted = false
        update {
            it.copy(
                stage = if (it.isBusy) CalibrationState.STAGE_ERROR else it.stage,
                message = if (it.isBusy) "检测过程中耳机连接已断开。" else it.message,
            )
        }
    }

    fun dispose() {
        commandJob?.cancel()
        levelJob?.cancel()
        scope.cancel()
    }

    // ------------------------------------------------------------ 内部流程

    private fun launchCommand(block: suspend () -> Unit) {
        if (commandJob?.isActive == true) {
            Log.d(TAG, "ignore command while another is still running")
            return
        }
        commandJob = scope.launch {
            commandMutex.withLock {
                try {
                    block()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.e(TAG, "calibration command failed", error)
                    failState(error.message ?: "操作失败，请重试。")
                }
            }
        }
    }

    private suspend fun beginHearing() {
        levels = mutableListOf()
        step = 0
        levelIndex = CalibrationState.V2_DEFAULT_LEVEL
        resetSearch()
        sender(CalibrationProtocol.buildHearingDetection(true))
        hearingStarted = true
        val startAck = awaitAck(CalibrationProtocol.CMD_HEARING_DETECT, 1_200L)
        if (startAck != null && startAck != 0) {
            hearingStarted = false
            failState(statusMessage(startAck))
            return
        }
        delay(320)
        update {
            it.copy(
                stage = CalibrationState.STAGE_HEARING,
                version = version,
                step = 0,
                side = 1,
                frequencyType = 1,
                levelIndex = CalibrationState.V2_DEFAULT_LEVEL,
                decibels = currentDecibels(),
                levels = emptyList(),
                message = null,
            )
        }
        sender(CalibrationProtocol.buildPauseHearing())
        delay(100)
        sendTone()
    }

    private suspend fun queryWear(): CalibrationProtocol.WearState? {
        val deferred = CompletableDeferred<CalibrationProtocol.WearState>()
        wearWaiter = deferred
        sender(CalibrationProtocol.buildWearQuery())
        val result = withTimeoutOrNull(WEAR_TIMEOUT_MS) { deferred.await() }
        if (wearWaiter === deferred) wearWaiter = null
        return result
    }

    /** 等待设置命令的 ACK；超时返回 null（部分固件不回 ACK，不能因此判失败）。 */    private suspend fun awaitAck(request: Int, timeoutMs: Long): Int? {
        val deferred = CompletableDeferred<Int>()
        ackRequest = request
        ackWaiter = deferred
        val status = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (ackWaiter === deferred) ackWaiter = null
        ackRequest = 0
        return status
    }

    private fun completeAck(packet: ByteArray) {
        val request = ackRequest
        if (request == 0) return
        CalibrationProtocol.parseSetAck(packet, request)?.let { ackWaiter?.complete(it) }
    }

    /**
     * 收到一条滤波结果（0x8116 或 0x811F）。
     *
     * 只按 UID 关联；两条都到齐才唤醒等待者——耳扫机型少一条就说明曲线不完整，
     * 此时保存下去耳机端不会生效。
     */
    private fun onFilterReply(replyUid: Int) {
        if (replyUid != uid) return
        if (pendingFilterReplies <= 0) return
        pendingFilterReplies -= 1
        if (pendingFilterReplies == 0) filterWaiter?.complete(Unit)
    }

    /** 标定期间把收发帧打到 logcat，便于用真实抓包定位固件行为差异。 */
    private fun logPacket(packet: ByteArray) {
        if (!active) return
        val command = CalibrationProtocol.commandOf(packet)
        val payload = CalibrationProtocol.payloadOf(packet) ?: return
        val shown = payload.take(32).joinToString(" ") { "%02x".format(it) }
        val suffix = if (payload.size > 32) " …(${payload.size}B)" else " (${payload.size}B)"
        Log.d(TAG, "rx cmd=0x%04x%s".format(command, " $shown$suffix"))
    }

    private fun statusMessage(status: Int): String = when (status) {
        9 -> "通话中，暂时无法开始检测。"
        10 -> "请先连接两只耳机。"
        11 -> "请将两只耳机戴好后再试。"
        15 -> "耳机正忙，请稍后重试。"
        16 -> "耳机电量过低，请充电后再试。"
        else -> "耳机拒绝了请求（状态 $status）。"
    }

    private fun sendTone() {
        val point = HearingPoint(currentStepSide(), currentStepType(), currentDecibels())
        sender(CalibrationProtocol.buildFrequency(point.side, point.type, point.db))
        update { it.copy(decibels = point.db, message = null) }
    }

    private suspend fun completePoint(db: Int) {
        recordPoint(db)
        sender(CalibrationProtocol.buildPauseHearing())
        delay(140)
        step += 1
        if (step >= CalibrationState.HEARING_STEPS) {
            finishTest()
        } else {
            resetSearch()
            updateStep()
            delay(120)
            sendTone()
        }
    }

    private fun recordPoint(db: Int) {
        val point = HearingPoint(currentStepSide(), currentStepType(), db)
        if (levels.size > step) levels[step] = point else levels += point
        update { it.copy(levels = levels.toList()) }
    }

    private suspend fun finishTest() {
        sender(CalibrationProtocol.buildPauseHearing())
        update { it.copy(stage = CalibrationState.STAGE_PROCESSING, message = "正在生成专属听感…") }
        if (levels.size != CalibrationState.HEARING_STEPS) {
            failState("测听数据不完整，请重新开始检测。")
            return
        }

        // 官方在取曲线前先结束测听会话（0x040D [02,00]）——不停止的话固件仍把
        // 0x0116 当作「测听进行中」而拒绝/不返回结果，表现就是测完之后失败。
        if (hearingStarted) {
            sender(CalibrationProtocol.buildHearingDetection(false))
            hearingStarted = false
        }
        delay(START_TONE_DELAY_MS)

        // 耳扫机型要同时请求主观测听曲线和耳道扫描曲线，两条都到齐才算完整。
        val scan = scanData
        val expectScan = capabilityProvider().earScan && scan != null && scan.isNotEmpty()
        pendingFilterReplies = if (expectScan) 2 else 1
        val deferred = CompletableDeferred<Unit>()
        filterWaiter = deferred
        sender(CalibrationProtocol.buildFilterRequest(levels, uid))
        if (expectScan) {
            delay(120)
            sender(CalibrationProtocol.buildEarScanFilterRequest(scan!!, uid))
        }
        val ready = withTimeoutOrNull(FILTER_TIMEOUT_MS) { deferred.await() } != null
        if (filterWaiter === deferred) filterWaiter = null
        pendingFilterReplies = 0
        if (!ready) {
            failState("未能取回完整的听感曲线，请确认耳道扫描有效后重新检测。")
            return
        }
        update {
            it.copy(
                stage = CalibrationState.STAGE_RESULT,
                filtersReady = true,
                canSave = true,
                message = null,
            )
        }
    }

    private fun updateStep() {
        update {
            it.copy(
                step = step,
                side = currentStepSide(),
                frequencyType = currentStepType(),
                levelIndex = levelIndex,
                decibels = currentDecibels(),
                message = null,
            )
        }
    }

    private fun resetSearch() {
        v1Lower = -60
        v1Upper = 20
        v1Current = 0
    }

    private fun resetFor(mode: String) {
        active = true
        hearingStarted = false
        previewStarted = false
        uid = 0
        scanData = null
        levels = mutableListOf()
        step = 0
        levelIndex = CalibrationState.V2_DEFAULT_LEVEL
        resetSearch()
        ackRequest = 0
        ackWaiter = null
        wearWaiter = null
        fitWaiter = null
        scanWaiter = null
        filterWaiter = null
        currentHearingWaiter = null
        update {
            CalibrationState(
                mode = mode,
                stage = CalibrationState.STAGE_IDLE,
                supportsEarScan = capabilityProvider().earScan,
                version = version,
                hearingEnabled = it.hearingEnabled,
                currentName = it.currentName,
                currentUid = it.currentUid,
            )
        }
    }

    private fun handleHearingEvent(event: CalibrationProtocol.HearingEvent) {
        if (!active || !hearingStarted) return
        // 只处理主观测听（type 2）；耳扫的 type 4 状态由扫描超时/结果路径负责。
        if (event.detectionType != 2) return
        when (event.status) {
            0 -> Unit
            8, 9 -> update { it.copy(message = "提示音音量较高，请适当调低音量。") }
            in 1..6 -> {
                sender(CalibrationProtocol.buildPauseHearing())
                val message = when (event.status) {
                    1, 2 -> "音频输出或通话打断了测听，请恢复后继续。"
                    3, 4 -> "检测到通话，测听已暂停，请结束通话后继续。"
                    else -> "耳机佩戴状态发生变化，请戴好后继续。"
                }
                update { it.copy(message = message) }
            }
            else -> update { it.copy(message = "耳机已结束本次检测，请重新开始。") }
        }
    }

    private fun isHearingAwaiting(): Boolean =
        active &&
                _state.value.mode == CalibrationState.MODE_HEARING &&
                _state.value.stage == CalibrationState.STAGE_HEARING

    private fun failState(message: String) {
        active = false
        hearingStarted = false
        update { it.copy(stage = CalibrationState.STAGE_ERROR, message = message) }
    }

    /** 返回更新前的 message，便于调用方记录。 */
    private fun updateMessage(message: String?): String? {
        var previous: String? = null
        update {
            previous = it.message
            it.copy(message = message)
        }
        return previous
    }

    private fun update(transform: (CalibrationState) -> CalibrationState) {
        val next: CalibrationState
        synchronized(stateLock) {
            val current = _state.value
            next = transform(current)
            if (next == current) return
            _state.value = next
        }
        onStateChanged(next)
    }
}
