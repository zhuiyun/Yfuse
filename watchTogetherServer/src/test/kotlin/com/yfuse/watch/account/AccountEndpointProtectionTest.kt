package com.yfuse.watch.account

import com.yfuse.watch.watchTogetherModule
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountEndpointProtectionTest {
    @Test
    fun logout_and_profile_endpoints_have_independent_per_ip_limits() =
        testApplication {
            val limiter =
                AccountRateLimiter(
                    AccountRateLimitPolicy(
                        credentialAttemptsPerWindow = 5,
                        logoutAttemptsPerWindow = 1,
                        profileReadAttemptsPerWindow = 1,
                        profileWriteAttemptsPerWindow = 1,
                        maxTrackedEntries = 20,
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
                    protectedJsonFrom(CLIENT_IP, REGISTER_BODY)
                }
            assertEquals(HttpStatusCode.Created, registered.status)
            val accessToken = registered.bodyAsText().protectedString("accessToken")

            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/v1/account/profile") {
                        protectedBearerFrom(CLIENT_IP, accessToken)
                    }.status,
            )
            assertRateLimited(
                client.get("/api/v1/account/profile") {
                    protectedBearerFrom(CLIENT_IP, accessToken)
                },
            )

            // The write bucket is independent from reads and runs before JSON parsing.
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put("/api/v1/account/profile") {
                        protectedJsonFrom(CLIENT_IP, """{"nickname":"新昵称"}""")
                        protectedBearer(accessToken)
                    }.status,
            )
            assertRateLimited(
                client.put("/api/v1/account/profile") {
                    protectedJsonFrom(CLIENT_IP, "{")
                    protectedBearer(accessToken)
                },
            )

            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .post("/api/v1/auth/logout") {
                        protectedBearerFrom(CLIENT_IP, accessToken)
                    }.status,
            )
            // The session is now invalid, so 429 proves the IP bucket runs before authentication.
            assertRateLimited(
                client.post("/api/v1/auth/logout") {
                    protectedBearerFrom(CLIENT_IP, accessToken)
                },
            )
        }

    @Test
    fun maximum_ciphertext_round_trips_within_derived_body_limit_and_one_extra_byte_is_rejected() =
        testApplication {
            application {
                watchTogetherModule(accountBackend = AccountBackend.inMemoryForTests())
            }

            val registered =
                client.post("/api/v1/auth/register") {
                    protectedJsonFrom(CLIENT_IP, REGISTER_BODY)
                }
            assertEquals(HttpStatusCode.Created, registered.status)
            val accessToken = registered.bodyAsText().protectedString("accessToken")
            val maximumCiphertext = protectedBase64(ByteArray(AccountLimits.MAX_CIPHERTEXT_BYTES))
            assertEquals(
                AccountLimits.MAX_BASE64URL_CIPHERTEXT_BYTES,
                maximumCiphertext.length,
            )
            val maximumRequest =
                protectedSyncBody(
                    baseVersion = 0,
                    nonceByte = 1,
                    ciphertext = maximumCiphertext,
                    includeWrap = true,
                )
            assertTrue(maximumRequest.toByteArray().size <= AccountLimits.MAX_REQUEST_BYTES)

            val uploaded =
                client.put("/api/v1/account/sync") {
                    protectedJsonFrom(CLIENT_IP, maximumRequest)
                    protectedBearer(accessToken)
                }
            assertEquals(HttpStatusCode.OK, uploaded.status)
            assertTrue(uploaded.bodyAsText().toByteArray().size <= AccountLimits.MAX_RESPONSE_BYTES)

            val fetched =
                client.get("/api/v1/account/sync") {
                    protectedBearerFrom(CLIENT_IP, accessToken)
                }
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertTrue(fetched.bodyAsText().toByteArray().size <= AccountLimits.MAX_RESPONSE_BYTES)

            val oversizedCiphertext =
                protectedBase64(
                    ByteArray(AccountLimits.MAX_CIPHERTEXT_BYTES + 1),
                )
            val oversizedRequest =
                protectedSyncBody(
                    baseVersion = 1,
                    nonceByte = 2,
                    ciphertext = oversizedCiphertext,
                    includeWrap = false,
                )
            // The JSON transport accepts the envelope; decoded-ciphertext validation owns this
            // boundary and must reject it with a useful client error.
            assertTrue(oversizedRequest.toByteArray().size <= AccountLimits.MAX_REQUEST_BYTES)
            val rejected =
                client.put("/api/v1/account/sync") {
                    protectedJsonFrom(CLIENT_IP, oversizedRequest)
                    protectedBearer(accessToken)
                }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertEquals("sync_envelope_invalid", rejected.protectedErrorCode())
        }

    @Test
    fun body_limit_math_covers_unpadded_base64url_boundaries() {
        assertEquals(0, base64UrlEncodedLength(0))
        assertEquals(2, base64UrlEncodedLength(1))
        assertEquals(3, base64UrlEncodedLength(2))
        assertEquals(4, base64UrlEncodedLength(3))
        assertEquals(6, base64UrlEncodedLength(4))
        assertEquals(
            AccountLimits.MAX_BASE64URL_CIPHERTEXT_BYTES +
                AccountLimits.MAX_SYNC_JSON_OVERHEAD_BYTES,
            AccountLimits.MAX_REQUEST_BYTES,
        )
        assertEquals(AccountLimits.MAX_REQUEST_BYTES, AccountLimits.MAX_RESPONSE_BYTES)
    }

    private suspend fun assertRateLimited(response: HttpResponse) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("rate_limited", response.protectedErrorCode())
        assertEquals("60", response.headers[HttpHeaders.RetryAfter])
    }

    companion object {
        private const val CLIENT_IP = "198.51.100.44"
        private const val REGISTER_BODY =
            """{"username":"Alice","password":"Correct-Horse-42","nickname":"小鱼","avatarId":2}"""
    }
}

private fun HttpRequestBuilder.protectedJsonFrom(
    clientIp: String,
    body: String,
) {
    header("X-Forwarded-Proto", "https")
    header("X-Forwarded-For", clientIp)
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun HttpRequestBuilder.protectedBearerFrom(
    clientIp: String,
    token: String,
) {
    header("X-Forwarded-Proto", "https")
    header("X-Forwarded-For", clientIp)
    protectedBearer(token)
}

private fun HttpRequestBuilder.protectedBearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun HttpResponse.protectedErrorCode(): String =
    bodyAsText()
        .let(Json::parseToJsonElement)
        .jsonObject
        .getValue("error")
        .jsonObject
        .getValue("code")
        .jsonPrimitive.content

private fun String.protectedString(name: String): String =
    Json
        .parseToJsonElement(this)
        .jsonObject
        .getValue(name)
        .jsonPrimitive.content

private fun protectedSyncBody(
    baseVersion: Long,
    nonceByte: Byte,
    ciphertext: String,
    includeWrap: Boolean,
): String {
    val wrap =
        if (includeWrap) {
            """,
          "wrapVersion":1,
          "wrapKdf":"PBKDF2-HMAC-SHA256",
          "wrapIterations":600000,
          "wrappedVaultKey":"${protectedBase64(ByteArray(48) { 3 })}",
          "wrapSalt":"${protectedBase64(ByteArray(16) { 4 })}",
          "wrapNonce":"${protectedBase64(ByteArray(12) { 5 })}""""
        } else {
            ""
        }
    return """{
      "baseVersion":$baseVersion,
      "payload":{
        "schemaVersion":1,
        "algorithm":"AES-256-GCM",
        "keyVersion":1,
        "nonce":"${protectedBase64(ByteArray(12) { nonceByte })}",
        "ciphertext":"$ciphertext"$wrap
      }
    }"""
}

private fun protectedBase64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
