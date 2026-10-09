package com.yfuse.core.account

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendBuildConfig
import com.yfuse.backend.BackendDiagnostics
import com.yfuse.backend.BackendUnavailableException
import com.yfuse.core.sync.playback.PlaybackPushRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AccountBackendBoundaryTest {
    @Test
    fun disabledAccountRejectsEveryOperationBeforeSendingCredentials() =
        runTest {
            var requests = 0
            val client =
                enabledAccountClient(
                    MockEngine {
                        requests++
                        error("A disabled backend must never reach the engine")
                    },
                )
            try {
                val api = AccountApi(client, backendAccess = BackendAccess(enabled = false))
                val encrypted = EncryptedSyncPayload(nonce = "nonce", ciphertext = "ciphertext")
                val password =
                    ChangePasswordRequest(
                        currentPassword = "current-secret",
                        newPassword = "new-secret",
                        expectedSyncVersion = 1,
                        keyVersion = 1,
                        wrappedVaultKey = "wrapped",
                        wrapSalt = "salt",
                        wrapNonce = "nonce",
                        wrapVersion = 1,
                        wrapKdf = "PBKDF2",
                        wrapIterations = 100_000,
                    )
                val operations: List<suspend () -> Unit> =
                    listOf(
                        { api.register("user", "password", null, null) },
                        { api.login("user", "password") },
                        { api.refresh("refresh-secret", "device", "request-id") },
                        { api.logout("access-secret") },
                        { api.profile("access-secret") },
                        { api.updateProfile("access-secret", "nickname") },
                        { api.changePassword("access-secret", password) },
                        { api.getSync("access-secret") },
                        { api.putSync("access-secret", 1, encrypted) },
                        { api.clearSync("access-secret") },
                        { api.sessions("access-secret") },
                        { api.issueInvite("access-secret") },
                        { api.revokeSession("access-secret", "session-id") },
                        { api.revokeOtherSessions("access-secret") },
                        { api.revokeAllSessions("access-secret") },
                        { api.exportAccount("access-secret") },
                        { api.deleteAccount("access-secret", "password") },
                    )
                operations.forEach { operation ->
                    assertFailsWith<BackendUnavailableException> { operation() }
                }
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun disabledCloudPlaybackRejectsPullAndPushBeforeSendingEncryptedDocuments() =
        runTest {
            var requests = 0
            val client =
                enabledAccountClient(
                    MockEngine {
                        requests++
                        error("A disabled backend must never reach the engine")
                    },
                )
            try {
                val cloud = PlaybackCloudApi(client, backendAccess = BackendAccess(enabled = false))
                assertFailsWith<BackendUnavailableException> { cloud.pull("access-secret", 0) }
                assertFailsWith<BackendUnavailableException> {
                    cloud.push("access-secret", PlaybackPushRequest(emptyList()))
                }
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun disabledTokenSourceDoesNotRestoreOrRefreshAnAccountSession() =
        runTest {
            var providerCalls = 0
            val source = AccountAccessTokenSource(backendAccess = BackendAccess(enabled = false))
            source.bind(
                provider = {
                    providerCalls++
                    "access-secret"
                },
                refreshProvider = {
                    providerCalls++
                    "refreshed-secret"
                },
            )
            source.markAvailable()
            assertFalse(source.sessionAvailable.value)
            assertNull(source.validAccessTokenFor(ACCOUNT_BASE_URL))
            assertNull(source.refreshAccessTokenFor(ACCOUNT_BASE_URL))
            assertEquals(0, providerCalls)
        }

    @Test
    fun enabledPlaybackRetainsAuthenticatedEndpointAndCursorBounds() =
        runTest {
            val client =
                enabledAccountClient(
                    MockEngine { request ->
                        assertEquals(
                            "https://account.example/api/v1/account/playback",
                            request.url.toString().substringBefore('?'),
                        )
                        assertEquals("Bearer access-secret", request.headers[HttpHeaders.Authorization])
                        assertEquals("0", request.url.parameters["after"])
                        assertEquals("200", request.url.parameters["limit"])
                        respond(
                            content = """{"cursor":7,"changes":[],"hasMore":false}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            try {
                val cloud =
                    PlaybackCloudApi(
                        client,
                        baseUrl = "https://account.example",
                        backendAccess = BackendAccess(enabled = true),
                    )
                assertEquals(7L, cloud.pull("access-secret", -1, limit = 400).cursor)
            } finally {
                client.close()
            }
        }

    @Test
    fun enabledAccountKeepsLegacyRefreshFallbackAndRedactedDiagnostics() =
        runTest {
            var requests = 0
            val events = mutableListOf<String>()
            val diagnostics =
                object : BackendDiagnostics {
                    override fun info(
                        event: String,
                        message: String,
                        attributes: Map<String, String>,
                    ) {
                        events += event
                        assertFalse((message + attributes).contains("refresh-secret"))
                    }

                    override fun warning(
                        event: String,
                        message: String,
                        attributes: Map<String, String>,
                    ) {
                        events += event
                        assertFalse((message + attributes).contains("refresh-secret"))
                    }
                }
            val client =
                enabledAccountClient(
                    MockEngine {
                        requests++
                        if (requests == 1) {
                            respond(
                                content = """{"error":{"code":"invalid_json","message":"legacy schema"}}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = AUTH_RESPONSE,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                )
            try {
                val api =
                    AccountApi(
                        client,
                        backendAccess = BackendAccess(enabled = true),
                        diagnostics = diagnostics,
                    )
                assertEquals("user-id", api.refresh("refresh-secret", "device", "request-id").user.id)
                assertEquals(2, requests)
                assertEquals(listOf("refresh_legacy_schema_fallback", "refresh_legacy_schema_result"), events)
            } finally {
                client.close()
            }
        }

    @Test
    fun generatedBuildSwitchControlsUnconfiguredProductionApiAndClient() =
        runTest {
            var requests = 0
            val client =
                createBackendAccountClient(
                    MockEngine {
                        requests++
                        respond(
                            content = AUTH_RESPONSE,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            try {
                val api = AccountApi(client, baseUrl = "https://account.example")
                if (BackendBuildConfig.ENABLED) {
                    assertEquals("user-id", api.login("user", "password").user.id)
                    assertEquals(1, requests)
                } else {
                    assertFailsWith<BackendUnavailableException> { api.login("user", "password") }
                    assertEquals(0, requests)
                }
            } finally {
                client.close()
            }
        }

    private companion object {
        val AUTH_RESPONSE =
            """
            {
              "user":{"id":"user-id","username":"user","nickname":"name","avatarId":0,
                      "createdAtEpochMs":0,"updatedAtEpochMs":0},
              "accessToken":"new-access","accessExpiresAtEpochMs":100,
              "refreshToken":"new-refresh","refreshExpiresAtEpochMs":200
            }
            """.trimIndent()
    }
}

private fun enabledAccountClient(engine: HttpClientEngine): HttpClient =
    createBackendAccountClient(engine, access = BackendAccess(enabled = true))
