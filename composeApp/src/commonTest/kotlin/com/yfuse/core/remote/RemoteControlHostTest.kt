package com.yfuse.core.remote

import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            assertEquals("remoteHost", socket.sent.receive().type)
            socket.push(hosting)
            host.hosting.first { it }
            socket.push(connected("phone-a", "小米 14", phones = 1))
            // Waiting to be asked about: nothing it sends counts, and nothing is kept for later.
            socket.push(key("up", "phone-a"))
            runCurrent()
            assertEquals(listOf(RemoteControlPhone("phone-a", "小米 14", allowed = false)), host.phones.value)
            host.allow("phone-a")
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
            // Its network blinks: it comes back under the same id and is not asked about again.
            first.push(disconnected("~stand-in", phones = 0))
            first.push(connected("~stand-in", null, phones = 1))
            runCurrent()
            assertTrue(host.onlyPhone().allowed)

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

    private fun host(
        relay: FakeRelay,
        scope: CoroutineScope,
        signedIn: MutableStateFlow<Boolean> = MutableStateFlow(true),
        trusted: (String) -> Boolean = { false },
    ) = RemoteControlHost(
        signedIn = signedIn,
        accessToken = { "token" },
        refreshAccessToken = { null },
        trusted = trusted,
        url = "wss://relay.test/watch",
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

    private companion object {
        const val RETRY_MS = 5_000L
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
