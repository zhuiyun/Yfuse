package com.yfuse.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherTransportTest {
    @Test
    fun http_endpoints_become_the_matching_websocket_scheme_with_one_watch_path() {
        assertEquals("wss://47.112.219.60/watch", "https://47.112.219.60/".toWebSocketUrl())
        assertEquals("ws://host:8080/watch", "http://host:8080/watch".toWebSocketUrl())
        assertEquals("wss://relay/watch", "wss://relay/watch/".toWebSocketUrl())
        assertEquals("ws://relay/watch", " ws://relay ".toWebSocketUrl())
    }

    @Test
    fun endpoints_without_a_web_scheme_are_rejected() {
        assertNull("".toWebSocketUrl())
        assertNull("   ".toWebSocketUrl())
        assertNull("relay.example/watch".toWebSocketUrl())
        assertNull("ftp://relay.example".toWebSocketUrl())
    }

    @Test
    fun reconnect_backoff_doubles_per_attempt_with_bounded_jitter_and_a_cap() {
        repeat(20) {
            assertTrue(backoffDelayMs(1) in 1_000L..1_200L)
            assertTrue(backoffDelayMs(2) in 2_000L..2_400L)
            assertTrue(backoffDelayMs(3) in 4_000L..4_800L)
            // 1 s * 2^5 = 32 s would exceed the cap; the sixth attempt and beyond stay at 20 s.
            assertTrue(backoffDelayMs(6) in 20_000L..24_000L)
            assertTrue(backoffDelayMs(99) in 20_000L..24_000L)
        }
    }

    @Test
    fun attempts_below_one_are_treated_as_the_first_attempt() {
        repeat(20) {
            assertTrue(backoffDelayMs(0) in 1_000L..1_200L)
            assertTrue(backoffDelayMs(-4) in 1_000L..1_200L)
        }
    }

    @Test
    fun domain_and_message_based_authentication_failures_are_recognised() {
        assertTrue(WatchAuthenticationException().isWatchAuthenticationFailure())
        assertTrue(AccountRequiredForWatchException().isWatchAuthenticationFailure())
        assertTrue(IllegalStateException("relay answered 401").isWatchAuthenticationFailure())
        assertTrue(IllegalStateException("Unauthorized handshake").isWatchAuthenticationFailure())
        assertFalse(IllegalStateException("connection reset").isWatchAuthenticationFailure())
        assertFalse(RoomUnavailableException("room closed").isWatchAuthenticationFailure())
    }

    @Test
    fun only_a_401_response_counts_as_an_authentication_failure() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val status =
                            if (request.url.encodedPath.endsWith("/expired")) {
                                HttpStatusCode.Unauthorized
                            } else {
                                HttpStatusCode.Forbidden
                            }
                        respond(content = "", status = status)
                    },
                )
            try {
                val unauthorized = ResponseException(client.get("http://relay/expired"), "")
                val forbidden = ResponseException(client.get("http://relay/blocked"), "")

                assertTrue(unauthorized.isWatchAuthenticationFailure())
                assertFalse(forbidden.isWatchAuthenticationFailure())
            } finally {
                client.close()
            }
        }
}
