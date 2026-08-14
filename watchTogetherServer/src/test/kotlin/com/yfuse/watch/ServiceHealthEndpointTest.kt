package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.migration.MigrationRelayBackend
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceHealthEndpointTest {
    @Test
    fun health_endpoint_returns_dependency_readiness_json() = testApplication {
        val accountBackend = AccountBackend.inMemory()
        val migrationBackend = MigrationRelayBackend.inMemory()
        val migrationExecutor =
            AccountWorkExecutor(
                AccountExecutionPolicy(workerThreads = 1, maxConcurrentOperations = 1),
            )
        application {
            watchTogetherModule(
                accountBackend = accountBackend,
                migrationRelayBackend = migrationBackend,
                migrationRelayWorkExecutor = migrationExecutor,
            )
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""))
        assertTrue(body.contains("\"accountDatabase\":\"ok\""))
        assertTrue(body.contains("\"migrationExecutor\":\"ok\""))
    }
}
