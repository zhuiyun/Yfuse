package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerManagementTest {
    private val jellyfin =
        SavedServer(
            id = "jellyfin",
            baseUrl = "https://jellyfin.example.com",
            serverName = "Jellyfin",
            userId = "user-1",
            userName = "User",
            accessToken = "token",
            kind = MediaServerKind.Jellyfin,
        )

    @Test
    fun jellyfin_management_lists_libraries_and_runs_real_scheduled_tasks() =
        runTest {
            val methods = mutableMapOf<String, HttpMethod>()
            val repo =
                testRepo { request ->
                    methods[request.url.encodedPath] = request.method
                    assertEquals("token", request.headers["X-Emby-Token"])
                    when (request.url.encodedPath) {
                        "/Users/user-1/Views" ->
                            json(
                                """{"Items":[{"Id":"lib-1","Name":"电影","CollectionType":"movies"}]}""",
                            )
                        "/ScheduledTasks" ->
                            json(
                                """[{"Id":"task-1","Name":"扫描媒体库","State":"Idle","LastExecutionResult":{"Status":"Completed"}}]""",
                            )
                        "/Items/lib-1/Refresh", "/ScheduledTasks/Running/task-1" -> json("{}")
                        else -> error("unexpected ${request.url}")
                    }
                }

            val snapshot = repo.serverManagement(jellyfin).getOrThrow()
            assertEquals("电影", snapshot.libraries.single().name)
            assertEquals("扫描媒体库", snapshot.tasks.single().name)
            assertTrue(snapshot.supportsScheduledTasks)
            assertTrue(repo.refreshLibrary(jellyfin, "lib-1").isSuccess)
            assertTrue(repo.runServerTask(jellyfin, "task-1").isSuccess)
            assertEquals(HttpMethod.Post, methods["/Items/lib-1/Refresh"])
            assertEquals(HttpMethod.Post, methods["/ScheduledTasks/Running/task-1"])
        }

    @Test
    fun plex_management_scans_sections_without_claiming_scheduled_tasks() =
        runTest {
            val plex =
                jellyfin.copy(
                    id = "plex",
                    baseUrl = "https://plex.example.com",
                    accessToken = "plex-token",
                    kind = MediaServerKind.Plex,
                )
            val repo =
                testRepo { request ->
                    assertEquals("plex-token", request.headers["X-Plex-Token"])
                    when (request.url.encodedPath) {
                        "/library/sections" ->
                            json(
                                """{"MediaContainer":{"Directory":[{"key":"7","title":"电影","type":"movie"}]}}""",
                            )
                        "/library/sections/7/refresh" -> json("{}")
                        else -> error("unexpected ${request.url}")
                    }
                }

            val snapshot = repo.serverManagement(plex).getOrThrow()
            assertFalse(snapshot.supportsScheduledTasks)
            assertTrue(snapshot.supportsMetadataAnalysis)
            assertTrue(repo.refreshLibrary(plex, "7").isSuccess)
        }
}
