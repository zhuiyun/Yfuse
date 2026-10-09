package com.yfuse.core.remote

import com.yfuse.backend.BackendAccess
import com.yfuse.core.account.AccountApiException
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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

    /** A relay that passes the television's answer on, joining a phone to a television that asks. */
    private val asking =
        joined.copy(
            capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL, WatchProtocol.CAPABILITY_REMOTE_PAIRING),
            ready = false,
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
            // It says which phone it is, so the television can ask about it by name.
            assertEquals("phone-a", join.remoteDeviceId)
            assertEquals("测试手机", join.name)
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

    @Test
    fun a_refresh_outage_is_retryable_and_a_manual_retry_can_join() =
        runTest {
            val relay = FakeRelay()
            var token: String? = null
            var refreshes = 0
            val client =
                PhoneRemoteClient(
                    accessToken = { token },
                    refreshAccessToken = {
                        refreshes++
                        if (refreshes == 1) error("account service unavailable")
                        token = "renewed"
                        token
                    },
                    connector = relay,
                    scope = backgroundScope,
                )
            client.connect("tv-session")
            runCurrent()
            assertEquals(PhoneRemoteState.Failed("登录服务暂时不可用，请重试"), client.state.value)
            assertEquals(0, relay.connects)

            client.connect("tv-session")
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(joined)
            runCurrent()
            assertEquals(PhoneRemoteState.Connected, client.state.value)
            assertEquals(2, refreshes)
        }

    @Test
    fun an_expired_refresh_requires_sign_in() =
        runTest {
            val relay = FakeRelay()
            val client =
                PhoneRemoteClient(
                    accessToken = { null },
                    refreshAccessToken = { null },
                    connector = relay,
                    scope = backgroundScope,
                )
            client.connect("tv-session")
            runCurrent()
            assertEquals(
                PhoneRemoteState.Failed("登录状态已失效，请重新登录鱼服账号", retryable = false),
                client.state.value,
            )
            assertEquals(0, relay.connects)
        }

    @Test
    fun a_rejected_refresh_token_requires_sign_in_while_a_server_error_can_retry() =
        runTest {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable)) {
                val relay = FakeRelay()
                val client =
                    PhoneRemoteClient(
                        accessToken = { null },
                        refreshAccessToken = {
                            throw AccountApiException("refresh_failed", "refresh failed", status)
                        },
                        connector = relay,
                        scope = backgroundScope,
                    )
                client.connect("tv-session")
                runCurrent()
                val expected =
                    if (status == HttpStatusCode.Unauthorized) {
                        PhoneRemoteState.Failed("登录状态已失效，请重新登录鱼服账号", retryable = false)
                    } else {
                        PhoneRemoteState.Failed("登录服务暂时不可用，请重试")
                    }
                assertEquals(expected, client.state.value)
                assertEquals(0, relay.connects)
                client.close()
            }
        }

    @Test
    fun closing_during_refresh_cancels_it_without_publishing_failure() =
        runTest {
            val relay = FakeRelay()
            val refresh = CompletableDeferred<String?>()
            var refreshFinished = false
            val client =
                PhoneRemoteClient(
                    accessToken = { null },
                    refreshAccessToken = {
                        try {
                            refresh.await()
                        } finally {
                            refreshFinished = true
                        }
                    },
                    connector = relay,
                    scope = backgroundScope,
                )
            client.connect("tv-session")
            runCurrent()
            assertFalse(refreshFinished)
            client.close()
            runCurrent()
            assertTrue(refreshFinished)
            assertEquals(PhoneRemoteState.Idle, client.state.value)
            assertEquals(0, relay.connects)
        }

    @Test
    fun disabledBackendDoesNotReadTokensOrConnectWhenAskedToJoin() =
        runTest {
            var tokenReads = 0
            val relay = FakeRelay()
            val client =
                PhoneRemoteClient(
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
            client.connect("tv-session")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, tokenReads)
            assertEquals(0, relay.connects)
            assertEquals(false, (client.state.value as PhoneRemoteState.Failed).retryable)
        }

    @Test
    fun a_phone_waits_while_the_television_asks_and_sends_nothing_until_let_in() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.connect("tv-session")
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(asking)
            client.state.first { it == PhoneRemoteState.Waiting }
            assertFalse(client.sendKey(RemoteControlKey.Up), "the television would drop it")
            client.updateText("星际")
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS * 2)
            runCurrent()
            assertTrue(socket.sent.tryReceive().isFailure, "nothing is sent while the television asks")

            socket.push(WatchWireMessage(type = "remoteAdmitted"))
            client.state.first { it == PhoneRemoteState.Connected }
            assertTrue(client.sendKey(RemoteControlKey.Left))
            assertEquals("left", socket.sent.receive().remoteKey)
            // What was typed while waiting follows, once.
            advanceTimeBy(REMOTE_TEXT_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(WatchWireMessage(type = "remoteText", text = "星际"), socket.sent.receive())
        }

    @Test
    fun a_refused_phone_stops_waiting_and_says_so() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.connect("tv-session")
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(asking)
            client.state.first { it == PhoneRemoteState.Waiting }
            socket.push(
                WatchWireMessage(
                    type = "remoteDisconnected",
                    message = "电视拒绝了这部手机的遥控",
                    errorCode = WatchProtocol.REMOTE_REFUSED_CODE,
                ),
            )
            runCurrent()
            assertEquals(PhoneRemoteState.Failed("电视拒绝了这部手机的遥控", retryable = true), client.state.value)
        }

    @Test
    fun a_phone_never_waits_on_a_relay_that_cannot_tell_it_it_was_let_in() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.connect("tv-session")
            val socket = relay.sessions.receive()
            socket.sent.receive()
            // Whatever it sets, a relay without pairing never passes the television's answer on.
            socket.push(joined.copy(ready = false))
            client.state.first { it == PhoneRemoteState.Connected }
            assertTrue(client.sendKey(RemoteControlKey.Center))
        }

    @Test
    fun a_phone_that_cannot_say_who_it_is_still_joins_unnamed() =
        runTest {
            val relay = FakeRelay()
            val broken = client(relay, backgroundScope, identity = { error("no install id yet") })
            broken.connect("tv-session")
            val unnamed = relay.sessions.receive()
            val join = unnamed.sent.receive()
            assertEquals("remoteJoin", join.type)
            assertEquals(null, join.remoteDeviceId)

            // Nor does it name itself with an id the relay would refuse the whole join over.
            val impostor = client(relay, backgroundScope, identity = { RemotePhoneIdentity("~made-up", null) })
            impostor.connect("tv-session")
            val madeUp = relay.sessions.receive()
            assertEquals(null, madeUp.sent.receive().remoteDeviceId)
        }

    private fun client(
        relay: FakeRelay,
        scope: CoroutineScope,
        identity: () -> RemotePhoneIdentity? = { RemotePhoneIdentity("phone-a", "测试手机") },
    ) = PhoneRemoteClient(
        accessToken = { "token" },
        refreshAccessToken = { null },
        url = "wss://relay.test/watch",
        connector = relay,
        retryDelayMs = { RETRY_MS },
        scope = scope,
        identity = identity,
    )

    private companion object {
        const val RETRY_MS = 1_000L
    }
}
