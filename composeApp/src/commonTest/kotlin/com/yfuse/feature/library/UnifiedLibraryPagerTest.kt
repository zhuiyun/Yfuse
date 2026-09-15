package com.yfuse.feature.library

import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedLibraryPagerTest {
    private fun server(id: String) = SavedServer(id, "https://$id.example", id, "user", "user", "token")

    private fun movie(
        id: String,
        tmdb: String = id,
    ) = MediaItem(
        id,
        "影片 $tmdb",
        null,
        "Movie",
        id,
        null,
        null,
        null,
        null,
        providerIds = mapOf("Tmdb" to tmdb),
    )

    @Test
    fun deduplication_keeps_sources_and_advances_raw_offsets() =
        runTest {
            val offsets = mutableListOf<Pair<String, Int>>()
            val pager =
                UnifiedLibraryPager(
                    libraries = { Result.success(listOf(MediaLibrary("movies", "Movies", "movies"))) },
                    page = { server, _, offset, _, _ ->
                        offsets += server.id to offset
                        Result.success(LibraryPage(listOf(movie("${server.id}-$offset", "$offset")), 2, offset))
                    },
                )
            pager.reset(listOf(server("a"), server("b")), UnifiedLibraryQuery())
            pager.loadMore()
            assertEquals(1, pager.state.value.groups.size)
            assertEquals(
                2,
                pager.state.value.groups
                    .single()
                    .copies.size,
            )
            pager.loadMore()
            assertEquals(listOf("a" to 0, "b" to 0, "a" to 1, "b" to 1), offsets)
            assertEquals(2, pager.state.value.groups.size)
            assertFalse(pager.state.value.hasMore)
        }

    @Test
    fun failure_does_not_hide_other_servers_and_retries_the_failed_offset() =
        runTest {
            var fail = true
            val pager =
                UnifiedLibraryPager(
                    libraries = { Result.success(listOf(MediaLibrary("movies", "Movies", "movies"))) },
                    page = { server, _, offset, _, _ ->
                        if (server.id == "b" && fail) {
                            Result.failure(IllegalStateException("Offline"))
                        } else {
                            Result.success(LibraryPage(listOf(movie(server.id)), 1, offset))
                        }
                    },
                )
            pager.reset(listOf(server("a"), server("b")), UnifiedLibraryQuery())
            pager.loadMore()
            assertEquals(1, pager.state.value.groups.size)
            assertEquals(1, pager.state.value.failures.size)
            fail = false
            pager.loadMore(retryFailures = true)
            assertEquals(2, pager.state.value.groups.size)
            assertTrue(
                pager.state.value.failures
                    .isEmpty(),
            )
        }

    @Test
    fun an_old_request_cannot_publish_into_a_new_profile() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val pager =
                UnifiedLibraryPager(
                    libraries = { Result.success(listOf(MediaLibrary("movies", "Movies", "movies"))) },
                    page = { server, _, offset, _, _ ->
                        if (server.id == "old") {
                            started.complete(Unit)
                            response.await()
                        }
                        Result.success(LibraryPage(listOf(movie(server.id)), 1, offset))
                    },
                )
            pager.reset(listOf(server("old")), UnifiedLibraryQuery())
            val old = async { pager.loadMore() }
            started.await()
            pager.reset(listOf(server("new")), UnifiedLibraryQuery())
            response.complete(Unit)
            old.await()
            pager.loadMore()
            assertEquals(
                "new",
                pager.state.value.groups
                    .single()
                    .recommended.serverId,
            )
        }

    @Test
    fun one_gesture_loads_at_most_four_libraries() =
        runTest {
            var calls = 0
            val pager =
                UnifiedLibraryPager(
                    libraries = { Result.success((1..10).map { MediaLibrary("$it", "$it", "movies") }) },
                    page = { _, id, offset, _, _ ->
                        calls++
                        Result.success(LibraryPage(listOf(movie(id)), 1, offset))
                    },
                )
            pager.reset(listOf(server("a")), UnifiedLibraryQuery())
            pager.loadMore()
            assertEquals(4, calls)
            assertTrue(pager.state.value.hasMore)
            pager.loadMore()
            assertEquals(8, calls)
            pager.loadMore()
            assertEquals(10, calls)
            assertFalse(pager.state.value.hasMore)
        }

    @Test
    fun repeated_first_page_is_reported_instead_of_looping_forever() =
        runTest {
            val pager =
                UnifiedLibraryPager(
                    libraries = { Result.success(listOf(MediaLibrary("movies", "Movies", "movies"))) },
                    page = { _, _, _, _, _ -> Result.success(LibraryPage(listOf(movie("1")), 100, 0)) },
                )
            pager.reset(listOf(server("a")), UnifiedLibraryQuery())
            pager.loadMore()
            pager.loadMore()
            assertEquals(1, pager.state.value.groups.size)
            assertFalse(pager.state.value.hasMore)
            assertTrue(
                pager.state.value.failures
                    .isNotEmpty(),
            )
        }
}
