package com.yfuse.watch

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WireMessage(
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

private data class Participant(
    val id: String,
    val name: String,
    val session: WebSocketSession,
)

private data class Room(
    val code: String,
    val itemId: String,
    val participants: LinkedHashMap<String, Participant> = linkedMapOf(),
    var hostId: String,
)

private val rooms = ConcurrentHashMap<String, Room>()
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        watchTogetherModule()
    }.start(wait = true)
}

fun Application.watchTogetherModule() {
    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 40_000L
        maxFrameSize = 64 * 1024L
        masking = false
    }
    routing {
        webSocket("/watch") {
            var joinedRoom: Room? = null
            var joinedClientId: String? = null
            try {
                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach
                    val message = runCatching {
                        json.decodeFromString(WireMessage.serializer(), frame.readText())
                    }.getOrNull() ?: return@consumeEach

                    when (message.type) {
                        "create", "join" -> {
                            if (joinedRoom != null) return@consumeEach
                            val clientId = message.clientId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach sendError("缺少客户端标识")
                            val itemId = message.itemId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach sendError("缺少媒体标识")
                            val room = if (message.type == "create") {
                                createRoom(itemId, clientId)
                            } else {
                                val code = message.roomCode.orEmpty().uppercase()
                                rooms[code] ?: return@consumeEach sendError("房间不存在或已关闭")
                            }
                            if (room.itemId != itemId) {
                                return@consumeEach sendError("房间播放的不是当前媒体")
                            }
                            synchronized(room) {
                                room.participants[clientId] = Participant(
                                    id = clientId,
                                    name = message.name?.take(24).orEmpty().ifBlank { "访客" },
                                    session = this,
                                )
                            }
                            joinedRoom = room
                            joinedClientId = clientId
                            broadcastRoom(room)
                        }

                        "playback" -> {
                            val room = joinedRoom ?: return@consumeEach
                            if (joinedClientId != room.hostId) {
                                sendError("仅房主可以控制播放")
                                return@consumeEach
                            }
                            broadcast(room, message.copy(roomCode = room.code))
                        }

                        "leave" -> return@consumeEach
                    }
                }
            } finally {
                val room = joinedRoom
                val clientId = joinedClientId
                if (room != null && clientId != null) {
                    synchronized(room) {
                        room.participants.remove(clientId)
                        if (room.hostId == clientId) {
                            room.hostId = room.participants.keys.firstOrNull().orEmpty()
                        }
                        if (room.participants.isEmpty()) rooms.remove(room.code)
                    }
                    if (room.participants.isNotEmpty()) broadcastRoom(room)
                }
            }
        }
    }
}

private fun createRoom(itemId: String, hostId: String): Room {
    while (true) {
        val code = buildString {
            repeat(6) { append("ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Random.nextInt(32)]) }
        }
        val room = Room(code = code, itemId = itemId, hostId = hostId)
        if (rooms.putIfAbsent(code, room) == null) return room
    }
}

private suspend fun WebSocketSession.sendError(message: String) {
    send(json.encodeToString(WireMessage.serializer(), WireMessage("error", message = message)))
}

private suspend fun broadcastRoom(room: Room) {
    val members = synchronized(room) { room.participants.values.toList() }
    members.forEach { member ->
        val payload = WireMessage(
            type = "joined",
            roomCode = room.code,
            isHost = member.id == room.hostId,
            participantCount = members.size,
            itemId = room.itemId,
        )
        runCatching {
            member.session.send(json.encodeToString(WireMessage.serializer(), payload))
        }
    }
}

private suspend fun broadcast(room: Room, message: WireMessage) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload = json.encodeToString(WireMessage.serializer(), message)
    members.forEach { member ->
        runCatching { member.session.send(payload) }
    }
}
