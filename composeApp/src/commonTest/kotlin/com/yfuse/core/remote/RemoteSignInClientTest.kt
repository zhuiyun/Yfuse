package com.yfuse.core.remote

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSignInClientTest {
    @Test
    fun a_server_is_offered_without_its_session_which_goes_once_and_only_after_send() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            assertTrue(client.offer("tv-session", SAVED))
            assertEquals(RemoteSignInState.Offering, client.state.value)
            val socket = relay.sessions.receive()
            assertEquals(
                WatchWireMessage(
                    type = "remoteSignInOffer",
                    remoteSessionId = "tv-session",
                    remoteDeviceId = "phone-a",
                    name = "测试手机",
                    signInServer = HANDED.summary,
                ),
                socket.sent.receive(),
            )

            socket.push(offered)
            runCurrent()
            assertEquals(RemoteSignInState.Confirming, client.state.value)
            assertTrue(socket.sent.tryReceive().isFailure, "nothing secret goes before 发送")

            client.send()
            assertEquals(WatchWireMessage(type = "remoteSignInSend", signInServer = HANDED), socket.sent.receive())
            assertEquals(RemoteSignInState.Sending, client.state.value)
            client.send()
            runCurrent()
            assertTrue(socket.sent.tryReceive().isFailure, "the session goes once")

            socket.push(WatchWireMessage(type = "remoteSignInEnded", message = "电视已登录这台服务器"))
            runCurrent()
            assertEquals(RemoteSignInState.Done, client.state.value)
        }

    @Test
    fun a_television_that_cancels_or_cannot_use_the_session_is_a_failure_and_nothing_is_resent() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.offer("tv-session", SAVED)
            val first = relay.sessions.receive()
            first.sent.receive()
            first.push(offered)
            runCurrent()
            // The television stopped asking before 发送: nothing may go after that.
            first.push(ended(WatchProtocol.REMOTE_SIGN_IN_CANCELLED_CODE, "电视已取消用手机登录"))
            runCurrent()
            assertEquals(RemoteSignInState.Failed("电视已取消用手机登录"), client.state.value)
            client.send()
            runCurrent()
            assertTrue(first.sent.tryReceive().isFailure)

            // Tried again by hand; this time the server does not take the session.
            client.offer("tv-session", SAVED)
            val second = relay.sessions.receive()
            second.sent.receive()
            second.push(offered)
            runCurrent()
            client.send()
            second.sent.receive()
            second.push(ended(WatchProtocol.REMOTE_SIGN_IN_FAILED_CODE, "电视未能用这份登录连接服务器"))
            runCurrent()
            assertEquals(RemoteSignInState.Failed("电视未能用这份登录连接服务器"), client.state.value)
            assertEquals(2, relay.connects)
        }

    @Test
    fun a_television_that_never_answers_is_given_up_on_without_sending_again() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.offer("tv-session", SAVED)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            socket.push(offered)
            runCurrent()
            client.send()
            socket.sent.receive()
            advanceTimeBy(REMOTE_SIGN_IN_ANSWER_MS + 1)
            runCurrent()
            val failed = client.state.value as RemoteSignInState.Failed
            assertTrue(failed.retryable)
            assertTrue(socket.sent.tryReceive().isFailure)
            assertEquals(1, relay.connects)
        }

    @Test
    fun a_relay_that_cannot_carry_it_is_never_given_a_session() =
        runTest {
            // In the clear, nothing is even offered.
            val plainRelay = FakeRelay()
            val plain = client(plainRelay, backgroundScope, url = "ws://relay.test/watch")
            plain.offer("tv-session", SAVED)
            runCurrent()
            assertEquals(RemoteSignInState.Failed("用手机登录需要加密连接", retryable = false), plain.state.value)
            assertEquals(0, plainRelay.connects)

            // A relay from before 用手机登录 refuses the message type; one that takes it without
            // saying it carries it is not trusted with the session either.
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.offer("tv-session", SAVED)
            val old = relay.sessions.receive()
            old.sent.receive()
            old.push(WatchWireMessage(type = "error", message = "消息类型无效", errorCode = "message_type_invalid"))
            runCurrent()
            val unsupported = client.state.value as RemoteSignInState.Failed
            assertFalse(unsupported.retryable)

            client.offer("tv-session", SAVED)
            val silent = relay.sessions.receive()
            silent.sent.receive()
            silent.push(offered.copy(capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_CONTROL)))
            runCurrent()
            assertEquals(unsupported, client.state.value)
            client.send()
            runCurrent()
            assertTrue(silent.sent.tryReceive().isFailure)
        }

    @Test
    fun the_relays_refusal_is_said_and_closing_withdraws_the_offer() =
        runTest {
            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            client.offer("tv-session", SAVED)
            val socket = relay.sessions.receive()
            socket.sent.receive()
            val refusal = "电视没有在等待手机登录，请先在电视的「添加服务器」里选择「用手机登录」"
            socket.push(WatchWireMessage(type = "error", message = refusal, errorCode = "remote_sign_in_unavailable"))
            runCurrent()
            assertEquals(RemoteSignInState.Failed(refusal), client.state.value)

            client.offer("tv-session", SAVED)
            val again = relay.sessions.receive()
            again.sent.receive()
            again.push(offered)
            runCurrent()
            client.close()
            assertEquals(RemoteSignInState.Idle, client.state.value)
            client.send()
            runCurrent()
            assertTrue(again.sent.tryReceive().isFailure)
            assertEquals(RemoteSignInState.Idle, client.state.value)
        }

    @Test
    fun only_a_server_a_television_can_take_is_offered_and_as_this_phone_has_it() =
        runTest {
            // The identity address, trimmed, whichever route the phone is on now.
            val onBackup =
                SAVED.copy(
                    baseUrl = "https://backup.example.com",
                    routes =
                        listOf(
                            ServerRoute(ServerRoute.PRIMARY_ID, ServerRoute.PRIMARY_NAME, "http://192.168.1.8:8096/"),
                            ServerRoute("backup", "外网", "https://backup.example.com"),
                        ),
                    activeRouteId = "backup",
                )
            assertEquals(HANDED, onBackup.toRemoteSignInServer())
            assertNull(SAVED.copy(kind = MediaServerKind.Plex).toRemoteSignInServer())
            assertNull(SAVED.copy(baseUrl = "http://alice:secret@192.168.1.8:8096").toRemoteSignInServer())
            assertNull(SAVED.copy(accessToken = "").toRemoteSignInServer())

            val relay = FakeRelay()
            val client = client(relay, backgroundScope)
            assertFalse(client.offer("tv-session", SAVED.copy(kind = MediaServerKind.Plex)))
            runCurrent()
            assertEquals(RemoteSignInState.Idle, client.state.value)
            assertEquals(0, relay.connects)
        }

    private fun client(
        relay: FakeRelay,
        scope: CoroutineScope,
        url: String = "wss://relay.test/watch",
    ) = RemoteSignInClient(
        accessToken = { "token" },
        refreshAccessToken = { null },
        scope = scope,
        url = url,
        connector = relay,
        identity = { RemotePhoneIdentity("phone-a", "测试手机") },
    )

    private fun ended(
        errorCode: String,
        message: String,
    ) = WatchWireMessage(type = "remoteSignInEnded", message = message, errorCode = errorCode)

    private val offered =
        WatchWireMessage(type = "remoteSignInOffered", capabilities = WatchProtocol.SERVER_CAPABILITIES)

    private companion object {
        val SAVED =
            SavedServer(
                id = SavedServer.idOf("http://192.168.1.8:8096", "u1"),
                baseUrl = "http://192.168.1.8:8096",
                serverName = "家里的 Jellyfin",
                userId = "u1",
                userName = "alice",
                accessToken = "saved-token",
                kind = MediaServerKind.Jellyfin,
            )
        val HANDED =
            RemoteSignInServer(
                kind = "Jellyfin",
                serverName = "家里的 Jellyfin",
                baseUrl = "http://192.168.1.8:8096",
                userName = "alice",
                userId = "u1",
                accessToken = "saved-token",
            )
    }
}
