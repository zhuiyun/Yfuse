package com.yfuse.core.remote

import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneRemoteClientTest {
    private val joined =
        WatchWireMessage(
            type = "remoteJoined",
            capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL),
            participantCount = 1,
        )

    @Test
    fun joins_then_sends_keys_and_only_the_settled_text() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            assertFalse(client.sendKey(RemoteControlKey.Up))
            client.connect("tv-session")
            val socket = relay.sessions.receive()
            val join = socket.sent.receive()
            assertEquals("remoteJoin", join.type)
            assertEquals("tv-session", join.remoteSessionId)
            assertFalse(client.sendKey(RemoteControlKey.Up), "nothing is sent before the relay has paired")
            socket.push(joined)
            client.state.first { it == PhoneRemoteState.Connected }
            assertTrue(client.sendKey(RemoteControlKey.Left))
            assertEquals("left", socket.sent.receive().remoteKey)

            client.updateText("星")
            client.updateText("星际")
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS - 1)
            runCurrent()
            assertTrue(socket.sent.tryReceive().isFailure)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(WatchWireMessage(type = "remoteText", text = "星际"), socket.sent.receive())
            client.updateText("bad\ntext")
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS * 2)
            runCurrent()
            assertTrue(socket.sent.tryReceive().isFailure, "a field the relay would refuse is never sent")

            client.close()
            assertEquals(PhoneRemoteState.Idle, client.state.value)
        }

    @Test
    fun a_dropped_socket_rejoins_and_sends_the_field_again() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.connect("tv-session")
            val first = relay.sessions.receive()
            first.sent.receive()
            first.push(joined)
            client.state.first { it == PhoneRemoteState.Connected }
            client.updateText("abc")
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals("abc", first.sent.receive().text)

            first.push(null)
            runCurrent()
            assertEquals(PhoneRemoteState.Connecting, client.state.value)
            assertFalse(client.sendKey(RemoteControlKey.Center))
            advanceTimeBy(RETRY_MS + 1)
            val again = relay.sessions.receive()
            assertEquals("remoteJoin", again.sent.receive().type)
            again.push(joined)
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals("abc", again.sent.receive().text)
        }

    @Test
    fun refusals_say_why_and_whether_trying_again_can_help() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)

            client.connect("tv-session")
            val offline = relay.sessions.receive()
            offline.sent.receive()
            offline.push(
                WatchWireMessage(type = "error", message = "电视不在线或未开启手机遥控", errorCode = "remote_unavailable"),
            )
            runCurrent()
            assertEquals(PhoneRemoteState.Failed("电视不在线或未开启手机遥控", retryable = true), client.state.value)

            client.connect("tv-session")
            val oldRelay = relay.sessions.receive()
            oldRelay.sent.receive()
            oldRelay.push(WatchWireMessage(type = "error", message = "消息类型无效", errorCode = "message_type_invalid"))
            runCurrent()
            assertEquals(false, (client.state.value as PhoneRemoteState.Failed).retryable)

            client.connect("tv-session")
            val left = relay.sessions.receive()
            left.sent.receive()
            left.push(joined)
            client.state.first { it == PhoneRemoteState.Connected }
            left.push(
                WatchWireMessage(type = "remoteDisconnected", message = "电视已断开手机遥控", errorCode = "remote_host_left"),
            )
            runCurrent()
            assertEquals(PhoneRemoteState.Failed("电视已断开手机遥控", retryable = true), client.state.value)
            assertEquals(3, relay.connects)
        }

    private fun client(
        relay: FakeRelay,
        scope: CoroutineScope,
    ) = PhoneRemoteClient(
        accessToken = { "token" },
        refreshAccessToken = { null },
        url = "wss://relay.test/watch",
        connector = relay,
        retryDelayMs = { RETRY_MS },
        scope = scope,
    )

    private companion object {
        const val RETRY_MS = 1_000L
    }
}
