package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomPlaylistTest {
    @Test
    fun initialPlaylistIsIncludedInV6AndV5WelcomeAndReconnectSnapshots() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient = createClient { install(WebSockets) }
            val initialPlaylist =
                listOf(
                    WatchWirePlaylistEntry("episode-1", "tmdb:42/s1e1", "第一集"),
                    WatchWirePlaylistEntry("episode-2", "tmdb:42/s1e2", "第二集"),
                )

            val host = socketClient.webSocketSession("/watch")
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.VERSION,
                    clientId = "host",
                    mediaKey = "tmdb:42/s1e1",
                    playlist = initialPlaylist,
                ),
            )
            val hostWelcome = host.receivePlaylistType("welcome")
            val roomCode = hostWelcome.getValue("roomCode").jsonPrimitive.content
            assertEquals(WatchProtocol.VERSION.toLong(), hostWelcome.protocolVersion())
            assertEquals(0L, hostWelcome.playlistRevision())
            assertEquals(listOf("episode-1", "episode-2"), hostWelcome.playlistIds())
            assertTrue(
                WatchProtocol.CAPABILITY_ROOM_PLAYLIST in
                    hostWelcome.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content },
            )

            val guest = socketClient.webSocketSession("/watch")
            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.MIN_SUPPORTED_VERSION,
                    roomCode = roomCode,
                    clientId = "guest",
                ),
            )
            val guestWelcome = guest.receivePlaylistType("welcome")
            val resumeCapability = guestWelcome.getValue("resumeCapability").jsonPrimitive.content
            assertEquals(WatchProtocol.MIN_SUPPORTED_VERSION.toLong(), guestWelcome.protocolVersion())
            assertEquals(0L, guestWelcome.playlistRevision())
            assertEquals(listOf("episode-1", "episode-2"), guestWelcome.playlistIds())

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 0L,
                    playlistEntry =
                        WatchWirePlaylistEntry("episode-3", "tmdb:42/s1e3", "第三集"),
                ),
            )
            val v5RoomUpdate = guest.receivePlaylistRevision(1L)
            assertEquals(
                listOf("episode-1", "episode-2", "episode-3"),
                v5RoomUpdate.playlistIds(),
            )
            guest.close()
            delay(50L)

            val reconnected = socketClient.webSocketSession("/watch")
            reconnected.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.MIN_SUPPORTED_VERSION,
                    roomCode = roomCode,
                    clientId = "guest",
                    resumeCapability = resumeCapability,
                ),
            )
            val reconnectWelcome = reconnected.receivePlaylistType("welcome")
            assertEquals(1L, reconnectWelcome.playlistRevision())
            assertEquals(
                listOf("episode-1", "episode-2", "episode-3"),
                reconnectWelcome.playlistIds(),
            )

            reconnected.close()
            host.close()
        }

    @Test
    fun hostAndModeratorCanMutateButEveryoneModeDoesNotAuthorizeOrdinaryMembers() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient = createClient { install(WebSockets) }
            val first = WatchWirePlaylistEntry("one", "tmdb:1", "One")
            val second = WatchWirePlaylistEntry("two", "tmdb:2", "Two")
            val host = socketClient.webSocketSession("/watch")
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.VERSION,
                    clientId = "host",
                    mediaKey = first.mediaKey,
                    playlist = listOf(first),
                ),
            )
            val hostWelcome = host.receivePlaylistType("welcome")
            val roomCode = hostWelcome.getValue("roomCode").jsonPrimitive.content

            val guest = socketClient.webSocketSession("/watch")
            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.VERSION,
                    roomCode = roomCode,
                    clientId = "guest",
                ),
            )
            guest.receivePlaylistType("welcome")

            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 0L,
                    playlistEntry = second,
                ),
            )
            assertEquals("playlist_forbidden", guest.receivePlaylistError().errorCode())

            host.sendPlaylistWire(
                WatchWireMessage(type = "setControlMode", controlMode = "everyone"),
            )
            guest.receivePlaylistMatching { payload ->
                payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                    payload["controlMode"]?.jsonPrimitive?.content == "everyone"
            }
            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 0L,
                    playlistEntry = second,
                ),
            )
            assertEquals("playlist_forbidden", guest.receivePlaylistError().errorCode())

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 0L,
                    playlistEntry = second,
                    playlistIndex = 1,
                ),
            )
            val afterAdd = host.receivePlaylistRevision(1L)
            assertEquals(listOf("one", "two"), afterAdd.playlistIds())

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "setModerator",
                    targetClientId = "guest",
                    moderator = true,
                ),
            )
            guest.receivePlaylistMatching { payload ->
                payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
                    payload.getValue("participants").jsonArray.any { participant ->
                        val item = participant.jsonObject
                        item["clientId"]?.jsonPrimitive?.content == "guest" &&
                            item["isModerator"]?.jsonPrimitive?.boolean == true
                    }
            }

            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistUpdate",
                    playlistRevision = 1L,
                    playlistEntry = first.copy(title = "One updated"),
                ),
            )
            val afterUpdate = guest.receivePlaylistRevision(2L)
            assertEquals("One updated", afterUpdate.playlistTitle("one"))

            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistReorder",
                    playlistRevision = 2L,
                    playlistEntryId = "two",
                    playlistIndex = 0,
                ),
            )
            assertEquals(listOf("two", "one"), guest.receivePlaylistRevision(3L).playlistIds())

            guest.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistRemove",
                    playlistRevision = 3L,
                    playlistEntryId = "one",
                ),
            )
            assertEquals(listOf("two"), guest.receivePlaylistRevision(4L).playlistIds())

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 4L,
                    playlistEntry = second,
                ),
            )
            assertUnchangedPlaylistError(host, "playlist_duplicate", 4L)
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistRemove",
                    playlistRevision = 3L,
                    playlistEntryId = "two",
                ),
            )
            assertUnchangedPlaylistError(host, "playlist_stale", 4L)
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistReorder",
                    playlistRevision = 4L,
                    playlistEntryId = "two",
                    playlistIndex = 2,
                ),
            )
            assertUnchangedPlaylistError(host, "playlist_index_invalid", 4L)
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistReorder",
                    playlistRevision = 4L,
                    playlistEntryId = "two",
                    playlistIndex = 0,
                ),
            )
            assertUnchangedPlaylistError(host, "playlist_unchanged", 4L)
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistRemove",
                    playlistRevision = 4L,
                    playlistEntryId = "missing",
                ),
            )
            assertUnchangedPlaylistError(host, "playlist_not_found", 4L)

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistUpdate",
                    playlistRevision = 4L,
                    playlistEntry = second.copy(title = "Two updated"),
                ),
            )
            val afterSafeErrors = host.receivePlaylistRevision(5L)
            assertEquals(listOf("two"), afterSafeErrors.playlistIds())
            assertEquals("Two updated", afterSafeErrors.playlistTitle("two"))

            guest.close()
            host.close()
        }

    @Test
    fun invalidInitialPlaylistsAreRejectedAndRoomCapacityIsStrict() =
        testApplication {
            application { watchTogetherModule() }
            val socketClient = createClient { install(WebSockets) }
            val valid = WatchWirePlaylistEntry("entry", "tmdb:1", "Valid")

            suspend fun assertRejected(
                clientId: String,
                playlist: List<WatchWirePlaylistEntry>,
            ) {
                val session = socketClient.webSocketSession("/watch")
                session.sendPlaylistWire(
                    WatchWireMessage(
                        type = "hello",
                        protocolVersion = WatchProtocol.VERSION,
                        clientId = clientId,
                        mediaKey = "tmdb:1",
                        playlist = playlist,
                    ),
                )
                assertEquals("playlist_invalid", session.receivePlaylistError().errorCode())
                session.close()
            }

            assertRejected("bad-id", listOf(valid.copy(id = "bad id")))
            assertRejected("bad-title", listOf(valid.copy(title = "bad\ntitle")))
            assertRejected("bad-media", listOf(valid.copy(mediaKey = "tmdb:bad key")))
            assertRejected("duplicate", listOf(valid, valid.copy(title = "Duplicate")))
            assertRejected(
                "too-many",
                List(WatchProtocol.MAX_PLAYLIST_ENTRIES + 1) { index ->
                    valid.copy(id = "entry-$index")
                },
            )

            val fullPlaylist =
                List(WatchProtocol.MAX_PLAYLIST_ENTRIES) { index ->
                    valid.copy(id = "entry-$index", title = "Entry $index")
                }
            val host = socketClient.webSocketSession("/watch")
            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.VERSION,
                    clientId = "full-host",
                    mediaKey = "tmdb:1",
                    playlist = fullPlaylist,
                ),
            )
            val welcome = host.receivePlaylistType("welcome")
            assertEquals(WatchProtocol.MAX_PLAYLIST_ENTRIES, welcome.playlistIds().size)
            assertEquals(0L, welcome.playlistRevision())

            host.sendPlaylistWire(
                WatchWireMessage(
                    type = "playlistAdd",
                    playlistRevision = 0L,
                    playlistEntry = WatchWirePlaylistEntry("overflow", "tmdb:2", "Overflow"),
                ),
            )
            val fullError = host.receivePlaylistError()
            assertEquals("playlist_full", fullError.errorCode())
            assertEquals(0L, fullError.playlistRevision())
            assertEquals(WatchProtocol.MAX_PLAYLIST_ENTRIES, fullError.playlistIds().size)

            val joiner = socketClient.webSocketSession("/watch")
            joiner.sendPlaylistWire(
                WatchWireMessage(
                    type = "hello",
                    protocolVersion = WatchProtocol.VERSION,
                    roomCode = welcome.getValue("roomCode").jsonPrimitive.content,
                    clientId = "joiner",
                    playlist = emptyList(),
                ),
            )
            assertEquals("playlist_initial_only", joiner.receivePlaylistError().errorCode())

            joiner.close()
            host.close()
        }
}

private val playlistWireJson = Json { encodeDefaults = false }

private suspend fun WebSocketSession.sendPlaylistWire(message: WatchWireMessage) {
    send(playlistWireJson.encodeToString(WatchWireMessage.serializer(), message))
}

private suspend fun WebSocketSession.receivePlaylistType(type: String): JsonObject =
    receivePlaylistMatching { payload -> payload["type"]?.jsonPrimitive?.content == type }

private suspend fun WebSocketSession.receivePlaylistError(): JsonObject = receivePlaylistType("error")

private suspend fun WebSocketSession.receivePlaylistRevision(revision: Long): JsonObject =
    receivePlaylistMatching { payload ->
        payload["type"]?.jsonPrimitive?.content == "roomUpdate" &&
            payload["playlistRevision"]?.jsonPrimitive?.long == revision
    }

private suspend fun WebSocketSession.receivePlaylistMatching(predicate: (JsonObject) -> Boolean): JsonObject =
    withTimeout(3_000L) {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val payload = Json.parseToJsonElement(frame.readText()).jsonObject
            if (predicate(payload)) return@withTimeout payload
        }
        error("unreachable")
    }

private suspend fun assertUnchangedPlaylistError(
    session: WebSocketSession,
    errorCode: String,
    revision: Long,
) {
    val error = session.receivePlaylistError()
    assertEquals(errorCode, error.errorCode())
    assertEquals(revision, error.playlistRevision())
    assertEquals(listOf("two"), error.playlistIds())
}

private fun JsonObject.errorCode(): String = getValue("errorCode").jsonPrimitive.content

private fun JsonObject.protocolVersion(): Long = getValue("protocolVersion").jsonPrimitive.long

private fun JsonObject.playlistRevision(): Long = getValue("playlistRevision").jsonPrimitive.long

private fun JsonObject.playlistIds(): List<String> =
    getValue("playlist").jsonArray.map { entry ->
        entry.jsonObject
            .getValue("id")
            .jsonPrimitive
            .content
    }

private fun JsonObject.playlistTitle(id: String): String {
    val entry =
        getValue("playlist")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
    assertFalse("url" in entry)
    assertFalse("token" in entry)
    return entry.getValue("title").jsonPrimitive.content
}
