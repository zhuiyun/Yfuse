package com.yfuse.backend

import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.createBackendAccountClient
import com.yfuse.core.handoff.AccountHandoffApi
import com.yfuse.core.migration.MigrationRelayApi
import com.yfuse.core.migration.createMigrationRelayClient
import com.yfuse.core.trakt.AccountTraktAuthApi
import com.yfuse.core.trakt.TraktApiException
import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import com.yfuse.watch.protocol.TraktRefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackendIntegrationBoundaryTest {
    private val origin = "https://account.example"
    private val disabled = BackendAccess(enabled = false)
    private val enabled = BackendAccess(enabled = true)

    @Test
    fun disabledHandoffNeverResolvesCredentialsOrSendsAnyOperation() =
        runTest {
            var requests = 0
            var tokenReads = 0
            val client =
                enabledAccountClient(
                    MockEngine {
                        requests++
                        error("Disabled handoff reached the network")
                    },
                )
            val tokens =
                AccountAccessTokenSource(origin, enabled).apply {
                    bind(
                        provider = {
                            tokenReads++
                            "access-secret"
                        },
                        refreshProvider = {
                            tokenReads++
                            "refresh-secret"
                        },
                    )
                }
            try {
                val api = AccountHandoffApi(client, tokens, origin, disabled)
                val operations: List<suspend () -> Unit> =
                    listOf(
                        { api.inbox() },
                        { api.heartbeat(HandoffHeartbeat("Phone", "Android", true)) },
                        { api.offer(HandoffOffer("request-id", "target", HandoffEnvelope("nonce", "ciphertext"))) },
                        { api.transition("valid-request-0001", HandoffTransition(HandoffStatus.Ready)) },
                    )
                operations.forEach { operation ->
                    assertFailsWith<BackendUnavailableException> { operation() }
                }
                assertEquals(0, requests)
                assertEquals(0, tokenReads)
            } finally {
                client.close()
            }
        }

    @Test
    fun disabledTraktAuthorizationDoesNotResolveCredentialsOrSendAnyOperation() =
        runTest {
            var requests = 0
            var tokenReads = 0
            val client =
                enabledAccountClient(
                    MockEngine {
                        requests++
                        error("Disabled authorization reached the network")
                    },
                )
            val tokens =
                AccountAccessTokenSource(origin, enabled).apply {
                    bind(
                        provider = {
                            tokenReads++
                            "access-secret"
                        },
                        refreshProvider = {
                            tokenReads++
                            "refresh-secret"
                        },
                    )
                }
            try {
                val api = AccountTraktAuthApi(client, tokens, origin, disabled)
                val operations: List<suspend () -> Unit> =
                    listOf(
                        { api.configuration() },
                        { api.begin(device = true) },
                        { api.poll("valid-request-0001") },
                        { api.cancel("valid-request-0001") },
                        { api.refresh(TraktRefreshRequest("refresh-secret", "request-id")) },
                        { api.revoke("access-secret") },
                    )
                operations.forEach { operation ->
                    assertFailsWith<BackendUnavailableException> { operation() }
                }
                assertEquals(0, requests)
                assertEquals(0, tokenReads)
            } finally {
                client.close()
            }
        }

    @Test
    fun disabledMigrationBlocksApiAndDirectFactoryClientBeforeSendingSecrets() =
        runTest {
            var requests = 0
            val engine =
                MockEngine {
                    requests++
                    error("Disabled migration reached the network")
                }
            val client = createMigrationRelayClient(engine, origin, disabled)
            try {
                val api = MigrationRelayApi(client, origin, disabled)
                assertFailsWith<BackendUnavailableException> { api.create("relay", "secret", "hash") }
                assertFailsWith<BackendUnavailableException> { api.redeem("relay", "000042", "hash") }
                assertFailsWith<BackendUnavailableException> { client.post("$origin/api/v1/migration-relays") }
                assertEquals(0, requests)
            } finally {
                client.close()
                engine.close()
            }
        }

    @Test
    fun enabledTraktAuthorizationRefreshesOnceAndRetainsItsServiceEndpoint() =
        runTest {
            var requests = 0
            val tokens =
                AccountAccessTokenSource(origin, enabled).apply {
                    bind(provider = { "stale" }, refreshProvider = { "fresh" })
                }
            val client =
                enabledAccountClient(
                    MockEngine { request ->
                        requests++
                        assertEquals("/api/v1/account/trakt/configuration", request.url.encodedPath)
                        assertEquals(
                            "Bearer ${if (requests == 1) "stale" else "fresh"}",
                            request.headers[HttpHeaders.Authorization],
                        )
                        if (requests == 1) {
                            respond("", HttpStatusCode.Unauthorized)
                        } else {
                            respond("""{"clientId":"trakt-client","oauthAvailable":true}""")
                        }
                    },
                )
            try {
                val configuration = AccountTraktAuthApi(client, tokens, origin, enabled).configuration()
                assertEquals("trakt-client", configuration.clientId)
                assertTrue(configuration.oauthAvailable)
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun enabledTraktAuthorizationStillBoundsResponsesAndPreservesRetryHints() =
        runTest {
            val tokens =
                AccountAccessTokenSource(origin, enabled).apply {
                    bind(provider = { "access" }, refreshProvider = { "fresh" })
                }
            val oversized = enabledAccountClient(MockEngine { respond("x".repeat(65_537)) })
            try {
                assertFailsWith<IllegalStateException> {
                    AccountTraktAuthApi(oversized, tokens, origin, enabled).configuration()
                }
            } finally {
                oversized.close()
            }
            val limited =
                enabledAccountClient(
                    MockEngine {
                        respond("private upstream text", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "42"))
                    },
                )
            try {
                val failure =
                    assertFailsWith<TraktApiException> {
                        AccountTraktAuthApi(limited, tokens, origin, enabled).configuration()
                    }
                assertEquals(42, failure.retryAfterSeconds)
                assertTrue(!failure.message.orEmpty().contains("private"))
            } finally {
                limited.close()
            }
        }

    @Test
    fun migrationAcceptsAnUnpaddedSecretAndRejectsIncorrectDecodedLength() =
        runTest {
            val secrets = listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AA")
            var responseIndex = 0
            val engine =
                MockEngine {
                    val encoded = secrets[responseIndex++]
                    respond(
                        """{"transferSecret":"$encoded"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = createMigrationRelayClient(engine, origin, enabled)
            try {
                val api = MigrationRelayApi(client, origin, enabled)
                assertContentEquals(ByteArray(32), api.redeem("relay", "000042", "hash"))
                assertFailsWith<IllegalArgumentException> { api.redeem("relay", "000042", "hash") }
            } finally {
                client.close()
                engine.close()
            }
        }
}

private fun enabledAccountClient(engine: HttpClientEngine): HttpClient =
    createBackendAccountClient(engine, access = BackendAccess(enabled = true))
