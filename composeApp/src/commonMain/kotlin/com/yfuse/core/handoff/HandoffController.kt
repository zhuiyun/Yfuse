package com.yfuse.core.handoff

import com.yfuse.core.logging.AppLog
import com.yfuse.watch.protocol.HandoffDevice
import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffInbox
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffPull
import com.yfuse.watch.protocol.HandoffRequest
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class HandoffUiState(
    val online: Boolean = false,
    val devices: List<HandoffDevice> = emptyList(),
    val incoming: HandoffRequest? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val connectionError: String? = null,
    val signedIn: Boolean = false,
    /**
     * Why this device could not take over a transfer. The receiving player has closed by then, so
     * [error] alone - shown only on the 设备接力 page - left the viewer with no answer at all.
     */
    val receiveFailure: String? = null,
    /** Other devices of this account playing something this one could continue — 在此继续. */
    val playingElsewhere: List<HandoffElsewhere> = emptyList(),
    /** What this device has asked to continue, until the transfer it asked for has run its course. */
    val continuing: HandoffElsewhere? = null,
    /** Whether this device told the service it can take a transfer: foreground, idle, a player to open. */
    val canReceive: Boolean = false,
) {
    val connectionLabel: String
        get() =
            when {
                !signedIn -> "请先登录鱼服账号"
                online -> "已连接"
                connectionError != null -> "账号已登录，接力服务未连接"
                else -> "账号已登录，正在连接接力服务"
            }
}

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
    /** Where the vault's key is read to seal and open 正在播放 cards: never the main thread. */
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    /** 在此继续 on its way: sent with every heartbeat until the source offers, or gives up. */
    private var pull: PendingPull? = null

    /** Pulls this device has answered with an offer, so a slow heartbeat never answers twice. */
    private val answeredPulls = ArrayDeque<String>()

    /** 正在播放 cards already opened, by envelope: a device resends the same one between changes. */
    private val opened = mutableMapOf<HandoffEnvelope, HandoffMedia>()

    /** Cuts the wait for the next heartbeat short, so 在此继续 is sent at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        if (lifetime != null) return
        lifetime =
            scope.launch {
                owner.collectLatest { identity ->
                    _state.value = HandoffUiState(signedIn = identity != null)
                    requests.value = emptyList()
                    sessionId = null
                    pull = null
                    answeredPulls.clear()
                    opened.clear()
                    if (identity == null) return@collectLatest
                    coroutineScope {
                        activeScope = this
                        try {
                            while (true) {
                                try {
                                    val receiving = canReceive()
                                    val asking = pull
                                    val inbox =
                                        api.heartbeat(
                                            HandoffHeartbeat(
                                                deviceName.take(80),
                                                platform.take(32),
                                                receiving,
                                                nowPlaying = sealNowPlaying(),
                                                pull = asking?.let { HandoffPull(it.target.sessionId, it.id) },
                                            ),
                                        )
                                    clockOffset = inbox.serverTimeEpochMs - now()
                                    sessionId = inbox.currentSessionId
                                    requests.value = inbox.requests
                                    val elsewhere = openNowPlaying(inbox)
                                    val requested =
                                        inbox.requests.firstOrNull {
                                            it.targetSessionId == sessionId &&
                                                it.status == HandoffStatus.Requested
                                        }
                                    // 在此继续 asked for exactly this transfer: it is taken without asking again.
                                    val answer = requested?.takeIf { it.sourceSessionId == asking?.target?.sessionId }
                                    _state.update { current ->
                                        current.copy(
                                            online = true,
                                            connectionError = null,
                                            devices = inbox.devices.filter { it.canReceive },
                                            incoming = requested?.takeIf { answer == null },
                                            playingElsewhere = elsewhere,
                                            canReceive = receiving,
                                        )
                                    }
                                    if (answer != null) {
                                        pull = null
                                        accept(answer)
                                    } else if (asking != null) {
                                        abandonUnansweredPull(asking, elsewhere)
                                    }
                                    answerPull(inbox)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    _state.update {
                                        it.copy(
                                            online = false,
                                            devices = emptyList(),
                                            incoming = null,
                                            connectionError =
                                                (error as? HandoffApiException)?.message
                                                    ?: "无法连接设备接力服务，请检查网络后重试",
                                        )
                                    }
                                }
                                val interval =
                                    if (_state.value.busy ||
                                        pull != null ||
                                        requests.value.any { !it.status.terminal }
                                    ) {
                                        2_000L
                                    } else {
                                        10_000L
                                    }
                                withTimeoutOrNull(interval) { wake.receive() }
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
                val startedAt = now()
                var paused = false
                var completed = false
                try {
                    // The longest the service allows: the receiving viewer has to confirm, and the
                    // receiving player then has to load the title over whatever link it has.
                    val offered =
                        api.offer(
                            HandoffOffer(
                                id,
                                targetSessionId,
                                cipher.encrypt(id, media),
                                lifetimeSeconds = HANDOFF_OFFER_LIFETIME_SECONDS,
                            ),
                        )
                    log("offer_sent", id, startedAt)
                    withTimeout(remaining(offered)) {
                        awaitStatus(id, HandoffStatus.Ready)
                        check(owner.value == currentOwner)
                        // The latest position is captured only after the receiving player is ready.
                        paused = true
                        log("source_paused", id, startedAt)
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
                        log("completed", id, startedAt)
                        _state.update { it.copy(message = "另一台设备已接续播放", error = null) }
                    }
                } catch (cancelled: CancellationException) {
                    log("failed", id, startedAt, "paused" to paused.toString(), failure = cancelled)
                    _state.update { it.copy(message = null, error = "接力已取消或超时") }
                    throw cancelled
                } catch (failure: Exception) {
                    log("failed", id, startedAt, "paused" to paused.toString(), failure = failure)
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
                val startedAt = now()
                var completed = false
                log("accepted", request.id, startedAt, "remainingMs" to remaining(request).toString())
                try {
                    withTimeout(remaining(request)) {
                        api.transition(request.id, HandoffTransition(HandoffStatus.Preparing))
                        val media = cipher.decrypt(request.id, request.payload)
                        check(playback.prepare(media)) { "接收设备无法准备该影片" }
                        log("prepared", request.id, startedAt)
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
                        log("committed", request.id, startedAt, "remainingMs" to remaining(committed).toString())
                        check(playback.startPrepared(finalMedia)) { "接收播放失败" }
                        completeWithConfirmation(request.id)
                        completed = true
                        log("completed", request.id, startedAt)
                        _state.update { it.copy(message = "已接收影片", error = null) }
                    }
                } catch (cancelled: CancellationException) {
                    // Read before the finally block releases the receiver that knows the reason.
                    val reason = playback.receiveFailureReason()
                    log("failed", request.id, startedAt, "reason" to reason.orEmpty(), failure = cancelled)
                    val error =
                        when {
                            reason != null -> "$reason，来源设备会继续播放"
                            cancelled is TimeoutCancellationException -> "接力超时，来源设备会继续播放"
                            else -> null
                        }
                    // A transfer the viewer cancelled needs no explanation.
                    _state.update {
                        it.copy(message = null, error = error ?: "接力已取消或超时", receiveFailure = error)
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = playback.receiveFailureReason()
                    log("failed", request.id, startedAt, "reason" to reason.orEmpty(), failure = failure)
                    val error = "${reason ?: "无法接收影片"}，来源设备会继续播放"
                    _state.update { it.copy(message = null, error = error, receiveFailure = error) }
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
                        _state.update { it.copy(busy = false, continuing = null) }
                    }
                }
            }
    }

    /**
     * 在此继续: asks [target] to hand over what it is playing. It answers with an ordinary transfer,
     * which this device takes without asking again — and the source still pauses only once this
     * device is ready. Gives up after [HANDOFF_PULL_TIMEOUT_MS] without an answer.
     */
    fun continueHere(target: HandoffElsewhere) {
        if (_state.value.busy || pull != null || activeScope == null) return
        pull = PendingPull(target, UUID.randomUUID().toString(), now())
        _state.update { it.copy(continuing = target, error = null, message = "正在请求${target.deviceName}接力") }
        wake.trySend(Unit)
    }

    /** Withdraws 在此继续, or stops the transfer it already started. */
    fun cancelContinue() {
        if (pull == null) {
            if (_state.value.continuing != null) cancelTransfer()
            return
        }
        pull = null
        _state.update { it.copy(continuing = null, message = null) }
        wake.trySend(Unit)
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

    fun dismissReceiveFailure() {
        _state.update { it.copy(receiveFailure = null) }
    }

    /** What this device is playing, sealed for the account's other devices; null when idle or unsealable. */
    private suspend fun sealNowPlaying(): HandoffEnvelope? {
        val session = sessionId ?: return null
        val media = playback.nowPlaying()?.forNowPlaying() ?: return null
        return try {
            withContext(cryptoDispatcher) { cipher.encrypt(nowPlayingId(session), media) }
                .takeIf { it.ciphertext.length <= HANDOFF_NOW_PLAYING_MAX_CHARS }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** The account's other devices that are playing, opened; a card that cannot be opened is skipped. */
    private suspend fun openNowPlaying(inbox: HandoffInbox): List<HandoffElsewhere> {
        val sealed = inbox.devices.mapNotNull { device -> device.nowPlaying?.let { device to it } }
        opened.keys.retainAll(sealed.mapTo(HashSet()) { it.second })
        return sealed.mapNotNull { (device, envelope) ->
            val media =
                opened[envelope] ?: try {
                    withContext(cryptoDispatcher) { cipher.decrypt(nowPlayingId(device.sessionId), envelope) }
                        .also { opened[envelope] = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
            // It is playing: it has moved on since the heartbeat that carried the card.
            val elapsed = (inbox.serverTimeEpochMs - device.lastSeenAtEpochMs).coerceAtLeast(0L)
            HandoffElsewhere(
                sessionId = device.sessionId,
                deviceName = device.name,
                title = media.title,
                positionMs =
                    (media.positionMs + elapsed).let {
                        if (media.durationMs > 0L) it.coerceAtMost(media.durationMs) else it
                    },
                durationMs = media.durationMs,
                mediaKey = media.mediaKey,
                profileId = media.profileId,
            )
        }
    }

    /** A source that stopped playing, or never answered, is not waited on any longer. */
    private fun abandonUnansweredPull(
        asking: PendingPull,
        elsewhere: List<HandoffElsewhere>,
    ) {
        val stopped = elsewhere.none { it.sessionId == asking.target.sessionId }
        if (!stopped && now() - asking.startedAt < HANDOFF_PULL_TIMEOUT_MS) return
        pull = null
        val error =
            if (stopped) {
                "${asking.target.deviceName}已停止播放"
            } else {
                "${asking.target.deviceName}没有回应，可以在那台设备上发起接力"
            }
        // The viewer asked for this on this device, so the answer is told here, not only on 设备接力.
        _state.update { it.copy(continuing = null, message = null, error = error, receiveFailure = error) }
    }

    /** Another device asked for what this one is playing: it gets an ordinary transfer. */
    private fun answerPull(inbox: HandoffInbox) {
        val asking =
            inbox.devices.firstOrNull { device ->
                val request = device.pull ?: return@firstOrNull false
                request.sourceSessionId == inbox.currentSessionId &&
                    request.id !in answeredPulls &&
                    device.canReceive
            } ?: return
        val id = asking.pull?.id ?: return
        if (_state.value.busy || playback.snapshot() == null) return
        answeredPulls.addLast(id)
        while (answeredPulls.size > 16) answeredPulls.removeFirst()
        log("pull_answered", id, now())
        send(asking.sessionId)
    }

    /** Every step of a transfer, on both devices: a failed one used to leave no trace at all. */
    private fun log(
        event: String,
        id: String,
        startedAt: Long,
        vararg extra: Pair<String, String>,
        failure: Throwable? = null,
    ) {
        val attributes =
            mapOf(
                "transfer" to id.take(8),
                "elapsedMs" to (now() - startedAt).coerceAtLeast(0L).toString(),
            ) + extra
        if (failure == null) {
            AppLog.info(category = "handoff", event = event, message = "Device handoff $event", attributes = attributes)
        } else {
            AppLog.warning(
                category = "handoff",
                event = event,
                message = "Device handoff did not complete",
                throwable = failure,
                attributes = attributes,
            )
        }
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

/** The most the handoff service accepts. */
internal const val HANDOFF_OFFER_LIFETIME_SECONDS = 120

/**
 * How long 在此继续 waits for the source to offer. A playing source checks in every ten seconds,
 * so this is three chances.
 */
internal const val HANDOFF_PULL_TIMEOUT_MS = 30_000L

/** A heartbeat stays small: a sealed 正在播放 card larger than this is not sent. Matches the service. */
internal const val HANDOFF_NOW_PLAYING_MAX_CHARS = 1_600

/** Another device of this account playing something this one can take over — 在此继续. */
data class HandoffElsewhere(
    val sessionId: String,
    val deviceName: String,
    val title: String,
    /** Where it has got to, counting the time since the device last checked in. */
    val positionMs: Long,
    val durationMs: Long,
    val mediaKey: String,
    val profileId: String? = null,
)

private class PendingPull(
    val target: HandoffElsewhere,
    val id: String,
    val startedAt: Long,
)

/** The associated data a 正在播放 card is sealed with: the vault's key, and the device it describes. */
private fun nowPlayingId(sessionId: String): String = "now-playing:$sessionId"

/** A 正在播放 card carries the title and where it is, not the tracks and offsets a transfer does. */
private fun HandoffMedia.forNowPlaying(): HandoffMedia =
    HandoffMedia(
        mediaKey = mediaKey,
        title = title.take(120),
        serverId = serverId,
        itemId = itemId,
        positionMs = positionMs,
        durationMs = durationMs,
        profileId = profileId,
        mediaSourceId = mediaSourceId,
    )
