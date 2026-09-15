package com.yfuse.core.trakt

import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.toBase64Url
import com.yfuse.watch.protocol.TraktAuthChallenge
import com.yfuse.watch.protocol.TraktAuthStatus
import com.yfuse.watch.protocol.TraktConfiguration
import com.yfuse.watch.protocol.TraktRefreshRequest
import com.yfuse.watch.protocol.TraktToken
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** Import is additive. Implementations retain existing local decisions and deletion tombstones. */
interface TraktImportSink {
    suspend fun importWatchlist(item: TraktListItem): Boolean

    suspend fun importHistory(item: TraktListItem): Boolean
}

data class TraktUiState(
    val signedIn: Boolean = false,
    val configuration: TraktConfiguration? = null,
    val connected: Boolean = false,
    val scrobbling: Boolean = false,
    val challenge: TraktAuthChallenge? = null,
    val busy: Boolean = false,
    val pending: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
internal data class TraktOutboxEvent(
    val id: String,
    val playbackSessionId: String,
    val media: TraktPlaybackMedia,
    val action: TraktPlaybackAction,
    val progress: Float,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
)

@Serializable
internal data class TraktLocalState(
    val clientId: String = "",
    val token: TraktToken? = null,
    val refresh: TraktRefreshRequest? = null,
    val scrobbling: Boolean = false,
    val events: List<TraktOutboxEvent> = emptyList(),
    val historyPage: Int = 1,
    val historyEndAt: String? = null,
    val watchlistPage: Int = 1,
)

/** Local encrypted credentials/outbox, isolated by the active account AND personal profile. */
class TraktRepository(
    private val api: TraktApi,
    private val auth: TraktAuthApi,
    private val secureStore: SecureStore,
    private val owner: StateFlow<String?>,
    private val importSink: TraktImportSink,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private var local = TraktLocalState()
    private var currentOwner: String? = null
    private var playbackGeneration = 0L
    private var activeScope: CoroutineScope? = null
    private var lifetime: Job? = null
    private var authJob: Job? = null
    private val refreshMutex = Mutex()
    private val flushMutex = Mutex()
    private var nextWriteAtEpochMs = 0L
    private val _state = MutableStateFlow(TraktUiState())
    val state = _state.asStateFlow()

    fun start() {
        if (lifetime != null) return
        lifetime =
            scope.launch {
                owner.collectLatest { identity ->
                    playbackGeneration++
                    currentOwner = identity
                    local = TraktLocalState()
                    _state.value = TraktUiState(signedIn = identity != null)
                    if (identity == null) return@collectLatest
                    try {
                        val bytes = secureStore.get(storageKey(identity))
                        if (bytes != null) {
                            try {
                                check(bytes.size <= 512 * 1024)
                                local = json.decodeFromString<TraktLocalState>(bytes.decodeToString())
                                check(local.events.size <= 128 && (local.token == null || local.token!!.valid()))
                            } finally {
                                bytes.fill(0)
                            }
                        }
                        publish()
                    } catch (_: Exception) {
                        local = TraktLocalState()
                        _state.update { it.copy(error = "Trakt 本地授权无法读取，请重新连接") }
                    }
                    coroutineScope {
                        activeScope = this
                        try {
                            while (true) {
                                flush(identity)
                                delay(5_000)
                            }
                        } finally {
                            activeScope = null
                            authJob?.cancel()
                            authJob = null
                        }
                    }
                }
            }
    }

    fun close() {
        lifetime?.cancel()
        lifetime = null
    }

    suspend fun refreshConfiguration() {
        val identity = currentOwner ?: return
        try {
            val config = auth.configuration()
            ensureOwner(identity)
            _state.update { it.copy(configuration = config, error = null) }
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: Exception) {
            _state.update { it.copy(error = "Trakt 接入服务尚未配置或暂不可用") }
        }
    }

    fun connect(device: Boolean) {
        val identity = currentOwner ?: return
        if (_state.value.busy) return
        val currentScope = activeScope ?: return
        _state.update { it.copy(busy = true, error = null, message = null) }
        authJob =
            currentScope.launch {
                var challenge: TraktAuthChallenge? = null
                try {
                    val config = auth.configuration()
                    ensureOwner(identity)
                    _state.update { it.copy(configuration = config) }
                    check(if (device) config.deviceAvailable else config.oauthAvailable) { "Trakt 尚未配置" }
                    challenge = auth.begin(device)
                    ensureOwner(identity)
                    _state.update { it.copy(challenge = challenge) }
                    withTimeout((challenge.expiresAtEpochMs - now()).coerceIn(1, 1_800_000)) {
                        var interval = challenge.intervalSeconds.coerceIn(1, 60)
                        while (true) {
                            delay(interval * 1000L)
                            val response = auth.poll(challenge.id)
                            ensureOwner(identity)
                            when (response.status) {
                                TraktAuthStatus.Pending -> interval = response.retryAfterSeconds.coerceIn(1, 3600)
                                TraktAuthStatus.Connected -> {
                                    val token = response.token ?: error("授权响应无效")
                                    check(token.valid())
                                    // A new authorization may belong to another Trakt account; drop old queued writes.
                                    save(identity, TraktLocalState(clientId = config.clientId, token = token))
                                    _state.update { it.copy(message = "Trakt 已连接；播放上报默认关闭", error = null) }
                                    break
                                }
                                TraktAuthStatus.Denied -> error("已拒绝 Trakt 授权")
                                TraktAuthStatus.Expired, TraktAuthStatus.Used -> error("授权代码已失效，请重新连接")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (owner.value == identity) _state.update { it.copy(error = "Trakt 授权已取消或过期") }
                    throw cancelled
                } catch (_: Exception) {
                    if (owner.value == identity) _state.update { it.copy(error = "Trakt 未连接，请确认配置或重新授权") }
                } finally {
                    withContext(NonCancellable) {
                        challenge?.let {
                            try {
                                withTimeout(3_000) { auth.cancel(it.id) }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (owner.value == identity) _state.update { it.copy(challenge = null, busy = false) }
                }
            }
    }

    fun cancelAuthorization() {
        authJob?.cancel()
    }

    fun setScrobbling(enabled: Boolean) {
        val identity = currentOwner ?: return
        if (local.token == null) return
        save(identity, local.copy(scrobbling = enabled, events = if (enabled) local.events else emptyList()))
    }

    fun disconnect() {
        val identity = currentOwner ?: return
        val token = local.token ?: return
        // Local opt-out is immediate even if remote revoke fails; no queued data can leave afterwards.
        authJob?.cancel()
        save(identity, TraktLocalState())
        activeScope?.launch {
            try {
                auth.revoke(token.accessToken)
                ensureOwner(identity)
                _state.update { it.copy(message = "Trakt 已断开", error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner.value == identity) _state.update { it.copy(error = "本机已断开；远端撤销失败，可在 Trakt 的应用设置中移除授权") }
            }
        }
    }

    fun importWatchlist() = importItems(history = false)

    fun importHistory() = importItems(history = true)

    /** Captured once per mounted playback session so late callbacks cannot enter another profile. */
    fun capturePlaybackOwner(): String? = currentOwner?.takeIf { it == owner.value }?.let { "$it:$playbackGeneration" }

    private fun importItems(history: Boolean) {
        val identity = currentOwner ?: return
        if (_state.value.busy || local.token == null) return
        _state.update { it.copy(busy = true, error = null) }
        activeScope?.launch {
            var imported = 0
            var retained = 0
            try {
                if (history &&
                    local.historyEndAt == null
                ) {
                    save(identity, local.copy(historyEndAt = Instant.ofEpochMilli(now()).toString()))
                }
                var done = false
                // Per-run bound limits network/CPU use; persisted page permits an explicit continuation.
                repeat(50) {
                    if (done) return@repeat
                    val pageNumber = if (history) local.historyPage else local.watchlistPage
                    val endAt = local.historyEndAt.orEmpty()
                    val page =
                        authorized(identity) { token ->
                            if (history) {
                                api.history(
                                    local.clientId,
                                    token,
                                    pageNumber,
                                    endAt,
                                )
                            } else {
                                api.watchlist(local.clientId, token, pageNumber)
                            }
                        }
                    ensureOwner(identity)
                    for (item in page.items) {
                        ensureOwner(identity)
                        val changed = if (history) importSink.importHistory(item) else importSink.importWatchlist(item)
                        if (changed) imported++ else retained++
                    }
                    done = !page.hasNext
                    save(
                        identity,
                        if (history) {
                            local.copy(
                                historyPage = if (done) 1 else pageNumber + 1,
                                historyEndAt = if (done) null else local.historyEndAt,
                            )
                        } else {
                            local.copy(watchlistPage = if (done) 1 else pageNumber + 1)
                        },
                    )
                    _state.update { it.copy(message = "已导入 $imported 项，保留或跳过 $retained 项") }
                }
                if (!done) _state.update { it.copy(message = "本轮已导入 $imported 项；再次点击可继续剩余内容") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner.value == identity) _state.update { it.copy(error = "导入未完成，已完成内容已保留；重试将从上次页继续") }
            } finally {
                if (owner.value == identity) _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Called for real playback transitions only, never for preloading, seeking or periodic UI ticks. */
    fun recordPlayback(
        media: TraktPlaybackMedia,
        playbackSessionId: String,
        action: TraktPlaybackAction,
        positionMs: Long,
        durationMs: Long,
        expectedOwner: String? = capturePlaybackOwner(),
    ) {
        val identity = currentOwner ?: return
        if (expectedOwner == null || capturePlaybackOwner() != expectedOwner) return
        if (!local.scrobbling || local.token == null || !media.valid() || durationMs <= 0 || positionMs < 0) return
        val event =
            TraktOutboxEvent(
                UUID.randomUUID().toString(),
                playbackSessionId.take(128),
                media,
                action,
                (positionMs.toDouble() / durationMs * 100).coerceIn(0.0, 100.0).toFloat(),
                now(),
            )
        val other = local.events.filterNot { it.playbackSessionId == event.playbackSessionId && it.media == media }
        if (other.size >= 128) {
            _state.update { it.copy(error = "待上报队列已满，请联网重试") }
            return
        }
        save(identity, local.copy(events = other + event))
        activeScope?.launch { flush(identity) }
    }

    fun retryPending() {
        val identity = currentOwner ?: return
        activeScope?.launch { flush(identity) }
    }

    private suspend fun flush(identity: String) {
        if (!local.scrobbling || local.token == null || !flushMutex.tryLock()) return
        try {
            val event = local.events.firstOrNull() ?: return
            if (maxOf(event.nextAttemptAtEpochMs, nextWriteAtEpochMs) > now()) return
            if (now() - event.createdAtEpochMs > 15 * 60_000 &&
                !(event.action == TraktPlaybackAction.Stop && event.progress > 80f)
            ) {
                // Stale starts/pauses must never overwrite a new device's active playback.
                save(identity, local.copy(events = local.events.filterNot { it.id == event.id }))
                _state.update { it.copy(error = "一条超过 15 分钟的播放事件已过期，未覆盖 Trakt 当前状态") }
                return
            }
            try {
                nextWriteAtEpochMs = now() + 1_000
                authorized(
                    identity,
                ) { token -> api.scrobble(local.clientId, token, event.media, event.action, event.progress) }
                ensureOwner(identity)
                if (local.scrobbling) save(identity, local.copy(events = local.events.filterNot { it.id == event.id }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureOwner(identity)
                val seconds =
                    maxOf(
                        (failure as? TraktApiException)?.retryAfterSeconds ?: 0,
                        (
                            5L shl
                                event.attempts.coerceAtMost(8)
                        ).coerceAtMost(900).toInt(),
                    )
                save(
                    identity,
                    local.copy(
                        events =
                            local.events.map {
                                if (it.id ==
                                    event.id
                                ) {
                                    it.copy(attempts = it.attempts + 1, nextAttemptAtEpochMs = now() + seconds * 1000L)
                                } else {
                                    it
                                }
                            },
                    ),
                )
                _state.update { it.copy(error = "播放上报暂未完成，将按退避时间重试") }
            }
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun <T> authorized(
        identity: String,
        block: suspend (String) -> T,
    ): T {
        val token = token(identity)
        return try {
            block(token.accessToken)
        } catch (failure: TraktApiException) {
            if (failure.status != 401) throw failure
            block(token(identity, rejectedAccessToken = token.accessToken).accessToken)
        }
    }

    private suspend fun token(
        identity: String,
        rejectedAccessToken: String? = null,
    ): TraktToken =
        refreshMutex.withLock {
            ensureOwner(identity)
            val token = local.token ?: error("请连接 Trakt")
            if (token.accessToken != rejectedAccessToken &&
                token.createdAt * 1000 + token.expiresIn * 1000 > now() + 60_000
            ) {
                return@withLock token
            }
            val pending = local.refresh ?: TraktRefreshRequest(token.refreshToken, UUID.randomUUID().toString())
            save(identity, local.copy(refresh = pending))
            val replacement = auth.refresh(pending)
            ensureOwner(identity)
            check(replacement.valid())
            // A disconnect/reconnect while the request was in flight must not resurrect old credentials.
            if (local.token?.refreshToken != token.refreshToken) throw CancellationException("Trakt account changed")
            save(identity, local.copy(token = replacement, refresh = null))
            replacement
        }

    private fun save(
        identity: String,
        replacement: TraktLocalState,
    ) {
        ensureOwner(identity)
        val bytes = json.encodeToString(replacement).encodeToByteArray()
        try {
            check(bytes.size <= 512 * 1024)
            secureStore.put(storageKey(identity), bytes)
        } finally {
            bytes.fill(0)
        }
        local = replacement
        publish()
    }

    private fun publish() {
        _state.update {
            it.copy(
                connected = local.token != null,
                scrobbling = local.scrobbling,
                pending = local.events.size,
            )
        }
    }

    private fun ensureOwner(identity: String) {
        if (identity != currentOwner ||
            identity != owner.value
        ) {
            throw CancellationException("Trakt profile changed")
        }
    }

    private fun storageKey(identity: String) = "trakt:${identity.encodeToByteArray().toBase64Url()}"
}

fun traktPlaybackMedia(
    type: String,
    providerIds: Map<String, String>,
): TraktPlaybackMedia? {
    val ids = providerIds.mapKeys { it.key.lowercase() }
    return TraktPlaybackMedia(
        if (type.equals("Movie", true)) {
            "movie"
        } else if (type.equals("Episode", true)) {
            "episode"
        } else {
            return null
        },
        TraktIds(ids["trakt"]?.toLongOrNull(), ids["tmdb"]?.toIntOrNull(), ids["tvdb"]?.toLongOrNull(), ids["imdb"]),
    ).takeIf { it.valid() }
}
