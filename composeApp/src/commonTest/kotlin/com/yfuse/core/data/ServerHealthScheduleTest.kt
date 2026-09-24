package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerHealthScheduleTest {
    private val probes = mutableMapOf<String, Int>()
    private val refused = mutableSetOf<String>()
    private val unreachable = mutableSetOf<String>()

    private val repo =
        testRepo { request ->
            val host = request.url.host
            probes[host] = (probes[host] ?: 0) + 1
            when {
                host in unreachable -> throw IOException("connect timed out")
                host in refused && request.headers["X-Emby-Token"] == "old-token" ->
                    respond("", HttpStatusCode.Unauthorized)
                else -> json("{}")
            }
        }

    @Test
    fun automatic_rounds_leave_a_refused_credential_alone_until_it_is_replaced() =
        runTest {
            refused += "refused.example"
            val registry = registryOf(server("refused"), server("fine"))
            val monitor = startedMonitor(registry)

            monitor.setAppForeground(true)
            runCurrent()
            assertEquals(1, probes["refused.example"])
            assertEquals(ServerHealthStatus.AuthRequired, monitor.health.value["refused"]?.status)

            minutes(3)
            assertEquals(1, probes["refused.example"])
            assertEquals(4, probes["fine.example"])

            // Signing in again is the one thing that can change the answer: probe it at once.
            registry.addOrUpdate(server("refused").copy(accessToken = "new-token"))
            runCurrent()
            assertEquals(2, probes["refused.example"])
            assertEquals(ServerHealthStatus.Healthy, monitor.health.value["refused"]?.status)
        }

    @Test
    fun offline_servers_are_probed_on_a_doubling_backoff_that_an_answer_resets() =
        runTest {
            unreachable += "dead.example"
            val monitor = startedMonitor(registryOf(server("dead")))

            monitor.setAppForeground(true)
            runCurrent()
            assertEquals(1, probes["dead.example"])
            // Due after one, two, then four minutes.
            minutes(1)
            assertEquals(2, probes["dead.example"])
            minutes(1)
            assertEquals(2, probes["dead.example"])
            minutes(1)
            assertEquals(3, probes["dead.example"])
            minutes(3)
            assertEquals(3, probes["dead.example"])
            minutes(1)
            assertEquals(4, probes["dead.example"])

            unreachable -= "dead.example"
            minutes(8)
            assertEquals(5, probes["dead.example"])
            assertEquals(ServerHealthStatus.Healthy, monitor.health.value["dead"]?.status)
            // Answering again puts it back on the ordinary minute cadence.
            minutes(1)
            assertEquals(6, probes["dead.example"])
        }

    @Test
    fun closing_the_player_resumes_probing_without_a_round_of_its_own() =
        runTest {
            val monitor = startedMonitor(registryOf(server("fine")))
            monitor.setAppForeground(true)
            runCurrent()
            assertEquals(1, probes["fine.example"])

            // MainActivity stops under the full-screen player and starts again as it closes,
            // while the player is still flagged visible.
            monitor.setPlayerVisible(true)
            monitor.setAppForeground(false)
            minutes(5)
            monitor.setAppForeground(true)
            monitor.setPlayerVisible(false)
            runCurrent()
            assertEquals(1, probes["fine.example"])

            minutes(1)
            assertEquals(2, probes["fine.example"])

            // A real return from the background re-checks what is due.
            monitor.setAppForeground(false)
            minutes(5)
            monitor.setAppForeground(true)
            runCurrent()
            assertEquals(3, probes["fine.example"])
        }

    @Test
    fun an_explicit_refresh_probes_every_server_whatever_its_last_verdict() =
        runTest {
            refused += "refused.example"
            unreachable += "dead.example"
            val refusedServer = server("refused")
            val deadServer = server("dead")
            val monitor = startedMonitor(registryOf(refusedServer, deadServer))
            monitor.setAppForeground(true)
            runCurrent()

            monitor.refreshAll(listOf(refusedServer, deadServer))

            assertEquals(2, probes["refused.example"])
            assertEquals(2, probes["dead.example"])
        }

    @Test
    fun background_work_skips_refused_and_backed_off_servers_only() =
        runTest {
            refused += "refused.example"
            unreachable += "dead.example"
            val refusedServer = server("refused")
            val deadServer = server("dead")
            val fineServer = server("fine")
            val monitor = ServerHealthMonitor(repo, testRegistry(), nowEpochMs = { currentTime })

            assertTrue(monitor.allowsBackgroundWork(refusedServer))
            monitor.refreshAllResults(listOf(refusedServer, deadServer, fineServer))

            assertFalse(monitor.allowsBackgroundWork(refusedServer))
            assertFalse(monitor.allowsBackgroundWork(deadServer))
            assertTrue(monitor.allowsBackgroundWork(fineServer))
            // A new credential is not the one that was refused.
            assertTrue(monitor.allowsBackgroundWork(refusedServer.copy(accessToken = "new-token")))

            advanceTimeBy(60_000L)
            assertTrue(monitor.allowsBackgroundWork(deadServer))
            assertFalse(monitor.allowsBackgroundWork(refusedServer))
        }

    @Test
    fun offline_backoff_doubles_from_the_refresh_interval_to_half_an_hour() {
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L),
            (1..7).map { offlineProbeBackoffMs(it) / 60_000L },
        )
    }

    private fun TestScope.startedMonitor(registry: ServerRegistry): ServerHealthMonitor =
        ServerHealthMonitor(repo, registry, nowEpochMs = { currentTime }).also { it.start(backgroundScope) }

    private fun TestScope.minutes(count: Int) {
        advanceTimeBy(count * 60_000L)
        runCurrent()
    }

    private fun registryOf(vararg servers: SavedServer): ServerRegistry =
        testRegistry().apply { servers.forEach { addOrUpdate(it) } }

    private fun server(id: String) =
        SavedServer(
            id = id,
            baseUrl = "https://$id.example",
            serverName = id,
            userId = "user",
            userName = "test",
            accessToken = "old-token",
        )
}
