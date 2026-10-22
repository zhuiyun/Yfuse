package com.yfuse.watch

import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AuthenticatedAccount
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchReauthenticationTest {
    /** Tokens the fake account store knows; removing one revokes it. */
    private val tokens = ConcurrentHashMap<String, AuthenticatedAccount>()

    private val lookup: suspend (String) -> AuthenticatedAccount = { token ->
        tokens[token] ?: throw AccountServiceException(AccountProblem.Unauthorized, "unauthorized", "unauthorized")
    }

    private fun ApplicationTestBuilder.relay(renewalGraceMs: Long = WATCH_REAUTH_RENEWAL_GRACE_MS) {
        application {
            watchTogetherModule(
                requireWatchAuthentication = true,
                watchAuthRevalidationMs = 40L,
                watchAccountAuthenticator = lookup,
                watchAccountRevalidator = lookup,
                watchAuthRenewalGraceMs = renewalGraceMs,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.socket(token: String): WebSocketSession =
        createClient { install(WebSockets) }.webSocketSession("/watch") {
            headers.append(HttpHeaders.Authorization, "Bearer $token")
        }

    private suspend fun WebSocketSession.reauthenticate(token: String) {
        send("""{"type":"reauthenticate","credential":{"accessToken":"$token"}}""")
    }

    private suspend fun WebSocketSession.closedWith(): String? =
        withTimeout(5_000L) { (this@closedWith as DefaultWebSocketSession).closeReason.await() }?.message

    @Test
    fun reauthentication_keeps_the_socket_open_past_the_first_tokens_expiry() =
        testApplication {
            val now = System.currentTimeMillis()
            tokens["first"] = testWatchAccount("alice", "alice-tv", accessExpiresAtEpochMs = now + 1_200L)
            tokens["second"] = testWatchAccount("alice", "alice-tv", accessExpiresAtEpochMs = now + 600_000L)
            relay()
            val session = socket("first")
            session.send(
                """{"type":"hello","protocolVersion":6,"clientId":"tv","mediaKey":"tmdb:1","capabilities":["reauthenticate"]}""",
            )
            val announced = session.awaitType("reauthenticated")
            assertEquals(now + 1_200L, announced.getValue("authExpiresAtMs").jsonPrimitive.long)
            session.awaitType("welcome")

            session.reauthenticate("second")
            val renewed = session.awaitType("reauthenticated")
            assertEquals(now + 600_000L, renewed.getValue("authExpiresAtMs").jsonPrimitive.long)

            // Well past the first token's expiry, and past several revalidations of the new one.
            delay(1_800L)
            session.send("""{"type":"ping","clientSentAtMs":7}""")
            assertEquals("pong", session.awaitType("pong").field("type"))
            session.close()
        }

    @Test
    fun a_token_of_another_session_or_account_is_refused_and_the_socket_still_expires() =
        testApplication {
            val now = System.currentTimeMillis()
            tokens["tv"] = testWatchAccount("alice", "alice-tv", accessExpiresAtEpochMs = now + 1_500L)
            tokens["phone"] = testWatchAccount("alice", "alice-phone", accessExpiresAtEpochMs = now + 600_000L)
            tokens["bob"] = testWatchAccount("bob", "bob-tv", accessExpiresAtEpochMs = now + 600_000L)
            relay()
            val session = socket("tv")
            session.send("""{"type":"remoteHost","capabilities":["remotePairing","reauthenticate"]}""")
            session.awaitType("reauthenticated")
            session.awaitType("remoteHosting")

            session.reauthenticate("phone")
            assertEquals("reauth_mismatch", session.awaitType("error").field("errorCode"))
            session.reauthenticate("bob")
            assertEquals("reauth_mismatch", session.awaitType("error").field("errorCode"))
            session.reauthenticate("unknown-token")
            val rejected = session.awaitType("error")
            assertEquals("reauth_rejected", rejected.field("errorCode"))
            assertEquals("false", rejected.field("retryable"))

            assertEquals("account_auth_expired", session.closedWith())
        }

    @Test
    fun a_socket_that_never_renews_closes_at_expiry_as_before() =
        testApplication {
            tokens["legacy"] =
                testWatchAccount("carol", "carol-phone", accessExpiresAtEpochMs = System.currentTimeMillis() + 600L)
            relay()
            val session = socket("legacy")
            session.send("""{"type":"hello","protocolVersion":5,"clientId":"phone","mediaKey":"tmdb:2"}""")
            val frames = session.drainFor(2_000L)
            assertTrue(frames.none { it.field("type") == "reauthenticated" })
            assertEquals("account_auth_expired", session.closedWith())
        }

    @Test
    fun a_replaced_token_is_answered_with_a_short_grace_to_renew() =
        testApplication {
            val now = System.currentTimeMillis()
            tokens["old"] = testWatchAccount("dave", "dave-tv", accessExpiresAtEpochMs = now + 600_000L)
            tokens["new"] = testWatchAccount("dave", "dave-tv", accessExpiresAtEpochMs = now + 900_000L)
            relay(renewalGraceMs = 2_000L)
            val session = socket("old")
            session.send("""{"type":"remoteHost","capabilities":["reauthenticate"]}""")
            session.awaitType("remoteHosting")

            // A refresh elsewhere rotated the session's access token.
            tokens.remove("old")
            val required = session.awaitType("error")
            assertEquals("reauth_required", required.field("errorCode"))
            session.reauthenticate("new")
            session.awaitType("reauthenticated")
            delay(300L)
            session.send("""{"type":"ping","clientSentAtMs":9}""")
            session.awaitType("pong")

            // Revoked for good: no renewal arrives, and the grace ends the socket.
            tokens.remove("new")
            assertEquals("reauth_required", session.awaitType("error").field("errorCode"))
            assertEquals("account_auth_expired", session.closedWith())
        }
}
