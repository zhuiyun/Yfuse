package com.yfuse.watch.migration

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MigrationRelayRoutesTest {
    @Test
    fun createIs201AndResponsesAreNeverCached() = testApplication {
        val backend = MigrationRelayBackend.inMemory(codeGenerator = { "000007" })
        application { routing { migrationRelayRoutes(backend) } }
        val response = client.post("/api/v1/migration-relays") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(createJson())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
        backend.close()
    }

    @Test
    fun untrustedForwardedProtoCannotMarkLoopbackProxyAsSecure() = testApplication {
        val backend = MigrationRelayBackend.inMemory(codeGenerator = { "000007" })
        application {
            routing {
                migrationRelayRoutes(
                    backend,
                    clientIpResolver = { "203.0.113.1" },
                    trustProxyHeaders = false,
                )
            }
        }
        val response = client.post("/api/v1/migration-relays") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header("X-Forwarded-Proto", "https")
            setBody(createJson())
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        backend.close()
    }

    @Test
    fun trusted_proxy_uses_distinct_forwarded_clients_for_rate_limits() = testApplication {
        val backend = MigrationRelayBackend.inMemory(codeGenerator = { "000007" })
        application {
            routing {
                migrationRelayRoutes(
                    backend,
                    trustProxyHeaders = true,
                )
            }
        }
        var lastStatus = HttpStatusCode.OK
        repeat(21) { index ->
            val response = client.post("/api/v1/migration-relays") {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header("X-Forwarded-Proto", "https")
                header("X-Forwarded-For", "198.51.100.${index + 1}")
                setBody(createJson(index + 1))
            }
            lastStatus = response.status
        }
        assertNotEquals(HttpStatusCode.TooManyRequests, lastStatus)
        backend.close()
    }

    @Test
    fun redeemIs200AndConsumesTicket() = testApplication {
        val backend = MigrationRelayBackend.inMemory(codeGenerator = { "000007" })
        application { routing { migrationRelayRoutes(backend) } }
        client.post("/api/v1/migration-relays") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(createJson())
        }
        val first = client.post("/api/v1/migration-relays/redeem") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(redeemJson())
        }
        val second = client.post("/api/v1/migration-relays/redeem") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(redeemJson())
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.BadRequest, second.status)
        backend.close()
    }

    private fun createJson(offset: Int = 0): String =
        """{"relayId":"${encoded(1 + offset)}","transferSecret":"${encoded(41)}","payloadSha256":"${encoded(81)}"}"""

    private fun redeemJson(): String =
        """{"relayId":"${encoded(1)}","code":"000007","payloadSha256":"${encoded(81)}"}"""

    private fun encoded(start: Int): String =
        MigrationRelayBackend.encode(ByteArray(32) { (it + start).toByte() })
}
