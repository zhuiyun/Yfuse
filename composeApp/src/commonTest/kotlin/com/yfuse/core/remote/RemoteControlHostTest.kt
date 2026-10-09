package com.yfuse.core.remote

import com.yfuse.backend.BackendAccess
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertTrue

class RemoteControlHostTest {
    private val hosting =
        WatchWireMessage(
            type = "remoteHosting",
            capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL),
        )

    @Test
    fun hosts_while_active_and_replays_what_phones_send() =
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
            socket.push(WatchWireMessage(type = "remoteConnected", participantCount = 1))
            socket.push(WatchWireMessage(type = "remoteKey", remoteKey = "up"))
            socket.push(WatchWireMessage(type = "remoteKey", remoteKey = "power"))
            socket.push(WatchWireMessage(type = "remoteText", text = "星际 "))
            socket.push(WatchWireMessage(type = "remoteText", text = "bad\ntext"))
            socket.push(WatchWireMessage(type = "remoteDisconnected", participantCount = 0))
            runCurrent()
            assertEquals(
                listOf(
                    RemoteControlEvent.Phones(phones = 1, joined = true),
                    RemoteControlEvent.Key(RemoteControlKey.Up),
                    RemoteControlEvent.Text("星际 "),
                    RemoteControlEvent.Phones(phones = 0, joined = false),
                ),
                events,
            )
            host.setActive(false)
            runCurrent()
            assertFalse(host.hosting.value)
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
    fun a_refresh_outage_retries_and_keeps_the_lifecycle_observer_alive() =
        runTest {
            val relay = FakeRelay()
            var token: String? = null
            var refreshes = 0
            val host =
                RemoteControlHost(
                    signedIn = MutableStateFlow(true),
                    accessToken = { token },
                    refreshAccessToken = {
                        refreshes++
                        if (refreshes == 1) error("account service unavailable")
                        token = "renewed"
                        token
                    },
                    connector = relay,
                    retryDelayMs = { RETRY_MS },
                    scope = backgroundScope,
                )
            host.setActive(true)
            runCurrent()
            assertEquals(1, refreshes)
            assertFalse(host.hosting.value)
            advanceTimeBy(RETRY_MS - 1)
            runCurrent()
            assertEquals(1, refreshes)
            advanceTimeBy(1)
            runCurrent()
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(hosting)
            runCurrent()
            assertTrue(host.hosting.value)
            assertEquals(2, refreshes)

            host.setActive(false)
            runCurrent()
            assertFalse(host.hosting.value)
            host.setActive(true)
            runCurrent()
            assertEquals(2, relay.connects)
        }

    @Test
    fun an_expired_refresh_stops_retrying_until_reactivated() =
        runTest {
            var refreshes = 0
            val host =
                RemoteControlHost(
                    signedIn = MutableStateFlow(true),
                    accessToken = { null },
                    refreshAccessToken = {
                        refreshes++
                        null
                    },
                    retryDelayMs = { RETRY_MS },
                    scope = backgroundScope,
                )
            host.setActive(true)
            advanceTimeBy(RETRY_MS * 10)
            runCurrent()
            assertEquals(1, refreshes)
            assertFalse(host.hosting.value)
            host.setActive(false)
            runCurrent()
            host.setActive(true)
            runCurrent()
            assertEquals(2, refreshes)
        }

    @Test
    fun deactivating_during_refresh_cancels_it_without_retrying() =
        runTest {
            val relay = FakeRelay()
            val refresh = CompletableDeferred<String?>()
            var refreshes = 0
            var refreshFinished = false
            val host =
                RemoteControlHost(
                    signedIn = MutableStateFlow(true),
                    accessToken = { null },
                    refreshAccessToken = {
                        refreshes++
                        try {
                            refresh.await()
                        } finally {
                            refreshFinished = true
                        }
                    },
                    connector = relay,
                    retryDelayMs = { RETRY_MS },
                    scope = backgroundScope,
                )
            host.setActive(true)
            runCurrent()
            host.setActive(false)
            advanceTimeBy(RETRY_MS * 10)
            runCurrent()
            assertTrue(refreshFinished)
            assertEquals(1, refreshes)
            assertEquals(0, relay.connects)
            assertFalse(host.hosting.value)
        }

    @Test
    fun disabledBackendDoesNotReadTokensOrHostEvenWhenSignedInAndActive() =
        runTest {
            var tokenReads = 0
            val relay = FakeRelay()
            val host =
                RemoteControlHost(
                    signedIn = MutableStateFlow(true),
                    accessToken = {
                        tokenReads++
                        "token"
                    },
                    refreshAccessToken = {
                        tokenReads++
                        "token"
                    },
                    connector = relay,
                    scope = backgroundScope,
                    backendAccess = BackendAccess(enabled = false),
                )
            host.setActive(true)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, tokenReads)
            assertEquals(0, relay.connects)
            assertFalse(host.hosting.value)
        }

    private fun host(
        relay: FakeRelay,
        scope: CoroutineScope,
        signedIn: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = RemoteControlHost(
        signedIn = signedIn,
        accessToken = { "token" },
        refreshAccessToken = { null },
        url = "wss://relay.test/watch",
        connector = relay,
        retryDelayMs = { RETRY_MS },
        scope = scope,
    )

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
