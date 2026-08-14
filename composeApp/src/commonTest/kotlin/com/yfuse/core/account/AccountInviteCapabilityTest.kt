package com.yfuse.core.account

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountInviteCapabilityTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun missing_capability_defaults_to_no_invite_permission() {
        val legacy =
            json.decodeFromString<AccountUser>(
                """{"id":"1","username":"zhuiyun","nickname":"云","avatarId":0,"createdAtEpochMs":1,"updatedAtEpochMs":1}""",
            )

        assertFalse(legacy.canIssueInvites())
        assertTrue(legacy.copy(capabilities = listOf(INVITE_ISSUE_CAPABILITY)).canIssueInvites())
    }

    @Test
    fun issue_invite_posts_bearer_and_decodes_one_time_code() =
        runTest {
            var authorization: String? = null
            val api =
                AccountApi(
                    createAccountClient(
                        MockEngine { request ->
                            assertEquals("/api/v1/account/invites", request.url.encodedPath)
                            assertEquals("POST", request.method.value)
                            authorization = request.headers[HttpHeaders.Authorization]
                            respond(
                                json.encodeToString(IssuedInviteCode("one-time-code-123", 2_000L)),
                                HttpStatusCode.Created,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    ),
                )

            assertEquals("one-time-code-123", api.issueInvite("account-access").code)
            assertEquals("Bearer account-access", authorization)
        }

    @Test
    fun issue_invite_keeps_forbidden_as_a_typed_error() =
        runTest {
            val api =
                AccountApi(
                    createAccountClient(
                        MockEngine {
                            respond(
                                """{"error":{"code":"forbidden","message":"denied"}}""",
                                HttpStatusCode.Forbidden,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    ),
                )

            val error = runCatching { api.issueInvite("account-access") }.exceptionOrNull()
            assertTrue(error is AccountApiException)
            assertEquals(HttpStatusCode.Forbidden, error.status)
            assertEquals("forbidden", error.code)
        }
}
