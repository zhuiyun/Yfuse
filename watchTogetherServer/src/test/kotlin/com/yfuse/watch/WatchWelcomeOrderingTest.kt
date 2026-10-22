package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchWelcomeOrderingTest {
    @Test
    fun broadcasts_cannot_overtake_a_blocked_welcome_and_the_first_update_has_the_current_playlist() =
        runBlocking {
            val room = room()
            val host = RecordingSession()
            room.addParticipant("host", host)
            assertTrue(host.welcome(room, "host"))
            assertEquals("welcome", host.frames.receive().field("type"))
            val guest = RecordingSession(blockWelcome = true)
            val participant = room.addParticipant("guest", guest)
            assertFalse(participant.readyForBroadcast)
            val updates = RoomUpdateBroadcaster(this, minIntervalMs = 0L)
            val welcome = async { guest.welcome(room, "guest") }
            try {
                withTimeout(2_000L) { guest.welcomeEntered.await() }
                // Exercise the production broadcaster while welcome is suspended in the socket.
                updates.request(room)
                val beforeWelcome = host.frames.receive()
                assertEquals("roomUpdate", beforeWelcome.field("type"))
                assertEquals("1", beforeWelcome.field("participantCount"))
                assertEquals(
                    listOf("host"),
                    beforeWelcome.getValue("participants").jsonArray.map { it.jsonObject.field("clientId") },
                )
                broadcastChat(room, room.chatHistory.first())
                assertEquals("chat", host.frames.receive().field("type"))
                assertTrue(guest.frames.tryReceive().isFailure)
                assertFalse(participant.readyForBroadcast)

                synchronized(room) {
                    room.playlist += WatchWirePlaylistEntry("two", "tmdb:604", "Two")
                    room.playlistRevision++
                    room.chatHistory +=
                        WatchWireChatMessage(2L, "host", "Host", 0, "During welcome", WatchClock.nowMs())
                }
                guest.releaseWelcome.complete(Unit)
                assertTrue(withTimeout(2_000L) { welcome.await() })
                assertTrue(participant.readyForBroadcast)
                val initial = guest.frames.receive()
                assertEquals("welcome", initial.field("type"))
                assertEquals(1, initial.getValue("playlist").jsonArray.size)
                assertEquals(1, initial.getValue("chatHistory").jsonArray.size)
                val catchUp = guest.frames.receive()
                assertEquals("chat", catchUp.field("type"))
                assertEquals("During welcome", catchUp.getValue("chat").jsonObject.field("text"))

                updates.request(room)
                val firstUpdate = guest.frames.receive()
                assertEquals("roomUpdate", firstUpdate.field("type"))
                assertEquals("2", firstUpdate.field("participantCount"))
                assertEquals("1", firstUpdate.field("playlistRevision"))
                assertEquals(2, firstUpdate.getValue("playlist").jsonArray.size)
                assertTrue(firstUpdate.field("roomRevision")!!.toLong() > initial.field("roomRevision")!!.toLong())
                updates.request(room)
                assertFalse("playlist" in guest.frames.receive())
            } finally {
                guest.releaseWelcome.complete(Unit)
                welcome.cancelAndJoin()
            }
        }

    @Test
    fun a_failed_welcome_never_publishes_the_member_to_a_broadcast() =
        runBlocking {
            val room = room()
            val guest = RecordingSession(failWelcome = true)
            val participant = room.addParticipant("guest", guest)
            assertFailsWith<IOException> { guest.welcome(room, "guest") }
            assertFalse(participant.readyForBroadcast)
            val updates = RoomUpdateBroadcaster(this, minIntervalMs = 0L)
            updates.request(room)
            broadcastChat(room, room.chatHistory.first())
            assertTrue(guest.frames.tryReceive().isFailure)
            assertTrue(room.wireParticipants().isEmpty())
        }

    @Test
    fun an_outgoing_welcome_cannot_mark_a_replacement_session_ready() =
        runBlocking {
            val room = room()
            val outgoing = RecordingSession(blockWelcome = true)
            room.addParticipant("guest", outgoing)
            val welcome = async { outgoing.welcome(room, "guest") }
            try {
                withTimeout(2_000L) { outgoing.welcomeEntered.await() }
                val replacement = RecordingSession()
                val participant = synchronized(room) { room.addParticipant("guest", replacement) }
                outgoing.releaseWelcome.complete(Unit)
                assertFalse(withTimeout(2_000L) { welcome.await() })
                assertFalse(participant.readyForBroadcast)
                assertTrue(replacement.welcome(room, "guest"))
                assertTrue(participant.readyForBroadcast)
            } finally {
                outgoing.releaseWelcome.complete(Unit)
                welcome.cancelAndJoin()
            }
        }

    private fun room(): Room =
        Room(
            code = "ABC234",
            creatorIp = "local",
            creatorAccountUserId = "account",
            hostId = "host",
            hostCapabilityDigest = ByteArray(32),
            timeline = Timeline("tmdb:603", 0L, WatchClock.nowMs()),
            playlist = mutableListOf(WatchWirePlaylistEntry("one", "tmdb:603", "One")),
            chatHistory =
                ArrayDeque(
                    listOf(WatchWireChatMessage(1L, "host", "Host", 0, "Earlier message", WatchClock.nowMs())),
                ),
        )

    private fun Room.addParticipant(
        id: String,
        socket: WebSocketSession,
    ): Participant =
        Participant(
            id = id,
            name = id,
            avatarId = 0,
            session = socket,
            sessionGeneration = 1L,
            accountUserId = "account-$id",
            authorizedHostEpoch = hostEpoch.takeIf { id == hostId },
            roomDeltas = true,
        ).also { participants[id] = it }

    private suspend fun WebSocketSession.welcome(
        room: Room,
        id: String,
    ): Boolean = sendRoomWelcome(room, id, null, null, WatchProtocol.VERSION)

    /** Real coroutine suspension in send(), so the production broadcaster runs during the handshake. */
    private class RecordingSession(
        private val blockWelcome: Boolean = false,
        private val failWelcome: Boolean = false,
    ) : WebSocketSession by unusedSession() {
        val welcomeEntered = CompletableDeferred<Unit>()
        val releaseWelcome = CompletableDeferred<Unit>()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)

        override suspend fun send(frame: Frame) {
            val payload = (frame as Frame.Text).readText().wireJson()
            if (payload.field("type") == "welcome") {
                welcomeEntered.complete(Unit)
                if (blockWelcome) releaseWelcome.await()
                if (failWelcome) throw IOException("Test transport closed during welcome")
            }
            frames.send(payload)
        }
    }
}

private fun unusedSession(): WebSocketSession =
    Proxy.newProxyInstance(WebSocketSession::class.java.classLoader, arrayOf(WebSocketSession::class.java)) {
            _,
            method,
            _,
        ->
        error("Unexpected transport call: ${method.name}")
    } as WebSocketSession
