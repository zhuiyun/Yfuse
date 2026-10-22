package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePairingTest {
    @Test
    fun input_from_a_phone_the_television_has_not_let_in_never_reaches_it() {
        val relay = RemoteControlRelay<String>()
        relay.host("user", "tv-session", "tv", asks = true)
        relay.join("user", "phone-session", "tv-session", "phone", RemotePhone("device-1"))
        assertEquals(RemoteAdmission.Refused(RemoteRefusal.NotAdmitted), relay.admitInput("phone", nowMs = 0L))
        relay.admit("tv", "device-1")
        assertEquals(RemoteAdmission.Input("tv", RemotePhone("device-1")), relay.admitInput("phone", nowMs = 1L))

        // A television that does not ask (an older app) is unchanged.
        relay.host("user", "old-tv-session", "old-tv", asks = false)
        relay.join("user", "phone-session", "old-tv-session", "phone-2", RemotePhone("device-2"))
        assertEquals(RemoteAdmission.Input("old-tv", RemotePhone("device-2")), relay.admitInput("phone-2", nowMs = 2L))
    }

    @Test
    fun admission_grants_a_token_once_to_a_phone_that_can_hold_one() {
        val relay = RemoteControlRelay<String>()
        relay.host("user", "tv-session", "tv", asks = true)
        relay.join("user", "p-session", "tv-session", "capable", RemotePhone("device-1"), pairingDeviceId = "device-1")
        relay.join("user", "p-session", "tv-session", "older", RemotePhone("device-2"))
        assertEquals(
            RemoteAdmission.Admitted(
                listOf("capable"),
                mapOf(remotePairingKey("user", "tv-session", "device-1") to listOf("capable")),
            ),
            relay.admit("tv", "device-1"),
        )
        // Once paired it is not paired again, and a phone that cannot hold a token gets none.
        assertEquals(RemoteAdmission.Admitted(listOf("capable")), relay.admit("tv", "device-1"))
        assertEquals(RemoteAdmission.Admitted(listOf("older")), relay.admit("tv", "device-2"))
    }

    @Test
    fun tokens_verify_only_their_own_pairing_expire_and_outlive_a_restart() {
        SqliteWatchStateStore.inMemory().use { store ->
            var now = 0L
            val tokens = RemotePairingTokens(store, ttlMs = 1_000L, now = { now })
            val key = remotePairingKey("user", "tv", "device")
            assertEquals(PairingCheck.Unknown, tokens.check(key, null))
            val token = tokens.issue(key)
            assertEquals(PairingCheck.Verified, tokens.check(key, token))
            assertEquals(PairingCheck.Mismatch, tokens.check(key, null))
            assertEquals(PairingCheck.Mismatch, tokens.check(key, "x".repeat(43)))
            assertEquals(PairingCheck.Unknown, tokens.check(remotePairingKey("user", "other-tv", "device"), token))

            // A relay restarted on the same store still knows the pairing.
            val restarted = RemotePairingTokens(store, ttlMs = 1_000L, now = { now })
            assertEquals(PairingCheck.Verified, restarted.check(key, token))
            now = 2_000L
            assertEquals(PairingCheck.Unknown, restarted.check(key, token))
        }
    }

    private val accounts: suspend (String) -> com.yfuse.watch.account.AuthenticatedAccount = { token ->
        // `<user>/<session>` bearers: one account, several devices.
        val parts = token.split('/')
        if (parts.size == 2) {
            testWatchAccount(parts[0], parts[1])
        } else {
            throw AccountServiceException(AccountProblem.Unauthorized, "unauthorized", "unauthorized")
        }
    }

    private suspend fun ApplicationTestBuilder.device(bearer: String): WebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/watch") {
            headers.append(HttpHeaders.Authorization, "Bearer $bearer")
        }

    @Test
    fun a_phone_claiming_a_paired_id_without_its_token_is_shown_as_unknown() =
        testApplication {
            application {
                watchTogetherModule(
                    requireWatchAuthentication = true,
                    watchAccountAuthenticator = accounts,
                    watchAccountRevalidator = accounts,
                )
            }
            val tv = device("alice/tv")
            tv.send("""{"type":"remoteHost","capabilities":["remotePairing"]}""")
            tv.awaitType("remoteHosting")

            val phone = device("alice/phone")
            phone.send(
                """{"type":"remoteJoin","remoteSessionId":"tv","remoteDeviceId":"phone-1","capabilities":["remotePairingToken"]}""",
            )
            phone.awaitType("remoteJoined")
            assertEquals("phone-1", tv.awaitType("remoteConnected").field("remoteDeviceId"))
            tv.send("""{"type":"remoteAdmit","remoteDeviceId":"phone-1"}""")
            val admitted = phone.awaitType("remoteAdmitted")
            val token =
                assertNotNull(
                    admitted["credential"]
                        ?.jsonObject
                        ?.get("pairingToken")
                        ?.jsonPrimitive
                        ?.content,
                )

            // Another device of the same account names itself phone-1 without the token.
            val impostor = device("alice/other-phone")
            impostor.send("""{"type":"remoteJoin","remoteSessionId":"tv","remoteDeviceId":"phone-1"}""")
            impostor.awaitType("remoteJoined")
            val shown = assertNotNull(tv.awaitType("remoteConnected").field("remoteDeviceId"))
            assertTrue(shown.startsWith(com.yfuse.watch.protocol.WatchProtocol.REMOTE_EPHEMERAL_DEVICE_PREFIX))
            // Nor can it press anything before the television lets it in.
            impostor.send("""{"type":"remoteKey","remoteKey":"up"}""")
            assertEquals("remote_not_admitted", impostor.awaitType("error").field("errorCode"))

            // The real phone, with its token, is still itself.
            phone.close()
            val returning = device("alice/phone")
            returning.send(
                """{"type":"remoteJoin","remoteSessionId":"tv","remoteDeviceId":"phone-1",""" +
                    """"capabilities":["remotePairingToken"],"credential":{"pairingToken":"$token"}}""",
            )
            returning.awaitType("remoteJoined")
            val back = tv.awaitWhere("remoteConnected") { it.field("remoteDeviceId") == "phone-1" }
            assertNull(back["credential"])
            listOf(tv, impostor, returning).forEach { it.close() }
        }
}
