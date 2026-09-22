package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountRateLimiterTest {
    @Test
    fun credentials_share_a_bucket_and_limit_before_json_or_pbkdf2_work() {
        var nowEpochMs = 1_700_000_000_000L
        val limiter =
            AccountRateLimiter(
                policy =
                    AccountRateLimitPolicy(
                        credentialAttemptsPerWindow = 2,
                        credentialWindowMs = 60_000L,
                        refreshAttemptsPerWindow = 4,
                        refreshWindowMs = 60_000L,
                        maxTrackedEntries = 20,
                        cleanupIntervalMs = 5_000L,
                    ),
                clock = { nowEpochMs },
            )
        testApplication {
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(),
                    accountRateLimiter = limiter,
                )
            }

            val login =
                client.post("/api/v1/auth/login") {
                    secureJsonFrom("198.51.100.7", """{"username":"Nobody","password":"Wrong-Pass-42"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, login.status)

            val register =
                client.post("/api/v1/auth/register") {
                    secureJsonFrom("198.51.100.7", REGISTER_BODY)
                }
            assertEquals(HttpStatusCode.Created, register.status)

            // Malformed JSON would be 400 if parsed. Receiving 429 demonstrates that the
            // shared register/login bucket runs before body parsing and the password KDF.
            val limited =
                client.post("/api/v1/auth/register") {
                    secureJsonFrom("198.51.100.7", "{")
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.errorCode())
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])

            val otherIp =
                client.post("/api/v1/auth/login") {
                    secureJsonFrom("198.51.100.8", "{")
                }
            assertEquals(HttpStatusCode.BadRequest, otherIp.status)
            assertEquals("invalid_json", otherIp.errorCode())

            nowEpochMs += 60_000L
            val afterWindow =
                client.post("/api/v1/auth/login") {
                    secureJsonFrom("198.51.100.7", "{")
                }
            assertEquals(HttpStatusCode.BadRequest, afterWindow.status)
        }
    }

    @Test
    fun refresh_uses_an_independent_wider_bucket() =
        testApplication {
            val limiter =
                AccountRateLimiter(
                    AccountRateLimitPolicy(
                        credentialAttemptsPerWindow = 1,
                        credentialWindowMs = 60_000L,
                        refreshAttemptsPerWindow = 2,
                        refreshWindowMs = 60_000L,
                        maxTrackedEntries = 20,
                        cleanupIntervalMs = 5_000L,
                    ),
                )
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(),
                    accountRateLimiter = limiter,
                )
            }

            val registered =
                client.post("/api/v1/auth/register") {
                    secureJsonFrom("203.0.113.9", REGISTER_BODY)
                }
            assertEquals(HttpStatusCode.Created, registered.status)
            var refreshToken = registered.bodyAsText().jsonString("refreshToken")

            repeat(2) {
                val refreshed =
                    client.post("/api/v1/auth/refresh") {
                        secureJsonFrom("203.0.113.9", """{"refreshToken":"$refreshToken"}""")
                    }
                assertEquals(HttpStatusCode.OK, refreshed.status)
                refreshToken = refreshed.bodyAsText().jsonString("refreshToken")
            }

            val limited =
                client.post("/api/v1/auth/refresh") {
                    secureJsonFrom("203.0.113.9", "{")
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("rate_limited", limited.errorCode())
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun loopback_proxy_requires_one_ip_literal_and_public_peers_cannot_spoof_identity() =
        testApplication {
            val limiter =
                AccountRateLimiter(
                    AccountRateLimitPolicy(
                        credentialAttemptsPerWindow = 2,
                        maxTrackedEntries = 20,
                    ),
                )
            application {
                watchTogetherModule(
                    accountBackend = AccountBackend.inMemoryForTests(),
                    accountRateLimiter = limiter,
                )
            }

            val commaChain =
                client.post("/api/v1/auth/login") {
                    secureJsonFrom(
                        "198.51.100.7, 198.51.100.8",
                        """{"username":"Nobody","password":"Wrong-Pass-42"}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, commaChain.status)
            assertEquals("forwarded_for_invalid", commaChain.errorCode())
            assertEquals(0, limiter.trackedEntryCount())

            val invalidHost =
                client.post("/api/v1/auth/login") {
                    secureJsonFrom(
                        "client.example.invalid",
                        """{"username":"Nobody","password":"Wrong-Pass-42"}""",
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, invalidHost.status)
            assertEquals("forwarded_for_invalid", invalidHost.errorCode())
            assertEquals(0, limiter.trackedEntryCount())

            assertEquals(
                ClientIdentityResolution.Resolved("203.0.113.10"),
                resolveAccountClientIdentity(
                    remoteHost = "203.0.113.10",
                    forwardedForValues = listOf("192.0.2.99"),
                ),
            )
            assertEquals(
                ClientIdentityResolution.Resolved("2001:db8:0:0:0:0:0:7"),
                resolveAccountClientIdentity(
                    remoteHost = "127.0.0.1",
                    forwardedForValues = listOf("2001:db8::7"),
                ),
            )
            assertEquals(
                ClientIdentityResolution.InvalidForwardedFor,
                resolveAccountClientIdentity(
                    remoteHost = "127.0.0.1",
                    forwardedForValues = listOf("192.0.2.1", "192.0.2.2"),
                ),
            )
        }

    @Test
    fun limiter_bounds_memory_without_evicting_live_buckets_and_cleans_expired_entries() {
        var nowEpochMs = 0L
        val limiter =
            AccountRateLimiter(
                policy =
                    AccountRateLimitPolicy(
                        credentialAttemptsPerWindow = 2,
                        credentialWindowMs = 1_000L,
                        refreshAttemptsPerWindow = 2,
                        refreshWindowMs = 1_000L,
                        maxTrackedEntries = 2,
                        cleanupIntervalMs = 10_000L,
                    ),
                clock = { nowEpochMs },
            )

        assertEquals(
            RateLimitDecision.Allowed,
            limiter.check("192.0.2.1", AccountRateLimitBucket.Credentials),
        )
        assertEquals(
            RateLimitDecision.Allowed,
            limiter.check("192.0.2.2", AccountRateLimitBucket.Refresh),
        )
        assertEquals(2, limiter.trackedEntryCount())
        val atCapacity =
            assertIs<RateLimitDecision.Limited>(
                limiter.check("192.0.2.3", AccountRateLimitBucket.Credentials),
            )
        assertEquals(1L, atCapacity.retryAfterSeconds)
        assertEquals(2, limiter.trackedEntryCount())

        nowEpochMs = 1_000L
        assertEquals(
            RateLimitDecision.Allowed,
            limiter.check("192.0.2.3", AccountRateLimitBucket.Credentials),
        )
        assertEquals(1, limiter.trackedEntryCount())
    }

    companion object {
        private const val REGISTER_BODY =
            """{"username":"Alice","password":"Correct-Horse-42","nickname":"小鱼","avatarId":2}"""
    }
}

private fun HttpRequestBuilder.secureJsonFrom(
    clientIp: String,
    body: String,
) {
    header("X-Forwarded-Proto", "https")
    header("X-Forwarded-For", clientIp)
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun String.jsonString(name: String): String =
    Json
        .parseToJsonElement(this)
        .jsonObject
        .getValue(name)
        .jsonPrimitive.content

private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
    bodyAsText()
        .let(Json::parseToJsonElement)
        .jsonObject
        .getValue("error")
        .jsonObject
        .getValue("code")
        .jsonPrimitive
        .content
