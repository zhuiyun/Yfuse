package com.yfuse.core.account

import com.yfuse.backend.BackendAccess
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AccountProtocolCompatibilityTest {
    private val backendAccess = BackendAccess(enabled = true)

    @Test
    fun missing_prelogin_route_uses_the_legacy_contract() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                createBackendAccountClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        respond("legacy route missing", HttpStatusCode.NotFound)
                    },
                    access = backendAccess,
                )
            try {
                assertNull(AccountApi(client, backendAccess = backendAccess).prelogin("legacy-viewer"))
                assertEquals(listOf("/api/v1/auth/prelogin"), paths)
            } finally {
                client.close()
            }
        }

    @Test
    fun unavailable_prelogin_is_not_permission_to_send_a_password() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                createBackendAccountClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        respond("temporarily unavailable", HttpStatusCode.ServiceUnavailable)
                    },
                    access = backendAccess,
                )
            try {
                val failure =
                    runCatching { AccountApi(client, backendAccess = backendAccess).prelogin("legacy-viewer") }
                        .exceptionOrNull()
                assertEquals(HttpStatusCode.ServiceUnavailable, assertIs<AccountApiException>(failure).status)
                assertEquals(listOf("/api/v1/auth/prelogin"), paths)
            } finally {
                client.close()
            }
        }
}
