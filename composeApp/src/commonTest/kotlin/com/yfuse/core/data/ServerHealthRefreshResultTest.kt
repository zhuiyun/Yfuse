package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerHealthRefreshResultTest {
    @Test
    fun concurrent_periodic_success_cannot_replace_an_explicit_rounds_actual_failure() =
        runTest {
            val registry = testRegistry()
            val blocked = CompletableDeferred<Unit>()
            val slowStarted = CompletableDeferred<Unit>()
            var aRequests = 0
            var bRequests = 0
            val repo =
                testRepo { request ->
                    when (request.url.host) {
                        "a.example" -> {
                            aRequests++
                            // A 401 intentionally activates the client's credential cooldown, so
                            // immediate recovery must use a transient server failure instead.
                            if (aRequests == 1) {
                                respond("unavailable", HttpStatusCode.ServiceUnavailable)
                            } else {
                                json("{}")
                            }
                        }
                        else -> {
                            bRequests++
                            slowStarted.complete(Unit)
                            blocked.await()
                            json("{}")
                        }
                    }
                }
            val monitor = ServerHealthMonitor(repo, registry)
            val a = server("a")
            val round = async { monitor.refreshAllResults(listOf(a, server("b"))) }
            runCurrent()
            assertTrue(slowStarted.isCompleted)
            assertEquals(1, aRequests)
            assertEquals(ServerHealthStatus.Degraded, monitor.health.value[a.id]?.status)
            // A periodic/individual refresh finishes while this round still awaits B.
            monitor.refresh(a)
            assertEquals(ServerHealthStatus.Healthy, monitor.health.value[a.id]?.status)
            blocked.complete(Unit)
            val result = round.await()
            assertTrue(result.getValue("a").isFailure)
            assertTrue(result.getValue("b").isSuccess)
            assertEquals(ServerHealthStatus.Healthy, monitor.health.value[a.id]?.status)
            assertEquals(2, aRequests)
            assertEquals(1, bRequests)
        }

    @Test
    fun cancelling_a_probe_preserves_cancellation_and_does_not_record_an_offline_failure() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val repo =
                testRepo {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    json("{}")
                }
            val monitor = ServerHealthMonitor(repo, testRegistry())
            val task = async { monitor.refreshResult(server("a")) }
            runCurrent()
            assertTrue(started.isCompleted)
            task.cancel()
            runCurrent()
            assertTrue(task.isCancelled)
            assertNull(monitor.health.value["a"])
        }

    private fun server(id: String) =
        SavedServer(
            id = id,
            baseUrl = "https://$id.example",
            serverName = id,
            userId = "user",
            userName = "test",
            accessToken = "fixture-token",
        )
}
