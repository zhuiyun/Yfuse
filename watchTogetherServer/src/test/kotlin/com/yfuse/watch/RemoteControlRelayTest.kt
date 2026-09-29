package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.LoginRequest
import com.yfuse.watch.account.RegisterRequest
import com.yfuse.watch.protocol.WatchProtocol
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
import kotlinx.serialization.json.boolean
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
            relay.join("mallory", "mallory-phone", "tv", "mallory-socket", MALLORY),
        )
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.Unavailable), relay.join("alice", "tv", "tv", "loop", PHONE))
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = true, phone = PHONE),
            relay.join("alice", "phone", "tv", "phone-socket", PHONE),
        )
        // The same socket again stays who it said it was.
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = false, phone = PHONE),
            relay.join("alice", "phone", "tv", "phone-socket", MALLORY),
        )
        assertEquals(RemoteAdmission.Input("tv-socket", PHONE), relay.admitInput("phone-socket", nowMs = 0L))
        // A socket is a television or a phone, never both, and a television sends no input.
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("tv-socket", nowMs = 0L))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("mallory-socket", 0L))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.host("alice", "phone", "phone-socket"))
        assertEquals(
            RemoteAdmission.Refused(RemoteRefusal.WrongRole),
            relay.join("alice", "tv", "tv", "tv-socket", PHONE),
        )
        assertTrue(relay.involves("phone-socket"))
        assertFalse(relay.involves("mallory-socket"))
    }

    @Test
    fun a_television_takes_a_few_phones_and_hears_them_come_and_go() {
        val relay = RemoteControlRelay<String>(maxControllersPerHost = 2)
        relay.host("alice", "tv", "tv-socket")
        relay.join("alice", "phone-1", "tv", "p1", RemotePhone("phone-1"))
        relay.join("alice", "phone-2", "tv", "p2", RemotePhone("phone-2"))
        assertEquals(
            RemoteAdmission.Refused(RemoteRefusal.Busy),
            relay.join("alice", "phone-3", "tv", "p3", RemotePhone("phone-3")),
        )
        assertEquals(RemoteDeparture.PhoneLeft("tv-socket", 1, RemotePhone("phone-1")), relay.depart("p1"))
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 2, fresh = true, phone = RemotePhone("phone-3")),
            relay.join("alice", "phone-3", "tv", "p3", RemotePhone("phone-3")),
        )
        assertEquals(2, relay.phonesOn("alice", "tv"))
        assertEquals(RemoteDeparture.None, relay.depart("never-seen"))
    }

    @Test
    fun a_reconnecting_television_keeps_its_phones_and_its_stale_socket_leaves_quietly() {
        val relay = RemoteControlRelay<String>()
        relay.host("alice", "tv", "old")
        relay.join("alice", "phone", "tv", "p1", PHONE)
        assertEquals(RemoteAdmission.Hosted("old", 1), relay.host("alice", "tv", "new"))
        assertEquals(RemoteAdmission.Input("new", PHONE), relay.admitInput("p1", nowMs = 0L))
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
        relay.join("alice", "phone", "tv", "p1", PHONE)
        repeat(3) { input ->
            assertEquals(RemoteAdmission.Input("tv-again", PHONE), relay.admitInput("p1", nowMs = input.toLong()))
        }
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.RateLimited), relay.admitInput("p1", nowMs = 3L))
        assertEquals(RemoteAdmission.Input("tv-again", PHONE), relay.admitInput("p1", nowMs = 1_000L))
    }

    @Test
    fun only_the_television_lets_a_phone_go_and_the_phone_is_then_no_longer_on_it() {
        val relay = RemoteControlRelay<String>()
        relay.host("alice", "tv", "tv-socket")
        relay.join("alice", "phone-a", "tv", "a1", PHONE)
        relay.join("alice", "phone-b", "tv", "b1", RemotePhone("phone-b"))
        // A phone cannot let another phone go, nor can a socket that hosts nothing.
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.release("b1", PHONE.deviceId))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.release("stranger", PHONE.deviceId))

        assertEquals(RemoteAdmission.Released(listOf("a1"), remaining = 1), relay.release("tv-socket", PHONE.deviceId))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotJoined), relay.admitInput("a1", nowMs = 0L))
        assertFalse(relay.involves("a1"))
        // Its socket closing later tells the television nothing twice.
        assertEquals(RemoteDeparture.None, relay.depart("a1"))
        // Letting go of a phone that is not there is nothing to do.
        assertEquals(RemoteAdmission.Released(emptyList<String>(), remaining = 1), relay.release("tv-socket", "gone"))

        // A television socket that has been replaced lets nothing go; the one replacing it does.
        relay.host("alice", "tv", "tv-new")
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.release("tv-socket", "phone-b"))
        assertEquals(RemoteAdmission.Released(listOf("b1"), remaining = 0), relay.release("tv-new", "phone-b"))
    }

    @Test
    fun a_phone_waits_on_a_television_that_asks_until_that_television_lets_it_in() {
        val relay = RemoteControlRelay<String>()
        relay.host("alice", "tv", "tv-socket", asks = true)
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = true, phone = PHONE, waiting = true),
            relay.join("alice", "phone-a", "tv", "a1", PHONE),
        )
        relay.join("alice", "phone-a", "tv", "a2", PHONE)
        relay.join("alice", "phone-b", "tv", "b1", RemotePhone("phone-b"))
        // A phone cannot let itself or another in, nor can a socket that hosts nothing.
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.admit("a1", PHONE.deviceId))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.admit("stranger", PHONE.deviceId))
        // Every socket of the phone let in hears it, and only those.
        assertEquals(RemoteAdmission.Admitted(listOf("a1", "a2")), relay.admit("tv-socket", PHONE.deviceId))
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 3, fresh = false, phone = PHONE, waiting = false),
            relay.join("alice", "phone-a", "tv", "a1", PHONE),
        )
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 3, fresh = false, phone = RemotePhone("phone-b"), waiting = true),
            relay.join("alice", "phone-b", "tv", "b1", RemotePhone("phone-b")),
        )
        assertEquals(RemoteAdmission.Admitted(emptyList<String>()), relay.admit("tv-socket", "gone"))
        // A television socket that has been replaced lets nobody in; the one replacing it does.
        relay.host("alice", "tv", "tv-new", asks = true)
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.WrongRole), relay.admit("tv-socket", "phone-b"))
        assertEquals(RemoteAdmission.Admitted(listOf("b1")), relay.admit("tv-new", "phone-b"))
    }

    @Test
    fun a_phone_joins_a_television_that_says_nothing_as_it_always_did() {
        val relay = RemoteControlRelay<String>()
        relay.host("alice", "tv", "tv-socket")
        assertEquals(
            RemoteAdmission.Joined("tv-socket", 1, fresh = true, phone = PHONE, waiting = false),
            relay.join("alice", "phone-a", "tv", "a1", PHONE),
        )
    }

    @Test
    fun a_phone_that_names_none_gets_a_stand_in_no_television_will_keep() {
        val first = RemotePhone.unnamed()
        val second = RemotePhone.unnamed()
        assertTrue(first.deviceId != second.deviceId)
        assertTrue(WatchProtocol.isValidRemoteDeviceId(first.deviceId))
        assertFalse(WatchProtocol.isStableRemoteDeviceId(first.deviceId))
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
            assertTrue(hosting.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "remotePairing" })

            val stranger = connect(strangerAuth.accessToken)
            stranger.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession"}""")
            assertEquals("remote_unavailable", stranger.receiveType("error").string("errorCode"))
            stranger.send("""{"type":"remoteKey","remoteKey":"up"}""")
            assertEquals("remote_not_joined", stranger.receiveType("error").string("errorCode"))

            val phone = connect(phoneAuth.accessToken)
            phone.send(
                """{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-a","name":"小米 14"}""",
            )
            phone.receiveType("remoteJoined")
            val connected = tv.receiveType("remoteConnected")
            assertEquals(1, connected.int("participantCount"))
            // The television hears which phone it is, to ask about it by name and to keep it.
            assertEquals("phone-a", connected.string("remoteDeviceId"))
            assertEquals("小米 14", connected.string("name"))
            phone.send("""{"type":"remoteKey","remoteKey":"left"}""")
            val left = tv.receiveType("remoteKey")
            assertEquals("left", left.string("remoteKey"))
            assertEquals("phone-a", left.string("remoteDeviceId"))
            phone.send("""{"type":"remoteText","text":"星际 "}""")
            val typed = tv.receiveType("remoteText")
            assertEquals("星际 ", typed.string("text"))
            assertEquals("phone-a", typed.string("remoteDeviceId"))
            phone.send("""{"type":"remoteText","text":""}""")
            assertEquals("", tv.receiveType("remoteText").string("text"))
            phone.send("""{"type":"remoteKey","remoteKey":"power"}""")
            assertEquals("remote_invalid", phone.receiveType("error").string("errorCode"))
            // A phone does not stamp its own input: the relay does.
            phone.send("""{"type":"remoteKey","remoteKey":"up","remoteDeviceId":"phone-b"}""")
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
            val gone = tv.receiveType("remoteDisconnected")
            assertEquals(0, gone.int("participantCount"))
            assertEquals("phone-a", gone.string("remoteDeviceId"))

            // A phone from before names itself not at all, and gets a stand-in for the connection.
            val older = connect(phoneAuth.accessToken)
            older.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession"}""")
            older.receiveType("remoteJoined")
            val standIn = tv.receiveType("remoteConnected").string("remoteDeviceId")
            assertFalse(WatchProtocol.isStableRemoteDeviceId(standIn))
            // Only a television lets a phone go, and only with a reason the relay knows.
            older.send("""{"type":"remoteRelease","remoteDeviceId":"$standIn"}""")
            assertEquals("remote_invalid", older.receiveType("error").string("errorCode"))
            tv.send("""{"type":"remoteRelease","remoteDeviceId":"$standIn","errorCode":"whatever"}""")
            assertEquals("remote_invalid", tv.receiveType("error").string("errorCode"))
            // The television refuses it: the phone hears why, and its keys go nowhere after.
            tv.send("""{"type":"remoteRelease","remoteDeviceId":"$standIn","errorCode":"remote_refused"}""")
            assertEquals("remote_refused", older.receiveType("remoteDisconnected").string("errorCode"))
            assertEquals(0, tv.receiveType("remoteDisconnected").int("participantCount"))
            older.send("""{"type":"remoteKey","remoteKey":"up"}""")
            assertEquals("remote_not_joined", older.receiveType("error").string("errorCode"))
            // A phone may not claim the relay's kind of id for itself.
            val impostor = connect(phoneAuth.accessToken)
            impostor.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"$standIn"}""")
            assertEquals("remote_invalid", impostor.receiveType("error").string("errorCode"))

            val again = connect(phoneAuth.accessToken)
            again.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-a"}""")
            again.receiveType("remoteJoined")
            tv.receiveType("remoteConnected")
            // Let go without a reason, the phone hears the television let it go.
            tv.send("""{"type":"remoteRelease","remoteDeviceId":"phone-a"}""")
            assertEquals("remote_released", again.receiveType("remoteDisconnected").string("errorCode"))
            tv.receiveType("remoteDisconnected")

            val last = connect(phoneAuth.accessToken)
            last.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-a"}""")
            last.receiveType("remoteJoined")
            tv.receiveType("remoteConnected")
            tv.close()
            assertEquals("remote_host_left", last.receiveType("remoteDisconnected").string("errorCode"))
            last.close()
            again.close()
            older.close()
            impostor.close()
            member.close()
            stranger.close()
        }

    @Test
    fun a_phone_hears_that_it_waits_and_then_that_the_television_let_it_in() =
        testApplication {
            val backend = AccountBackend.inMemoryForTests()
            val phoneAuth = backend.service.register(RegisterRequest("admit-owner", "Watch-Test-42"))
            val tvAuth = backend.service.login(LoginRequest("admit-owner", "Watch-Test-42", deviceName = "客厅电视"))
            val tvSession = backend.service.validateAccessToken(tvAuth.accessToken).sessionId
            application { watchTogetherModule(accountBackend = backend, requireWatchAuthentication = true) }
            val sockets = createClient { install(WebSockets) }

            suspend fun connect(token: String): WebSocketSession =
                sockets.webSocketSession("/watch") { headers.append(HttpHeaders.Authorization, "Bearer $token") }

            val tv = connect(tvAuth.accessToken)
            // What a television says it does is bounded like everything else it sends.
            tv.send("""{"type":"remoteHost","capabilities":["remote pairing"]}""")
            assertEquals("remote_invalid", tv.receiveType("error").string("errorCode"))
            tv.send("""{"type":"remoteHost","capabilities":["remotePairing","somethingLater"]}""")
            tv.receiveType("remoteHosting")

            val phone = connect(phoneAuth.accessToken)
            phone.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-a"}""")
            assertFalse(phone.receiveType("remoteJoined").boolean("ready"))
            tv.receiveType("remoteConnected")
            val other = connect(phoneAuth.accessToken)
            other.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-b"}""")
            other.receiveType("remoteJoined")
            tv.receiveType("remoteConnected")

            // A phone cannot let itself in, nor say more than the television's message holds.
            phone.send("""{"type":"remoteAdmit","remoteDeviceId":"phone-a"}""")
            assertEquals("remote_invalid", phone.receiveType("error").string("errorCode"))
            tv.send("""{"type":"remoteAdmit","remoteDeviceId":"phone-a","errorCode":"remote_refused"}""")
            assertEquals("remote_invalid", tv.receiveType("error").string("errorCode"))
            tv.send("""{"type":"remoteAdmit","remoteDeviceId":"phone-a"}""")
            phone.receiveType("remoteAdmitted")
            // The other phone is not told anything: the next thing it hears is its own answer.
            other.send("""{"type":"ping","clientSentAtMs":7}""")
            assertEquals("pong", other.nextType())

            // A television that says nothing is joined as before.
            tv.close()
            phone.receiveType("remoteDisconnected")
            val older = connect(tvAuth.accessToken)
            older.send("""{"type":"remoteHost"}""")
            older.receiveType("remoteHosting")
            val again = connect(phoneAuth.accessToken)
            again.send("""{"type":"remoteJoin","remoteSessionId":"$tvSession","remoteDeviceId":"phone-a"}""")
            assertFalse("ready" in again.receiveType("remoteJoined"))
            again.close()
            older.close()
            other.close()
            phone.close()
        }

    private companion object {
        val PHONE = RemotePhone("phone-a", "小米 14")
        val MALLORY = RemotePhone("mallory-phone")
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean

    /** The type of the very next message, whatever it is. */
    private suspend fun WebSocketSession.nextType(): String =
        withTimeout(4_000L) {
            Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject.string("type")
        }

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
