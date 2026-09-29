package com.yfuse.core.remote

import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlHostTest {
    private val hosting =
        WatchWireMessage(
            type = "remoteHosting",
            capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL, WatchProtocol.CAPABILITY_REMOTE_PAIRING),
        )

    /** A relay from before phones were named: it says only how many are on. */
    private val unnamedHosting =
        WatchWireMessage(
            type = "remoteHosting",
            capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL),
        )

    @Test
    fun hosts_while_active_and_replays_what_a_phone_it_let_in_sends() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val events = mutableListOf<RemoteControlEvent>()
            backgroundScope.launch { host.events.collect { events += it } }
            host.setActive(true)
            val socket = relay.sessions.receive()
            val hostMessage = socket.sent.receive()
            assertEquals("remoteHost", hostMessage.type)
            // It says it asks, so a relay that knows has each phone wait for the answer.
            assertEquals(listOf(WatchProtocol.CAPABILITY_REMOTE_PAIRING), hostMessage.capabilities)
            socket.push(hosting)
            host.hosting.first { it }
            socket.push(connected("phone-a", "小米 14", phones = 1))
            // Waiting to be asked about: nothing it sends counts, and nothing is kept for later.
            socket.push(key("up", "phone-a"))
            runCurrent()
            assertEquals(listOf(RemoteControlPhone("phone-a", "小米 14", allowed = false)), host.phones.value)
            assertTrue(socket.sent.tryReceive().isFailure, "nobody has been let in yet")
            host.allow("phone-a")
            // The relay hears it, and tells that phone to stop waiting.
            assertEquals(admit("phone-a"), socket.sent.receive())
            socket.push(key("up", "phone-a"))
            socket.push(key("power", "phone-a"))
            socket.push(WatchWireMessage(type = "remoteText", text = "星际 ", remoteDeviceId = "phone-a"))
            socket.push(WatchWireMessage(type = "remoteText", text = "bad\ntext", remoteDeviceId = "phone-a"))
            socket.push(disconnected("phone-a", phones = 0))
            runCurrent()
            assertEquals(listOf(RemoteControlEvent.Key(RemoteControlKey.Up), RemoteControlEvent.Text("星际 ")), events)
            assertEquals(emptyList(), host.phones.value)
            host.setActive(false)
            runCurrent()
            assertFalse(host.hosting.value)
        }

    @Test
    fun a_refused_phone_is_let_go_and_a_trusted_one_is_let_in_at_once() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope, trusted = { it == "phone-a" })
            val events = mutableListOf<RemoteControlEvent>()
            backgroundScope.launch { host.events.collect { events += it } }
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(hosting)
            host.hosting.first { it }
            socket.push(connected("phone-a", "小米 14", phones = 1))
            socket.push(connected("phone-b", "陌生手机", phones = 2))
            socket.push(key("left", "phone-a"))
            socket.push(key("right", "phone-b"))
            runCurrent()
            assertEquals(listOf<RemoteControlEvent>(RemoteControlEvent.Key(RemoteControlKey.Left)), events)
            assertEquals(listOf(true, false), host.phones.value.map { it.allowed })
            // The trusted phone is let in as it connects, and hears so; the other one waits on.
            assertEquals(admit("phone-a"), socket.sent.receive())
            assertTrue(socket.sent.tryReceive().isFailure)

            host.release("phone-b")
            val refusal = socket.sent.receive()
            assertEquals("remoteRelease", refusal.type)
            assertEquals("phone-b", refusal.remoteDeviceId)
            assertEquals(WatchProtocol.REMOTE_REFUSED_CODE, refusal.errorCode)

            // 断开 lets the trusted phone go too, as a disconnection rather than a refusal.
            host.releaseAll()
            val disconnection = socket.sent.receive()
            assertEquals("phone-a", disconnection.remoteDeviceId)
            assertNull(disconnection.errorCode)
            runCurrent()
            assertEquals(emptyList(), host.phones.value)
        }

    @Test
    fun allowed_once_holds_while_the_television_hosts_and_a_stand_in_is_never_trusted() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope, trusted = { true })
            host.setActive(true)
            val first = relay.sessions.receive()
            first.sent.receive()
            first.push(hosting)
            host.hosting.first { it }
            // A phone from before phones named themselves is given a stand-in, which no trust covers.
            first.push(connected("~stand-in", null, phones = 1))
            runCurrent()
            assertFalse(host.onlyPhone().allowed)
            assertFalse(host.onlyPhone().rememberable)
            host.allow("~stand-in")
            assertEquals(admit("~stand-in"), first.sent.receive())
            // Its network blinks: it comes back under the same id and is not asked about again,
            // and its new connection is told so at once.
            first.push(disconnected("~stand-in", phones = 0))
            first.push(connected("~stand-in", null, phones = 1))
            runCurrent()
            assertTrue(host.onlyPhone().allowed)
            assertEquals(admit("~stand-in"), first.sent.receive())

            // Leaving the foreground ends that; the relay tells every phone its television left.
            host.setActive(false)
            runCurrent()
            assertEquals(emptyList(), host.phones.value)
            host.setActive(true)
            val second = relay.sessions.receive()
            second.sent.receive()
            second.push(hosting)
            second.push(connected("~stand-in", null, phones = 1))
            runCurrent()
            assertFalse(host.onlyPhone().allowed)
        }

    @Test
    fun a_phone_heard_before_it_was_seen_to_connect_is_asked_about_like_a_newcomer() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val events = mutableListOf<RemoteControlEvent>()
            backgroundScope.launch { host.events.collect { events += it } }
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            // The television hosted again and the phone stayed on through it.
            socket.push(hosting.copy(participantCount = 1))
            host.hosting.first { it }
            socket.push(key("down", "phone-c"))
            runCurrent()
            assertEquals(emptyList(), events)
            assertEquals(listOf(RemoteControlPhone("phone-c", null, allowed = false)), host.phones.value)
        }

    @Test
    fun on_a_relay_that_names_no_phones_every_newcomer_waits_and_letting_go_hosts_afresh() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope, trusted = { true })
            val events = mutableListOf<RemoteControlEvent>()
            backgroundScope.launch { host.events.collect { events += it } }
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(unnamedHosting)
            host.hosting.first { it }
            socket.push(WatchWireMessage(type = "remoteConnected", participantCount = 1))
            socket.push(WatchWireMessage(type = "remoteKey", remoteKey = "up"))
            runCurrent()
            val unnamed = host.onlyPhone()
            assertFalse(unnamed.allowed)
            assertFalse(unnamed.rememberable)
            host.allow(unnamed.deviceId)
            runCurrent()
            // Such a relay never made the phone wait, and has nobody to tell.
            assertTrue(socket.sent.tryReceive().isFailure)
            socket.push(WatchWireMessage(type = "remoteKey", remoteKey = "up"))
            // A second phone's keys could not be told from the first's: both wait again.
            socket.push(WatchWireMessage(type = "remoteConnected", participantCount = 2))
            socket.push(WatchWireMessage(type = "remoteKey", remoteKey = "down"))
            runCurrent()
            assertEquals(listOf<RemoteControlEvent>(RemoteControlEvent.Key(RemoteControlKey.Up)), events)

            host.release(unnamed.deviceId)
            runCurrent()
            assertFalse(host.hosting.value, "the socket is left, which the relay tells every phone")
            advanceTimeBy(RETRY_MS + 1)
            runCurrent()
            assertEquals(2, relay.connects)
            val again = relay.sessions.receive()
            assertEquals("remoteHost", again.sent.receive().type)
            again.push(unnamedHosting.copy(participantCount = 0))
            runCurrent()
            assertEquals(emptyList(), host.phones.value)
        }

    @Test
    fun a_dropped_socket_is_hosted_again_after_a_pause() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            host.setActive(true)
            val first = relay.sessions.receive()
            first.sent.receive()
            first.push(hosting)
            host.hosting.first { it }
            first.push(null)
            runCurrent()
            assertFalse(host.hosting.value)
            advanceTimeBy(RETRY_MS - 1)
            runCurrent()
            assertEquals(1, relay.connects)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(2, relay.connects)
        }

    @Test
    fun a_relay_without_remote_control_is_left_alone_until_the_next_activation() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(WatchWireMessage(type = "error", message = "消息类型无效", errorCode = "message_type_invalid"))
            advanceTimeBy(RETRY_MS * 10)
            runCurrent()
            assertEquals(1, relay.connects)
            host.setActive(false)
            runCurrent()
            host.setActive(true)
            relay.sessions.receive()
            assertEquals(2, relay.connects)
        }

    @Test
    fun signing_out_stops_hosting() =
        runTest {
            val relay = FakeRelay()
            val signedIn = MutableStateFlow(true)
            val host = host(relay, backgroundScope, signedIn)
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(hosting)
            host.hosting.first { it }
            signedIn.value = false
            runCurrent()
            assertFalse(host.hosting.value)
            advanceTimeBy(RETRY_MS * 10)
            runCurrent()
            assertEquals(1, relay.connects)
        }

    @Test
    fun a_television_asks_for_a_server_only_on_a_relay_that_carries_it() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            host.setActive(true)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(hosting)
            host.hosting.first { it }
            assertFalse(host.signInAvailable.value)
            host.askForServer()
            runCurrent()
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
            assertTrue(socket.sent.tryReceive().isFailure, "nothing is asked of a relay that cannot answer")

            // One that carries it outside TLS is not asked either: a session would cross it in the clear.
            val plainRelay = FakeRelay()
            val plain = host(plainRelay, backgroundScope, url = "ws://relay.test/watch")
            plain.setActive(true)
            val plainSocket = plainRelay.sessions.receive()
            plainSocket.sent.receive()
            plainSocket.push(signingInHosting)
            plain.hosting.first { it }
            assertFalse(plain.signInAvailable.value)
            plain.askForServer()
            runCurrent()
            assertEquals(RemoteSignInRequest.Idle, plain.signIn.value)
            assertTrue(plainSocket.sent.tryReceive().isFailure)
        }

    @Test
    fun a_phone_offers_a_server_and_its_session_is_taken_once_for_exactly_what_was_shown() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val handed = mutableListOf<RemoteSignInServer>()
            backgroundScope.launch { host.handedServers.collect { handed += it } }
            val socket = signingInSocket(relay, host)
            host.askForServer()
            assertEquals(WatchWireMessage(type = "remoteSignInAsk"), socket.sent.receive())
            assertEquals(RemoteSignInRequest.Waiting, host.signIn.value)
            assertTrue(host.signIn.value.asking)

            socket.push(signInOffer("phone-a"))
            runCurrent()
            assertEquals(RemoteSignInRequest.Offered("phone-a", "小米 14", SIGN_IN.summary), host.signIn.value)
            // Nothing but the session for what was shown, from the phone that showed it, is taken.
            socket.push(signInSend("phone-b", SIGN_IN))
            socket.push(signInSend("phone-a", SIGN_IN.copy(serverName = "另一台")))
            socket.push(signInSend("phone-a", SIGN_IN.summary))
            runCurrent()
            assertTrue(host.signIn.value is RemoteSignInRequest.Offered)
            assertEquals(emptyList(), handed)

            socket.push(signInSend("phone-a", SIGN_IN))
            runCurrent()
            assertEquals(RemoteSignInRequest.Receiving("小米 14", SIGN_IN.summary), host.signIn.value)
            assertFalse(host.signIn.value.asking)
            assertEquals(listOf(SIGN_IN), handed)
            // Once: the same session again is not taken twice.
            socket.push(signInSend("phone-a", SIGN_IN))
            runCurrent()
            assertEquals(listOf(SIGN_IN), handed)

            host.finishSignIn(saved = true)
            assertEquals(WatchWireMessage(type = "remoteSignInEnd"), socket.sent.receive())
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
        }

    @Test
    fun a_phone_that_leaves_leaves_the_television_waiting_and_cancelling_tells_the_relay() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val socket = signingInSocket(relay, host)
            host.askForServer()
            socket.sent.receive()
            socket.push(signInOffer("phone-a"))
            socket.push(WatchWireMessage(type = "remoteSignInWithdrawn", remoteDeviceId = "phone-b"))
            runCurrent()
            assertTrue(host.signIn.value is RemoteSignInRequest.Offered, "another phone leaving changes nothing")
            socket.push(WatchWireMessage(type = "remoteSignInWithdrawn", remoteDeviceId = "phone-a"))
            runCurrent()
            assertEquals(RemoteSignInRequest.Waiting, host.signIn.value)

            host.cancelSignIn()
            assertEquals(cancelled, socket.sent.receive())
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
            // An offer for an ask this television already dropped is ended at the relay too.
            socket.push(signInOffer("phone-a"))
            assertEquals(cancelled, socket.sent.receive())
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
        }

    @Test
    fun an_ask_gives_up_by_itself_and_a_session_never_said_saved_is_reported_unsaved() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val socket = signingInSocket(relay, host)
            host.askForServer()
            socket.sent.receive()
            // A phone coming and going does not restart the clock.
            advanceTimeBy(WatchProtocol.REMOTE_SIGN_IN_ASK_MS / 2)
            socket.push(signInOffer("phone-a"))
            advanceTimeBy(WatchProtocol.REMOTE_SIGN_IN_ASK_MS / 2 + 1)
            runCurrent()
            assertEquals(RemoteSignInRequest.Expired, host.signIn.value)
            assertEquals(cancelled, socket.sent.receive())
            host.cancelSignIn()
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
            assertTrue(socket.sent.tryReceive().isFailure, "the relay already heard it end")

            host.askForServer()
            socket.sent.receive()
            socket.push(signInOffer("phone-a"))
            socket.push(signInSend("phone-a", SIGN_IN))
            runCurrent()
            assertTrue(host.signIn.value is RemoteSignInRequest.Receiving)
            // Leaving the form does not take back a session that has arrived.
            host.cancelSignIn()
            assertTrue(host.signIn.value is RemoteSignInRequest.Receiving)
            advanceTimeBy(REMOTE_SIGN_IN_RECEIVE_MS + 1)
            runCurrent()
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
            assertEquals(
                WatchWireMessage(type = "remoteSignInEnd", errorCode = WatchProtocol.REMOTE_SIGN_IN_FAILED_CODE),
                socket.sent.receive(),
            )
        }

    @Test
    fun a_television_that_reconnects_while_asking_asks_again_and_one_that_stops_hosting_stops_asking() =
        runTest {
            val relay = FakeRelay()
            val host = host(relay, backgroundScope)
            val first = signingInSocket(relay, host)
            host.askForServer()
            first.sent.receive()
            first.push(signInOffer("phone-a"))
            first.push(null)
            runCurrent()
            assertFalse(host.signInAvailable.value)
            // Still asking, and still showing what the phone offered, while it reconnects.
            assertTrue(host.signIn.value is RemoteSignInRequest.Offered)
            advanceTimeBy(RETRY_MS + 1)
            val again = relay.sessions.receive()
            assertEquals("remoteHost", again.sent.receive().type)
            again.push(signingInHosting)
            assertEquals(WatchWireMessage(type = "remoteSignInAsk"), again.sent.receive())

            host.setActive(false)
            runCurrent()
            assertEquals(RemoteSignInRequest.Idle, host.signIn.value)
        }

    private fun host(
        relay: FakeRelay,
        scope: CoroutineScope,
        signedIn: MutableStateFlow<Boolean> = MutableStateFlow(true),
        trusted: (String) -> Boolean = { false },
        url: String = "wss://relay.test/watch",
    ) = RemoteControlHost(
        signedIn = signedIn,
        accessToken = { "token" },
        refreshAccessToken = { null },
        trusted = trusted,
        url = url,
        connector = relay,
        retryDelayMs = { RETRY_MS },
        scope = scope,
    )

    private fun RemoteControlHost.onlyPhone(): RemoteControlPhone = phones.value.single()

    private fun connected(
        deviceId: String,
        name: String?,
        phones: Int,
    ) = WatchWireMessage(type = "remoteConnected", participantCount = phones, remoteDeviceId = deviceId, name = name)

    private fun disconnected(
        deviceId: String,
        phones: Int,
    ) = WatchWireMessage(type = "remoteDisconnected", participantCount = phones, remoteDeviceId = deviceId)

    private fun key(
        wireName: String,
        deviceId: String,
    ) = WatchWireMessage(type = "remoteKey", remoteKey = wireName, remoteDeviceId = deviceId)

    private fun admit(deviceId: String) = WatchWireMessage(type = "remoteAdmit", remoteDeviceId = deviceId)

    /** A relay with 用手机登录 as well. */
    private val signingInHosting =
        hosting.copy(capabilities = hosting.capabilities.orEmpty() + WatchProtocol.CAPABILITY_REMOTE_SIGN_IN)

    private val cancelled =
        WatchWireMessage(type = "remoteSignInEnd", errorCode = WatchProtocol.REMOTE_SIGN_IN_CANCELLED_CODE)

    /** The television hosting, on a relay that carries 用手机登录. */
    private suspend fun TestScope.signingInSocket(
        relay: FakeRelay,
        host: RemoteControlHost,
    ): FakeRelaySocket {
        host.setActive(true)
        val socket = relay.sessions.receive()
        socket.sent.receive()
        socket.push(signingInHosting)
        host.signInAvailable.first { it }
        runCurrent()
        return socket
    }

    private fun signInOffer(deviceId: String) =
        WatchWireMessage(
            type = "remoteSignInOffer",
            remoteDeviceId = deviceId,
            name = "小米 14",
            signInServer = SIGN_IN.summary,
        )

    private fun signInSend(
        deviceId: String,
        server: RemoteSignInServer,
    ) = WatchWireMessage(type = "remoteSignInSend", remoteDeviceId = deviceId, signInServer = server)

    private companion object {
        const val RETRY_MS = 5_000L
        val SIGN_IN =
            RemoteSignInServer(
                kind = "Emby",
                serverName = "家里的 Emby",
                baseUrl = "http://192.168.1.8:8096",
                userName = "alice",
                userId = "u1",
                accessToken = "handed-token",
            )
    }
}

/** Hands each opened socket to the test, which then plays the relay's part on it. */
internal class FakeRelay : RemoteRelayConnector {
    val sessions = Channel<FakeRelaySocket>(Channel.UNLIMITED)
    var connects = 0
        private set

    override suspend fun connect(
        url: String,
        accessToken: String,
        session: suspend (RemoteRelayChannel) -> Unit,
    ) {
        connects++
        val socket = FakeRelaySocket()
        sessions.send(socket)
        session(socket)
    }
}

internal class FakeRelaySocket : RemoteRelayChannel {
    val sent = Channel<WatchWireMessage>(Channel.UNLIMITED)
    private val inbox = Channel<WatchWireMessage?>(Channel.UNLIMITED)

    override suspend fun send(message: WatchWireMessage) {
        sent.send(message)
    }

    override suspend fun receive(): WatchWireMessage? = inbox.receive()

    /** A message from the relay; null closes the socket. */
    fun push(message: WatchWireMessage?) {
        inbox.trySend(message)
    }
}
