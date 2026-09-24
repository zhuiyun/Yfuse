package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarServerHealthTest {
    @Test
    fun calendar_fan_out_leaves_out_a_server_inside_its_offline_backoff() =
        runTest {
            var now = 0L
            val requests = mutableListOf<String>()
            val external = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
            val repo =
                testRepo { request ->
                    requests += request.url.host
                    if (request.url.host == "dead.example") throw IOException("connect timed out")
                    json("""{"Items":[]}""")
                }
            try {
                val settings = MapSettings()
                val registry =
                    testRegistry().apply {
                        addOrUpdate(server("dead"))
                        addOrUpdate(server("fine"))
                    }
                val monitor = ServerHealthMonitor(repo, registry, nowEpochMs = { now })
                monitor.refreshAllResults(registry.data.value.servers)
                val schedules = OfficialAiringScheduleCatalog(external, settings)
                val calendar =
                    AiringCalendarRepository(
                        emby = repo,
                        registry = registry,
                        officialSchedules = schedules,
                        identityResolver = CalendarIdentityResolver(schedules, settings),
                        followStore = CalendarFollowStore(settings),
                        serverHealth = monitor,
                    )

                requests.clear()
                calendar.refreshAutomaticFollows()
                assertTrue("fine.example" in requests, requests.toString())
                assertFalse("dead.example" in requests, requests.toString())

                // Once its backoff has run out, the server is asked again.
                now += 60_000L
                requests.clear()
                calendar.refreshAutomaticFollows(forceRefresh = true)
                assertTrue("dead.example" in requests, requests.toString())
            } finally {
                external.close()
            }
        }

    private fun server(id: String) =
        SavedServer(
            id = id,
            baseUrl = "https://$id.example",
            serverName = id,
            userId = "user",
            userName = "test",
            accessToken = "token",
        )
}
