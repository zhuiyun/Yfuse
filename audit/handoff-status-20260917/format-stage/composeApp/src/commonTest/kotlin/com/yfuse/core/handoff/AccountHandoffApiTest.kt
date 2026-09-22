package com.yfuse.core.handoff

import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.createAccountClient
import com.yfuse.watch.protocol.HandoffHeartbeat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountHandoffApiTest {
    private val origin = "https://account.example.test"

    @Test
    fun missing_heartbeat_endpoint_preserves_service_error_without_refreshing_the_account() =
        runTest {
            var refreshes = 0
            val tokens =
                AccountAccessTokenSource(origin).apply {
                    bind(provider = { "access" }, refreshProvider = {
                        refreshes++
                        "fresh"
                    })
                }
            val client =
                createAccountClient(
                    MockEngine { request ->
                        assertEquals("/api/v1/account/handoff/heartbeat", request.url.encodedPath)
                        assertEquals("Bearer access", request.headers["Authorization"])
                        respond("private upstream response", HttpStatusCode.NotFound)
                    },
                )
            try {
                val failure =
                    assertFailsWith<HandoffApiException> {
                        AccountHandoffApi(client, tokens, origin).heartbeat(HandoffHeartbeat("Phone", "Android", true))
                    }
                assertEquals(HttpStatusCode.NotFound, failure.status)
                assertTrue(failure.message.orEmpty().contains("服务端更新"))
                assertTrue(!failure.message.orEmpty().contains("private"))
                assertEquals(0, refreshes)
            } finally {
                client.close()
            }
        }

    @Test
    fun expired_access_token_is_refreshed_once_and_the_same_request_retried() =
        runTest {
            var refreshes = 0
            val tokens =
                AccountAccessTokenSource(origin).apply {
                    bind(provider = { "stale" }, refreshProvider = {
                        refreshes++
                        "fresh"
                    })
                }
            val auth = mutableListOf<String?>()
            val client =
                createAccountClient(
                    MockEngine { request ->
                        auth += request.headers["Authorization"]
                        if (auth.size == 1) {
                            respond("", HttpStatusCode.Unauthorized)
                        } else {
                            respond(
                                """{"currentSessionId":"session","devices":[],"requests":[],"serverTimeEpochMs":1000}""",
                                HttpStatusCode.OK,
                                headersOf("Content-Type", "application/json"),
                            )
                        }
                    },
                )
            try {
                val inbox = AccountHandoffApi(client, tokens, origin).inbox()
                assertEquals("session", inbox.currentSessionId)
                assertEquals<List<String?>>(listOf("Bearer stale", "Bearer fresh"), auth)
                assertEquals(1, refreshes)
            } finally {
                client.close()
            }
        }

    @Test
    fun rejected_refreshed_token_reports_authentication_failure_without_an_unbounded_retry() =
        runTest {
            var attempts = 0
            val tokens =
                AccountAccessTokenSource(origin).apply {
                    bind(provider = { "stale" }, refreshProvider = { "fresh" })
                }
            val client =
                createAccountClient(
                    MockEngine {
                        attempts++
                        respond("", HttpStatusCode.Unauthorized)
                    },
                )
            try {
                val failure = assertFailsWith<HandoffApiException> { AccountHandoffApi(client, tokens, origin).inbox() }
                assertEquals(HttpStatusCode.Unauthorized, failure.status)
                assertTrue(failure.message.orEmpty().contains("凭证已失效"))
                assertEquals(2, attempts)
            } finally {
                client.close()
            }
        }

    @Test
    fun absent_credentials_never_send_an_anonymous_handoff_request() =
        runTest {
            var attempts = 0
            val client =
                createAccountClient(
                    MockEngine {
                        attempts++
                        respond("", HttpStatusCode.OK)
                    },
                )
            try {
                val failure =
                    assertFailsWith<HandoffApiException> {
                        AccountHandoffApi(client, AccountAccessTokenSource(origin), origin).inbox()
                    }
                assertEquals(HttpStatusCode.Unauthorized, failure.status)
                assertEquals(0, attempts)
            } finally {
                client.close()
            }
        }
}
