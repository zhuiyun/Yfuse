package com.yfuse.core.sync

import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WatchWireMessage(
    val type: String,
    val roomCode: String? = null,
    val clientId: String? = null,
    val name: String? = null,
    val itemId: String? = null,
    val itemIndex: Int? = null,
    val positionMs: Long? = null,
    val playing: Boolean? = null,
    val sentAtEpochMs: Long? = null,
    val isHost: Boolean? = null,
    val participantCount: Int? = null,
    val message: String? = null,
)

data class WatchTogetherState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val itemId: String? = null,
    val error: String? = null,
)

data class WatchPlaybackUpdate(
    val itemId: String,
    val itemIndex: Int,
    val positionMs: Long,
    val playing: Boolean,
    val sentAtEpochMs: Long,
)

class WatchTogetherClient(private val preferences: WatchTogetherPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()
    private var socketJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private val _state = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = _state.asStateFlow()
    private val _playbackUpdates = MutableSharedFlow<WatchPlaybackUpdate>(extraBufferCapacity = 8)
    val playbackUpdates: SharedFlow<WatchPlaybackUpdate> = _playbackUpdates.asSharedFlow()

    fun createRoom(endpoint: String, itemId: String, name: String = "房主") {
        connect(endpoint, roomCode = null, itemId = itemId, name = name, create = true)
    }

    fun joinRoom(endpoint: String, roomCode: String, itemId: String, name: String = "访客") {
        connect(endpoint, roomCode.uppercase(), itemId, name, create = false)
    }

    fun sendPlayback(
        itemId: String,
        itemIndex: Int,
        positionMs: Long,
        playing: Boolean,
    ) {
        if (!_state.value.connected || !_state.value.isHost) return
        send(
            WatchWireMessage(
                type = "playback",
                itemId = itemId,
                itemIndex = itemIndex,
                positionMs = positionMs,
                playing = playing,
                sentAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    fun leave() {
        val oldSession = session
        val oldJob = socketJob
        session = null
        socketJob = null
        oldJob?.cancel()
        _state.value = WatchTogetherState()
        scope.launch {
            runCatching {
                oldSession?.close(CloseReason(CloseReason.Codes.NORMAL, "leave"))
            }
        }
    }

    private fun connect(
        endpoint: String,
        roomCode: String?,
        itemId: String,
        name: String,
        create: Boolean,
    ) {
        val url = endpoint.toWebSocketUrl()
        if (url == null) {
            _state.value = WatchTogetherState(error = "一起看服务地址无效")
            return
        }
        preferences.setEndpoint(endpoint)
        leave()
        _state.value = WatchTogetherState(connecting = true)
        socketJob = scope.launch {
            runCatching {
                client.webSocket(urlString = url) {
                    session = this
                    send(
                        json.encodeToString(
                            WatchWireMessage.serializer(),
                            WatchWireMessage(
                                type = if (create) "create" else "join",
                                roomCode = roomCode,
                                clientId = preferences.clientId,
                                name = name,
                                itemId = itemId,
                            ),
                        ),
                    )
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        handle(frame.readText())
                    }
                }
            }.onFailure { error ->
                _state.value = WatchTogetherState(
                    error = error.message?.takeIf { it.isNotBlank() } ?: "一起看连接失败",
                )
            }
            session = null
            if (_state.value.error == null) _state.value = WatchTogetherState()
        }
    }

    private suspend fun handle(raw: String) {
        val message = runCatching {
            json.decodeFromString(WatchWireMessage.serializer(), raw)
        }.getOrNull() ?: return
        when (message.type) {
            "joined" -> _state.value = WatchTogetherState(
                connected = true,
                roomCode = message.roomCode,
                isHost = message.isHost == true,
                participantCount = message.participantCount ?: 1,
                itemId = message.itemId,
            )
            "playback" -> {
                val itemId = message.itemId ?: return
                _playbackUpdates.emit(
                    WatchPlaybackUpdate(
                        itemId = itemId,
                        itemIndex = message.itemIndex ?: 0,
                        positionMs = message.positionMs ?: 0L,
                        playing = message.playing == true,
                        sentAtEpochMs = message.sentAtEpochMs ?: System.currentTimeMillis(),
                    ),
                )
            }
            "error" -> _state.value = _state.value.copy(
                connecting = false,
                error = message.message ?: "一起看服务返回错误",
            )
        }
    }

    private fun send(message: WatchWireMessage) {
        scope.launch {
            sendMutex.withLock {
                val active = session ?: return@withLock
                runCatching {
                    active.send(
                        json.encodeToString(WatchWireMessage.serializer(), message),
                    )
                }.onFailure {
                    _state.value = _state.value.copy(error = "同步状态发送失败")
                }
            }
        }
    }
}

private fun String.toWebSocketUrl(): String? {
    val normalized = trim().trimEnd('/')
    if (normalized.isEmpty()) return null
    val websocket = when {
        normalized.startsWith("ws://") || normalized.startsWith("wss://") -> normalized
        normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
        normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
        else -> return null
    }
    return if (websocket.endsWith("/watch")) websocket else "$websocket/watch"
}
