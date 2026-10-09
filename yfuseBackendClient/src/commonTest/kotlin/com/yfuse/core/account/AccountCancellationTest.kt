package com.yfuse.core.account

import com.yfuse.backend.BackendAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountCancellationTest {
    @Test
    fun cancelledAccountErrorDecodingIsNotReportedAsExpiredCredentials() =
        runTest {
            val client = cancellingClient()
            try {
                val api = AccountApi(client, backendAccess = BackendAccess(enabled = true))
                val failure = assertFailsWith<CancellationException> { api.profile("token") }
                assertEquals("decode cancelled", failure.message)
            } finally {
                client.close()
            }
        }

    @Test
    fun cancelledPlaybackErrorDecodingDoesNotTriggerAnAuthenticationRetry() =
        runTest {
            val client = cancellingClient()
            try {
                val api = PlaybackCloudApi(client, backendAccess = BackendAccess(enabled = true))
                val failure = assertFailsWith<CancellationException> { api.pull("token", 0) }
                assertEquals("decode cancelled", failure.message)
            } finally {
                client.close()
            }
        }

    private fun cancellingClient(): HttpClient =
        HttpClient(
            MockEngine {
                respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(
                createClientPlugin("CancelErrorDecode") {
                    transformResponseBody { _, _, requestedType ->
                        if (requestedType.type == ErrorEnvelope::class) throw CancellationException("decode cancelled")
                        null
                    }
                },
            )
        }
}
