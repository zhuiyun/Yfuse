package com.yfuse.watch.account

import com.yfuse.watch.protocol.TraktAuthStatus
import com.yfuse.watch.protocol.TraktRefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktOAuthBrokerTest {
    private var clock = 1_000_000L
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var response = TraktOAuthResponse(200, TOKEN_JSON)
    private val transport =
        TraktOAuthTransport { path, body ->
            calls += path to body
            response
        }
    private val account = AuthenticatedAccount("user", "session", "viewer", "Viewer", 0, Long.MAX_VALUE)

    private fun broker() =
        TraktOAuthBroker(
            "public-client",
            "server-secret",
            "https://account.example/api/v1/account/trakt/callback",
            transport,
        ) {
            clock
        }

    @Test
    fun missingConfigurationCannotStartOAuth() =
        runBlocking {
            val broker = TraktOAuthBroker("", "", "", transport) { clock }
            assertFalse(broker.configuration().oauthAvailable)
            assertFalse(broker.configuration().deviceAvailable)
            assertFailsWith<AccountServiceException> { broker.begin(account, false) }
            assertTrue(calls.isEmpty())
        }

    @Test
    fun browserCallbackUsesBoundSingleUseStateAndCurrentAuthHost() =
        runBlocking {
            val broker = broker()
            val challenge = broker.begin(account, false)
            val uri = URI(challenge.verificationUrl)
            assertEquals("auth.trakt.tv", uri.host)
            val state =
                uri.rawQuery
                    .split('&')
                    .map { it.split('=', limit = 2) }
                    .associate {
                        it[0] to
                            URLDecoder.decode(it[1], Charsets.UTF_8)
                    }.getValue("state")
            assertFailsWith<AccountServiceException> { broker.callback("x".repeat(64), "code", false) }
            broker.callback(state, "authorization-code", false)
            assertEquals(TraktAuthStatus.Connected, broker.poll(account, challenge.id).status)
            assertFailsWith<AccountServiceException> { broker.callback(state, "authorization-code", false) }
            assertFailsWith<AccountServiceException> {
                broker.poll(
                    account.copy(sessionId = "another-device"),
                    challenge.id,
                )
            }
            assertEquals("/oauth/token", calls.single().first)
            assertEquals(
                "server-secret",
                calls
                    .single()
                    .second
                    .getValue("client_secret")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun devicePollHonorsIntervalSlowDownAndDenial() =
        runBlocking {
            val broker = broker()
            response =
                TraktOAuthResponse(
                    200,
                    """{"device_code":"device-code","user_code":"ABCD","verification_url":"https://auth.trakt.tv/activate","expires_in":600,"interval":5}""",
                )
            val challenge = broker.begin(account, true)
            assertEquals(TraktAuthStatus.Pending, broker.poll(account, challenge.id).status)
            assertEquals(1, calls.size)
            clock += 5_000
            response = TraktOAuthResponse(429, "{}", retryAfterSeconds = 30)
            assertEquals(30, broker.poll(account, challenge.id).retryAfterSeconds)
            clock += 10_000
            broker.poll(account, challenge.id)
            assertEquals(2, calls.size)
            clock += 20_000
            response = TraktOAuthResponse(418, "{}")
            assertEquals(TraktAuthStatus.Denied, broker.poll(account, challenge.id).status)
            broker.poll(account, challenge.id)
            assertEquals(3, calls.size)
        }

    @Test
    fun refreshRetryReusesReplacementForSingleUseRefreshToken(): Unit =
        runBlocking {
            val broker = broker()
            val request = TraktRefreshRequest("refresh-original", "request-0000000001")
            val first = broker.refresh(account, request)
            assertEquals(first, broker.refresh(account, request))
            assertEquals(1, calls.size)
            assertFailsWith<AccountServiceException> {
                broker.refresh(
                    account,
                    request.copy(refreshToken = "another-token"),
                )
            }
        }

    @Test
    fun untrustedVerificationUrlAndExpiredChallengeAreRejected() =
        runBlocking {
            val broker = broker()
            response =
                TraktOAuthResponse(
                    200,
                    """{"device_code":"code","user_code":"ABCD","verification_url":"https://evil.example/activate","expires_in":600,"interval":5}""",
                )
            assertFailsWith<AccountServiceException> { broker.begin(account, true) }
            val challenge = broker.begin(account, false)
            clock = challenge.expiresAtEpochMs
            assertEquals(TraktAuthStatus.Expired, broker.poll(account, challenge.id).status)
        }

    companion object {
        private const val TOKEN_JSON = """
            {"access_token":"access","refresh_token":"refresh-replacement",
             "expires_in":604800,"created_at":1000,"token_type":"bearer"}
        """
    }
}
