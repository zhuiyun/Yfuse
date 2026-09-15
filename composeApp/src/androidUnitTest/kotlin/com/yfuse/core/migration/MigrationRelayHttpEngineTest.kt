package com.yfuse.core.migration

import com.yfuse.core.network.embyRequestDispatcher
import com.yfuse.core.network.sharedOriginConnectionPool
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MigrationRelayHttpEngineTest {
    @Test
    fun default_migration_engine_does_not_borrow_the_shared_origin_connection() =
        runBlocking {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
                server.start()
                val migrationEngine = migrationRelayHttpEngine()
                val sharedClient = sharedOriginClient()
                val migrationClient = HttpClient(migrationEngine)
                val url = server.url("/default-pool-ownership").toString()
                try {
                    assertEquals("ok", sharedClient.readText(url))
                    assertEquals(0, assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).sequenceNumber)
                    assertEquals("ok", migrationClient.get(url).bodyAsText())
                    assertEquals(0, assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).sequenceNumber)
                } finally {
                    migrationClient.close()
                    migrationEngine.close()
                    sharedClient.connectionPool.evictAll()
                    sharedClient.dispatcher.executorService.shutdown()
                }
            }
        }

    @Test
    fun closing_migration_engine_keeps_the_shared_origin_connection_reusable() =
        runBlocking {
            MockWebServer().use { server ->
                repeat(3) { server.enqueue(MockResponse().setBody("ok")) }
                server.start()
                val migrationPool = ConnectionPool()
                val migrationDispatcher = embyRequestDispatcher()
                val migrationEngine = migrationRelayHttpEngine(migrationPool, migrationDispatcher)
                val sharedClient = sharedOriginClient()
                val migrationClient = HttpClient(migrationEngine)
                val url = server.url("/pool-ownership").toString()
                try {
                    assertEquals("ok", sharedClient.readText(url))
                    assertEquals(0, assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).sequenceNumber)

                    assertEquals("ok", migrationClient.get(url).bodyAsText())
                    // The same server must get a separate connection for the page-owned client.
                    assertEquals(0, assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).sequenceNumber)
                    migrationClient.close()
                    migrationEngine.close()
                    // Ktor's parent job need not finish; observe the resources that close() owns.
                    withTimeout(5_000L) {
                        while (!migrationDispatcher.executorService.isShutdown) delay(10L)
                    }
                    assertEquals(0, migrationPool.connectionCount())

                    assertEquals("ok", sharedClient.readText(url))
                    // Ktor's completed cleanup must leave the application's original TCP connection alive.
                    assertEquals(1, assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).sequenceNumber)
                } finally {
                    migrationClient.close()
                    migrationEngine.close()
                    sharedClient.connectionPool.evictAll()
                    sharedClient.dispatcher.executorService.shutdown()
                }
            }
        }

    // Model an application consumer of the real shared pool, with synchronous test-owned cleanup.
    // Closing an extra Ktor Emby engine here would asynchronously evict the next test's connections.
    private fun sharedOriginClient(): OkHttpClient =
        OkHttpClient.Builder().connectionPool(sharedOriginConnectionPool).build()

    private fun OkHttpClient.readText(url: String): String =
        newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertNotNull(response.body).string()
        }
}
