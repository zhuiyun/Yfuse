package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlexSourceRetryTest {
    @Test
    fun explicitSourceRetryReachesPlexThroughBothCooldownsWithoutExemptingOrdinaryRequests() =
        runTest {
            val server = SavedServer("plex", "https://plex", "Plex", "u", "User", "token", kind = MediaServerKind.Plex)
            val other = server.copy(id = "other", baseUrl = "https://other")
            var healthy = false
            val calls = mutableListOf<String>()
            val repository =
                testRepo { request ->
                    calls += request.url.host
                    assertEquals(HttpMethod.Get, request.method)
                    if (!healthy || request.url.host == "other") throw IOException("Plex unavailable")
                    when (request.url.encodedPath) {
                        "/library/sections" ->
                            json("""{"MediaContainer":{"Directory":[{"key":"1","title":"电影","type":"movie"}]}}""")
                        "/library/sections/1/all", "/library/metadata/42" ->
                            json(
                                """{"MediaContainer":{"totalSize":1,"Metadata":[{"ratingKey":"42","type":"movie",""" +
                                    """"title":"Avengers: Endgame","year":2019,"Guid":[{"id":"tmdb://299534"}]}]}}""",
                            )
                        else -> error("Unexpected Plex request: ${request.url.encodedPath}")
                    }
                }
            // Ordinary metadata calls establish the real HTTP transport cooldown, independent
            // of the repository's shorter source-lookup cache.
            repeat(3) {
                assertTrue(repository.libraries(server).isFailure)
                assertTrue(repository.libraries(other).isFailure)
            }
            val failedCalls = calls.size
            val cooled =
                repository
                    .compareSources(
                        servers = listOf(server),
                        currentServerId = server.id,
                        title = "复仇者联盟4：终局之战",
                        tmdbId = 299534,
                        mediaType = "movie",
                    ).single()
            assertFalse(cooled.reachable)
            assertEquals(failedCalls, calls.size)

            healthy = true
            val retried =
                repository
                    .compareSources(
                        servers = listOf(server),
                        currentServerId = server.id,
                        title = "复仇者联盟4：终局之战",
                        tmdbId = 299534,
                        mediaType = "movie",
                        forceRefresh = true,
                    ).single()
            assertTrue(retried.reachable)
            assertEquals("42", retried.itemId)
            assertTrue(calls.size > failedCalls)

            // The exemption ends with the explicit lookup, rather than leaking into the
            // shared Plex adapter and allowing a different cooling server to be hammered.
            val afterRetry = calls.size
            assertTrue(repository.libraries(other).isFailure)
            assertEquals(afterRetry, calls.size)
        }
}
