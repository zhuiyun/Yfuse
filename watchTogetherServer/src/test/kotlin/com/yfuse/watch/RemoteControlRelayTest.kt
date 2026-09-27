package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.LoginRequest
import com.yfuse.watch.account.RegisterRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteControlRelayTest {
    @Test
    fun pairing_is_keyed_by_the_account_and_input_only_goes_to_the_television() {
        val relay = RemoteControlRelay<String>()
        assertEquals(RemoteAdmission.Hosted<String>(null, 0), relay.host("alice", "tv", "tv-socket"))
        // Another account's phone cannot even find the television, and nothing controls itself.
        assertEquals(
            RemoteAdmission.Refused(RemoteRefusal.Unavailable),
            relay.join("mallory", "mallory-phone", "tv", "mallory-socket"),
        )
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.Unavailable), relay.join("alice", "tv", "tv", "loop"))
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = true),
            relay.join("alice", "phone", "tv", "phone-socket"),
        )
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = false),
            relay.join("alice", "phone", "tv", "phone-socket"),
        )
        assertEquals(RemoteAdmission.Input("tv-socket"), relay.admitInput("phone-socket", nowMs = 0L))
        // A socket is a television or a phone, never both, and a television sends no input.
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("tv-socket", nowMs = 0L))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("mallory-socket", 0L))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.host("alice", "phone", "phone-socket"))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.join("alice", "tv", "tv", "tv-socket"))
        assertTrue(relay.involves("phone-socket"))
        assertFalse(relay.involves("mallory-socket"))
    }

    @Test
    fun a_television_takes_a_few_phones_and_hears_them_come_and_go() {
        val relay = RemoteControlRelay<String>(maxControllersPerHost = 2)
        relay.host("alice", "tv", "tv-socket")
        relay.join("alice", "phone-1", "tv", "p1")
        relay.join("alice", "phone-2", "tv", "p2")
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.Busy), relay.join("alice", "phone-3", "tv", "p3"))
        assertEquals(RemoteDeparture.PhoneLeft("tv-socket", 1), relay.depart("p1"))
        assertEquals(RemoteAdmission.Joined("tv-socket", 2, fresh = true), relay.join("alice", "phone-3", "tv", "p3"))
        assertEquals(2, relay.phonesOn("alice", "tv"))
        assertEquals(RemoteDeparture.None, relay.depart("never-seen"))
    }

    @Test
    fun a_reconnecting_television_keeps_its_phones_and_its_stale_socket_leaves_quietly() {
        val relay = RemoteControlRelay<String>()
        relay.host("alice", "tv", "old")
        relay.join("alice", "phone", "tv", "p1")
        assertEquals(RemoteAdmission.Hosted("old", 1), relay.host("alice", "tv", "new"))
        assertEquals(RemoteAdmission.Input("new"), relay.admitInput("p1", nowMs = 0L))
        assertEquals(RemoteDeparture.None, relay.depart("old"))
        assertEquals(RemoteDeparture.HostLeft(listOf("p1")), relay.depart("new"))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("p1", nowMs = 0L))
        assertFalse(relay.involves("p1"))
    }

    @Test
    fun input_is_paced_per_phone_and_hosts_are_bounded() {
        val relay = RemoteControlRelay<String>(maxHosts = 1, maxInputsPerWindow = 3, inputWindowMs = 1_000L)
        relay.host("alice", "tv", "tv-socket")
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.Full), relay.host("bob", "tv", "bob-tv"))
        assertEquals(RemoteAdmission.Hosted("tv-socket", 0), relay.host("alice", "tv", "tv-again"))
        relay.join("alice", "phone", "tv", "p1")
        repeat(3) { assertEquals(RemoteAdmission.Input("tv-again"), relay.admitInput("p1", nowMs = it.toLong())) }
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.RateLimited), relay.admitInput("p1", nowMs = 3L))
        assertEquals(RemoteAdmission.Input("tv-again"), relay.admitInput("p1", nowMs = 1_000L))
    }

    @Test
    fun a_phone_drives_a_television_of_its_own_account_over_the_relay() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val phoneAuth = backend.service.register(RegisterRequest("remote-owner", "Watch-Test-42"))
            val tvAuth = backend.service.login(LoginRequest("remote-owner", "Watch-Test-42", deviceName = "客厅电视"))
            val strangerAuth = backend.service.register(RegisterRequest("remote-stranger", "Watch-Test-42"))
            val tvSession = backend.service.validateAccessToken(tvAuth.accessToken).sessionId
            application { watchTogetherModule(accountBackend = backend, requireWatchAuthentication = true) }
            val sockets = createClient { install(WebSockets) }

            suspend fun connect(token: String): WebSocketSession =
                sockets.webSocketSession("/watch") { headers.append(HttpHeaders.Authorization, "Bearer $token") }

            val tv = connect(tvAuth.accessToken)
            tv.send("""{"type":"remoteHost"}""")
            val hosting = tv.receiveType("remoteHosting")
            assertTrue(hosting.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "remoteControl" })

            val stranger = connect(strangerAuth.accessToken)
            stranger.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession"}""")
            assertEquals("remote_unavailable", stranger.receiveType("error").string("errorCode"))
            stranger.send("""{"type":"remoteKey","remoteKey":"up"}""")
            assertEquals("remote_not_joined", stranger.receiveType("error").string("errorCode"))

            val phone = connect(phoneAuth.accessToken)
            phone.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession"}""")
            phone.receiveType("remoteJoined")
            assertEquals(1, tv.receiveType("remoteConnected").int("participantCount"))
            phone.send("""{"type":"remoteKey","remoteKey":"left"}""")
            assertEquals("left", tv.receiveType("remoteKey").string("remoteKey"))
            phone.send("""{"type":"remoteText","text":"星际 "}""")
            assertEquals("星际 ", tv.receiveType("remoteText").string("text"))
            phone.send("""{"type":"remoteText","text":""}""")
            assertEquals("", tv.receiveType("remoteText").string("text"))
            phone.send("""{"type":"remoteKey","remoteKey":"power"}""")
            assertEquals("remote_invalid", phone.receiveType("error").string("errorCode"))

            // Nothing goes from the television to a phone, and a remote socket never joins a room.
            tv.send("""{"type":"remoteKey","remoteKey":"up"}""")
            assertEquals("remote_not_joined", tv.receiveType("error").string("errorCode"))
            phone.send("""{"type":"hello","protocolVersion":6,"clientId":"phone","mediaKey":"tmdb:351"}""")
            assertEquals("remote_invalid", phone.receiveType("error").string("errorCode"))
            phone.send("""{"type":"ping","clientSentAtMs":7}""")
            phone.receiveType("pong")

            // A room member never carries a remote either.
            val member = connect(phoneAuth.accessToken)
            member.send("""{"type":"hello","protocolVersion":6,"clientId":"member","mediaKey":"tmdb:351"}""")
            member.receiveType("welcome")
            member.send("""{"type":"remoteHost"}""")
            assertEquals("remote_invalid", member.receiveType("error").string("errorCode"))

            phone.close()
            assertEquals(0, tv.receiveType("remoteDisconnected").int("participantCount"))
            val again = connect(phoneAuth.accessToken)
            again.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession"}""")
            again.receiveType("remoteJoined")
            tv.receiveType("remoteConnected")
            tv.close()
            assertEquals("remote_host_left", again.receiveType("remoteDisconnected").string("errorCode"))
            again.close()
            member.close()
            stranger.close()
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private suspend fun WebSocketSession.receiveType(type: String): JsonObject =
        withTimeout(4_000L) {
            var found: JsonObject? = null
            while (found == null) {
                val payload = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                if (payload.string("type") == type) found = payload
            }
            found
        }
}
