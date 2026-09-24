package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailLoadingPerformanceTest {
    private val server = SavedServer("one", "https://fast", "Fast", "u", "User", "token")

    @Test
    fun source_comparison_publishes_fast_results_before_slow_servers_finish() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val fastReady = CompletableDeferred<Unit>()
            val repo =
                testRepo(dispatcher = Dispatchers.Unconfined) { request ->
                    if (request.url.host == "slow") release.await()
                    json("""{"Items":[]}""")
                }
            val slow = server.copy(id = "two", baseUrl = "https://slow")
            val seen = mutableListOf<String>()
            val result =
                async {
                    repo.compareSources(listOf(slow, server), server.id, "Movie", onSource = {
                        seen.add(it.serverId)
                        if (it.serverId == server.id) fastReady.complete(Unit)
                    })
                }
            try {
                fastReady.await()
                assertEquals(listOf(server.id), seen)
                assertFalse(result.isCompleted)
            } finally {
                release.complete(Unit)
            }
            assertEquals(listOf(slow.id, server.id), result.await().map { it.serverId })
        }

    @Test
    fun failed_source_cools_down_but_explicit_retry_and_changed_credentials_can_retry() =
        runTest {
            var requests = 0
            val repo =
                testRepo(dispatcher = Dispatchers.Unconfined) {
                    requests++
                    respond("", HttpStatusCode(522, "Origin Unreachable"))
                }
            assertFalse(repo.compareSources(listOf(server), server.id, "A").single().reachable)
            val first = requests
            repo.compareSources(listOf(server), server.id, "B")
            assertEquals(first, requests)
            repo.compareSources(listOf(server), server.id, "B", forceRefresh = true)
            assertTrue(requests > first)
            val second = requests
            repo.compareSources(listOf(server.copy(accessToken = "new-token")), server.id, "B")
            assertTrue(requests > second)
        }

    @Test
    fun source_cooldown_expires_without_being_extended_by_reads() {
        var now = 0L
        val cooldown = SourceLookupCooldown(cooldownMs = 30L, nowMs = { now })
        cooldown.record(server, false)
        now = 29L
        assertTrue(cooldown.blocked(server))
        now = 30L
        assertFalse(cooldown.blocked(server))
        cooldown.record(server, false)
        cooldown.record(server, true)
        assertFalse(cooldown.blocked(server))
    }

    @Test
    fun only_full_detail_snapshots_are_painted_and_accounts_never_share_them() =
        runTest {
            val repo =
                testRepo(dispatcher = Dispatchers.Unconfined) {
                    json("""{"Id":"m1","Name":"Movie","Type":"Movie"}""")
                }
            repo.playbackItemDetail(server, "m1").getOrThrow()
            assertNull(repo.cachedItemDetail(server, "m1"))
            repo.itemDetail(server, "m1", includeInheritedPeople = false).getOrThrow()
            assertNotNull(repo.cachedItemDetail(server, "m1"))
            assertNull(repo.cachedItemDetail(server.copy(accessToken = "other"), "m1"))
            assertNull(repo.cachedItemDetail(server.copy(userId = "other"), "m1"))
        }

    @Test
    fun lightweight_detail_does_not_poison_the_playback_metadata_cache() =
        runTest {
            val fieldsSeen = mutableListOf<Set<String>>()
            val repo =
                testRepo(dispatcher = Dispatchers.Unconfined) { request ->
                    fieldsSeen +=
                        request.url.parameters["Fields"]
                            .orEmpty()
                            .split(',')
                            .toSet()
                    json("""{"Id":"m1","Name":"Movie","Type":"Movie"}""")
                }

            repo.itemDetail(server, "m1", includePlaybackFields = false).getOrThrow()
            repo.playbackItemDetail(server, "m1").getOrThrow()

            assertEquals(2, fieldsSeen.size)
            assertTrue("MediaSources" !in fieldsSeen.first())
            assertTrue("MediaSources" in fieldsSeen.last())
        }

    @Test
    fun playback_directory_still_requests_chapters_and_is_separate_from_the_lightweight_cache() =
        runTest {
            val fieldsSeen = mutableListOf<Set<String>>()
            val repo =
                testRepo(dispatcher = Dispatchers.Unconfined) { request ->
                    when {
                        request.url.encodedPath.endsWith("/Episodes") -> {
                            fieldsSeen.add(
                                request.url.parameters["Fields"]
                                    .orEmpty()
                                    .split(',')
                                    .toSet(),
                            )
                            json("""{"Items":[{"Id":"e1","Name":"First","Type":"Episode","SeriesId":"s1"}]}""")
                        }
                        request.url.encodedPath.endsWith("/Items/e1") ->
                            json("""{"Id":"e1","Name":"First","Type":"Episode","SeriesId":"s1"}""")
                        else -> json("""{"Id":"s1","Name":"Series","Type":"Series"}""")
                    }
                }
            val series = repo.itemDetail(server, "s1").getOrThrow()
            repo.resolvePlayTargetWithEpisodes(server, series).getOrThrow()
            repo.resolveSeriesPlayback(server, "s1").getOrThrow()
            assertEquals(2, fieldsSeen.size)
            assertFalse("Chapters" in fieldsSeen.first())
            assertTrue("Chapters" in fieldsSeen.last())
            assertTrue("MediaSources" in fieldsSeen.last())
        }

    @Test
    fun lightweight_episode_directory_is_shared_but_local_selection_is_recomputed() =
        runTest {
            var directoryRequests = 0
            val progress =
                com.yfuse.core.sync.playback
                    .PlaybackSyncStore(com.russhwolf.settings.MapSettings()) { 1_000L }
            val repo =
                testRepo(
                    dispatcher = Dispatchers.Unconfined,
                    progressProjection = PlaybackProgressProjection(progress),
                ) { request ->
                    if (request.url.encodedPath.endsWith("/Episodes")) {
                        directoryRequests++
                        val fields =
                            request.url.parameters["Fields"]
                                .orEmpty()
                                .split(',')
                        assertFalse("Chapters" in fields)
                        assertFalse("MediaSources" in fields)
                        json(
                            """{"Items":[{"Id":"e1","Name":"First","Type":"Episode","SeriesId":"s1"},""" +
                                """{"Id":"e2","Name":"Second","Type":"Episode","SeriesId":"s1"}]}""",
                        )
                    } else {
                        json("""{"Id":"s1","Name":"Series","Type":"Series"}""")
                    }
                }
            val series = repo.itemDetail(server, "s1").getOrThrow()
            assertEquals(
                "e1",
                repo
                    .resolvePlayTargetWithEpisodes(server, series)
                    .getOrThrow()
                    .target.itemId,
            )
            progress.seedServerProgressIfAbsent(server.id, "e1", positionMs = 0L, played = true)
            assertEquals(
                "e2",
                repo
                    .resolvePlayTargetWithEpisodes(server, series)
                    .getOrThrow()
                    .target.itemId,
            )
            assertEquals(1, directoryRequests)
            repo.resolvePlayTargetWithEpisodes(server.copy(accessToken = "refreshed"), series).getOrThrow()
            assertEquals(2, directoryRequests)
        }
}
