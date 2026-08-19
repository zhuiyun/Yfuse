package com.yfuse.core.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaybackCloudApiTest {
    @Test
    fun response_without_an_error_envelope_keeps_the_http_status_visible() =
        runTest {
            val client = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
            try {
                val api = PlaybackCloudApi(client, "https://account.example.test")

                val error =
                    assertFailsWith<AccountApiException> {
                        api.pull("access-token", afterCursor = 0L)
                    }

                assertEquals(HttpStatusCode.ServiceUnavailable, error.status)
                assertEquals("http_503", error.code)
                assertTrue(error.message.contains("HTTP 503"))
            } finally {
                client.close()
            }
        }
}
