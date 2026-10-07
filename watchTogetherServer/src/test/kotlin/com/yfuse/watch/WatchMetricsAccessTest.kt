package com.yfuse.watch

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchMetricsAccessTest {
    @Test
    fun without_a_token_a_proxied_loopback_request_is_refused() =
        testApplication {
            application { watchTogetherModule(metricsToken = null) }

            // What Caddy forwards: the peer is loopback, but the caller is somewhere else.
            val proxied = client.get("/watch/metrics") { header("X-Forwarded-For", "203.0.113.9") }
            assertEquals(HttpStatusCode.Forbidden, proxied.status)
            val forwarded = client.get("/watch/metrics") { header("Forwarded", "for=203.0.113.9") }
            assertEquals(HttpStatusCode.Forbidden, forwarded.status)

            // `curl http://127.0.0.1:8080/watch/metrics` on the box itself.
            assertEquals(HttpStatusCode.OK, client.get("/watch/metrics").status)
        }

    @Test
    fun a_configured_token_is_required_even_on_the_box() =
        testApplication {
            application { watchTogetherModule(metricsToken = "metrics-secret-token-1234") }

            assertEquals(HttpStatusCode.Forbidden, client.get("/watch/metrics").status)
            val wrong = client.get("/watch/metrics") { header(HttpHeaders.Authorization, "Bearer nope") }
            assertEquals(HttpStatusCode.Forbidden, wrong.status)
            val right =
                client.get("/watch/metrics") {
                    header(HttpHeaders.Authorization, "Bearer metrics-secret-token-1234")
                    header("X-Forwarded-For", "203.0.113.9")
                }
            assertEquals(HttpStatusCode.OK, right.status)
        }

    @Test
    fun access_rule_does_not_trust_a_public_peer_without_a_token() {
        assertFalse(metricsRequestAllowed("203.0.113.9", headersOf(), metricsToken = null))
        assertTrue(metricsRequestAllowed("127.0.0.1", headersOf(), metricsToken = null))
        assertFalse(metricsRequestAllowed("127.0.0.1", headersOf("X-Real-IP", "203.0.113.9"), metricsToken = null))
    }
}
