package com.yfuse.core.remote

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireCredential
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemotePairingTokenTest {
    private class MemoryTokens : RemotePairingTokenStore {
        val tokens = mutableMapOf<String, String>()

        override fun load(televisionSessionId: String): String? = tokens[televisionSessionId]

        override fun save(
            televisionSessionId: String,
            token: String,
        ) {
            tokens[televisionSessionId] = token
        }
    }

    @Test
    fun the_token_an_admission_hands_over_is_kept_and_presented_on_the_next_join() =
        runTest {
            val relay = FakeRelay()
            val store = MemoryTokens()
            val client =
                PhoneRemoteClient(
                    accessToken = { "token" },
                    refreshAccessToken = { null },
                    url = "wss://relay.test/watch",
                    connector = relay,
                    retryDelayMs = { 1_000L },
                    scope = backgroundScope,
                    identity = { RemotePhoneIdentity("phone-a", "测试手机") },
                    pairingTokens = store,
                )
            client.connect("tv-session")
            val socket = relay.sessions.receive()
            val join = socket.sent.receive()
            assertEquals(
                listOf(WatchProtocol.CAPABILITY_REAUTHENTICATE, WatchProtocol.CAPABILITY_REMOTE_PAIRING_TOKEN),
                join.capabilities,
            )
            assertNull(join.credential)
            socket.push(
                WatchWireMessage(
                    type = "remoteJoined",
                    capabilities =
                        listOf(
                            WatchProtocol.CAPABILITY_REMOTE_CONTROL,
                            WatchProtocol.CAPABILITY_REMOTE_PAIRING,
                        ),
                    participantCount = 1,
                    ready = false,
                ),
            )
            client.state.first { it == PhoneRemoteState.Waiting }
            socket.push(
                WatchWireMessage(type = "remoteAdmitted", credential = WatchWireCredential(pairingToken = TOKEN)),
            )
            client.state.first { it == PhoneRemoteState.Connected }
            assertEquals(TOKEN, store.load("tv-session"))

            client.close()
            client.connect("tv-session")
            val again = relay.sessions.receive()
            assertEquals(
                TOKEN,
                again.sent
                    .receive()
                    .credential
                    ?.pairingToken,
            )
            client.close()
        }

    private companion object {
        val TOKEN = "p".repeat(43)
    }
}
