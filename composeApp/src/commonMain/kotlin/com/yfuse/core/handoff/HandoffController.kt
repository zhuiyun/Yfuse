package com.yfuse.core.handoff

import com.yfuse.watch.protocol.HandoffDevice
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffRequest
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class HandoffUiState(
    val online: Boolean = false,
    val devices: List<HandoffDevice> = emptyList(),
    val incoming: HandoffRequest? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val connectionError: String? = null,
)

/** One instance per application; [owner] must change on sign-out or account/profile switches. */
class HandoffController(
    private val api: HandoffApi,
    private val cipher: HandoffPayloadCipher,
    private val playback: HandoffPlaybackBridge,
    private val owner: StateFlow<String?>,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val platform: String,
    private val canReceive: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(HandoffUiState())
    val state: StateFlow<HandoffUiState> = _state.asStateFlow()
    private val requests = MutableStateFlow<List<HandoffRequest>>(emptyList())
    private var sessionId: String? = null
    private var clockOffset = 0L
    private var lifetime: Job? = null
    private var activeScope: CoroutineScope? = null
    private var transfer: Job? = null

    fun start() {
        if (lifetime != null) return
        lifetime =
            scope.launch {
                owner.collectLatest { identity ->
                    _state.value = HandoffUiState()
                    requests.value = emptyList()
                    sessionId = null
                    if (identity == null) return@collectLatest
                    coroutineScope {
                        activeScope = this
                        try {
                            while (true) {
                                try {
                                    val inbox =
                                        api.heartbeat(
                                            HandoffHeartbeat(deviceName.take(80), platform.take(32), canReceive()),
                                        )
                                    clockOffset = inbox.serverTimeEpochMs - now()
                                    sessionId = inbox.currentSessionId
                                    requests.value = inbox.requests
                                    _state.update { current ->
                                        current.copy(
                                            online = true,
                                            connectionError = null,
                                            devices = inbox.devices.filter { it.canReceive },
                                            incoming =
                                                inbox.requests.firstOrNull {
                                                    it.targetSessionId == sessionId &&
                                                        it.status == HandoffStatus.Requested
                                                },
                                        )
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    _state.update {
                                        it.copy(
                                            online = false,
                                            devices = emptyList(),
                                            incoming = null,
                                            connectionError = "无法连接设备接力服务，请稍后重试",
                                        )
                                    }
                                }
                                delay(
                                    if (_state.value.busy ||
                                        requests.value.any { !it.status.terminal }
                                    ) {
                                        2_000
                                    } else {
                                        10_000
                                    },
                                )
                            }
                        } finally {
                            activeScope = null
                            transfer?.cancel()
                            transfer = null
                        }
                    }
                }
            }
    }

    fun close() {
        lifetime?.cancel()
        lifetime = null
    }

    fun send(targetSessionId: String) {
        val currentOwner = owner.value ?: return
        if (_state.value.busy) return
        val media =
            playback.snapshot() ?: run {
                _state.update { it.copy(error = "请先打开要接力的影片") }
                return
            }
        val transferScope = activeScope ?: return
        _state.update { it.copy(busy = true, error = null, message = "等待另一台设备确认") }
        transfer =
            transferScope.launch {
                val id = UUID.randomUUID().toString()
                var paused = false
                var completed = false
                try {
                    val offered = api.offer(HandoffOffer(id, targetSessionId, cipher.encrypt(id, media)))
                    withTimeout(remaining(offered)) {
                        awaitStatus(id, HandoffStatus.Ready)
                        check(owner.value == currentOwner)
                        // The latest position is captured only after the receiving player is ready.
                        paused = true
                        val finalMedia = playback.pauseAndSnapshot() ?: error("来源影片已关闭")
                        check(
                            finalMedia.mediaKey == media.mediaKey &&
                                finalMedia.itemId == media.itemId &&
                                finalMedia.serverId == media.serverId &&
                                finalMedia.mediaSourceId == media.mediaSourceId,
                        ) {
                            "来源影片已经改变，接力已取消"
                        }
                        api.transition(id, HandoffTransition(HandoffStatus.Committed, cipher.encrypt(id, finalMedia)))
                        awaitStatus(id, HandoffStatus.Completed)
                        completed = true
                        _state.update { it.copy(message = "另一台设备已接续播放", error = null) }
                    }
                } catch (cancelled: CancellationException) {
                    _state.update { it.copy(message = null, error = "接力已取消或超时") }
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(message = null, error = "接力未完成，来源播放已保留") }
                } finally {
                    try {
                        if (!completed) {
                            withContext(NonCancellable) {
                                // A lost completion reply must not restart a source after the server accepted the transfer.
                                try {
                                    withTimeout(3_000) {
                                        completed =
                                            api.inbox().requests.any {
                                                it.id == id &&
                                                    it.status == HandoffStatus.Completed
                                            }
                                        if (!completed) api.transition(id, HandoffTransition(HandoffStatus.Cancelled))
                                    }
                                } catch (_: Exception) {
                                }
                                if (!completed && paused && owner.value == currentOwner) {
                                    runCatching { playback.resumeSource() }.onFailure {
                                        _state.update { state -> state.copy(error = "来源播放器未能恢复，请手动继续") }
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { playback.finishTransfer() }
                        _state.update { it.copy(busy = false) }
                    }
                }
            }
    }

    fun accept(request: HandoffRequest) {
        if (_state.value.busy || request.targetSessionId != sessionId) return
        val transferScope = activeScope ?: return
        _state.update { it.copy(busy = true, incoming = null, error = null, message = "正在准备接收影片") }
        transfer =
            transferScope.launch {
                var completed = false
                try {
                    withTimeout(remaining(request)) {
                        api.transition(request.id, HandoffTransition(HandoffStatus.Preparing))
                        val media = cipher.decrypt(request.id, request.payload)
                        check(playback.prepare(media)) { "接收设备无法准备该影片" }
                        api.transition(request.id, HandoffTransition(HandoffStatus.Ready))
                        val committed = awaitStatus(request.id, HandoffStatus.Committed)
                        val finalMedia = cipher.decrypt(request.id, committed.payload)
                        check(
                            finalMedia.mediaKey == media.mediaKey &&
                                finalMedia.serverId == media.serverId &&
                                finalMedia.itemId == media.itemId &&
                                finalMedia.mediaSourceId == media.mediaSourceId,
                        )
                        check(remaining(committed) > 3_000)
                        check(playback.startPrepared(finalMedia)) { "接收播放失败" }
                        completeWithConfirmation(request.id)
                        completed = true
                        _state.update { it.copy(message = "已接收影片", error = null) }
                    }
                } catch (cancelled: CancellationException) {
                    _state.update { it.copy(message = null, error = "接力已取消或超时") }
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(message = null, error = "无法接收影片，来源设备会继续播放") }
                } finally {
                    try {
                        if (!completed) {
                            withContext(NonCancellable) {
                                // Stop prepared/started output before asking the source to resume.
                                try {
                                    runCatching { playback.releasePrepared() }.onFailure {
                                        _state.update { state -> state.copy(error = "接收播放器未能关闭，请手动关闭") }
                                    }
                                } finally {
                                    try {
                                        withTimeout(
                                            3_000,
                                        ) { api.transition(request.id, HandoffTransition(HandoffStatus.Failed)) }
                                    } catch (
                                        _: Exception,
                                    ) {
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { playback.finishTransfer() }
                        _state.update { it.copy(busy = false) }
                    }
                }
            }
    }

    fun reject(request: HandoffRequest) {
        activeScope?.launch {
            try {
                api.transition(request.id, HandoffTransition(HandoffStatus.Rejected))
                _state.update { it.copy(incoming = null) }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "未能拒绝请求，请重试") }
            }
        }
    }

    fun cancelTransfer() {
        transfer?.cancel()
    }

    private fun remaining(request: HandoffRequest): Long =
        (request.expiresAtEpochMs - (now() + clockOffset)).coerceAtLeast(1)

    private suspend fun completeWithConfirmation(id: String) {
        try {
            api.transition(id, HandoffTransition(HandoffStatus.Completed))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Completed is idempotent. Confirm ambiguous responses before rolling back a working receiver.
            if (api.inbox().requests.none { it.id == id && it.status == HandoffStatus.Completed }) {
                api.transition(id, HandoffTransition(HandoffStatus.Completed))
            }
        }
    }

    private suspend fun awaitStatus(
        id: String,
        desired: HandoffStatus,
    ): HandoffRequest {
        val request =
            requests.first { list -> list.any { it.id == id && (it.status == desired || it.status.terminal) } }.first {
                it.id ==
                    id
            }
        check(request.status == desired) { "接力被拒绝、取消或已超时" }
        return request
    }
}
