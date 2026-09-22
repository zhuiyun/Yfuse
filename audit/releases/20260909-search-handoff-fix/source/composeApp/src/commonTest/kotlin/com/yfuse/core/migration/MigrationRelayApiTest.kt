package com.yfuse.core.migration

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondRedirect
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationRelayApiTest {
    @Test
    fun redirect_cannot_send_transfer_secret_to_another_origin() =
        runTest {
            var requests = 0
            val client =
                createMigrationRelayClient(
                    engine =
                        MockEngine { request ->
                            requests++
                            if (request.url.host == "account.example") {
                                respondRedirect("https://attacker.example/steal")
                            } else {
                                error("Redirect target must be blocked before the engine")
                            }
                        },
                    trustedOrigin = "https://account.example",
                )
            val api = MigrationRelayApi(client, "https://account.example")

            val failure = runCatching { api.create("relay", "secret", "hash") }.exceptionOrNull()
            assertTrue(
                failure is IllegalStateException || failure is MigrationRelayApiException,
                "Redirect must fail before reaching the second engine call",
            )
            assertEquals(1, requests)
        }

    @Test
    fun official_https_origin_can_create_a_ticket() =
        runTest {
            val client =
                createMigrationRelayClient(
                    engine =
                        MockEngine {
                            respond(
                                """{"code":"000042","expiresAtEpochMs":1234}""",
                                HttpStatusCode.Created,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        },
                    trustedOrigin = "https://account.example",
                )

            val ticket =
                MigrationRelayApi(client, "https://account.example")
                    .create("relay", "secret", "hash")

            assertEquals("000042", ticket.code)
        }
}
