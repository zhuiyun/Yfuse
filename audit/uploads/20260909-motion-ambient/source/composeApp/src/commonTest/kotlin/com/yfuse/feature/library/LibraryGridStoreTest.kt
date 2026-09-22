package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.data.FAVORITES_COLLECTION_ID
import com.yfuse.core.data.WATCH_LATER_COLLECTION_ID
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGridStoreTest {
    private fun registry() =
        testRegistry().apply {
            addOrUpdate(SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"))
        }

    /** `{"Items":[…],"TotalRecordCount":total}` holding [count] items numbered from [from]. */
    private fun page(
        from: Int,
        count: Int,
        total: Int,
    ): String {
        val items =
            (from until from + count).joinToString(",") {
                """{"Id":"m$it","Name":"电影$it","Type":"Movie"}"""
            }
        return """{"Items":[$items],"TotalRecordCount":$total}"""
    }

    @Test
    fun load_more_appends_the_next_page_and_stops_at_the_total() =
        runTest {
            val requested = mutableListOf<String?>()
            val repo =
                testRepo { request ->
                    requested += request.url.parameters["StartIndex"]
                    val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        json(page(from = start, count = if (start == 0) 60 else 30, total = 90))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()

            val first = store.states.first { !it.loading && it.items.isNotEmpty() }
            assertEquals(60, first.items.size)
            assertEquals(90, first.totalCount)
            assertTrue(first.canLoadMore)

            store.accept(GridIntent.LoadMore)

            val second = store.states.first { it.items.size > 60 }
            assertEquals(90, second.items.size)
            assertFalse(second.canLoadMore)
            assertTrue(requested.contains("60"), "second page was not requested: $requested")
            store.dispose()
            runCurrent()
        }

    @Test
    fun a_repeated_item_is_merged_rather_than_duplicated() =
        runTest {
            // Emby's order is not total, so a title can sit on both sides of a page boundary.
            val repo =
                testRepo { request ->
                    val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else if (start == 0) {
                        json(page(from = 0, count = 2, total = 4))
                    } else {
                        json(page(from = 1, count = 2, total = 4))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()
            store.states.first { !it.loading && it.items.isNotEmpty() }

            store.accept(GridIntent.LoadMore)

            val merged = store.states.first { it.items.size > 2 }
            assertEquals(listOf("m0", "m1", "m2"), merged.items.map { it.id })
            store.dispose()
            runCurrent()
        }

    @Test
    fun an_entirely_duplicated_page_advances_the_server_offset() =
        runTest {
            val requested = mutableListOf<Int>()
            val repo =
                testRepo { request ->
                    val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        requested += start
                        when (start) {
                            0 -> json(page(from = 0, count = 2, total = 6))
                            2 -> json(page(from = 0, count = 2, total = 6))
                            else -> json(page(from = 4, count = 2, total = 6))
                        }
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()
            store.states.first { !it.loading && it.nextStartIndex == 2 }

            store.accept(GridIntent.LoadMore)

            val duplicated = store.states.first { !it.loadingMore && it.nextStartIndex == 4 }
            assertEquals(listOf("m0", "m1"), duplicated.items.map { it.id })
            assertTrue(duplicated.canLoadMore)

            store.accept(GridIntent.LoadMore)

            val completed = store.states.first { !it.loadingMore && it.nextStartIndex == 6 }
            assertEquals(listOf("m0", "m1", "m4", "m5"), completed.items.map { it.id })
            assertFalse(completed.canLoadMore)
            assertEquals(listOf(0, 2, 4), requested)
            store.dispose()
            runCurrent()
        }

    @Test
    fun changing_the_sort_reloads_from_the_first_page() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val sorts = mutableListOf<String?>()
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        sorts += request.url.parameters["SortBy"]
                        json(page(from = 0, count = 3, total = 3))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertTrue(!store.state.loading && store.state.items.isNotEmpty())

            store.accept(GridIntent.SetSort(LibrarySort.Name))

            advanceUntilIdle()
            val sorted = store.state
            assertEquals(LibrarySort.Name, sorted.sort)
            assertFalse(sorted.loading)
            assertEquals(3, sorted.items.size)
            assertEquals(listOf<String?>("DateCreated", "SortName"), sorts)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun changing_sort_keeps_the_existing_grid_until_the_replacement_page_arrives() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val releaseSortedPage = CompletableDeferred<Unit>()
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        if (request.url.parameters["SortBy"] == "SortName") releaseSortedPage.await()
                        json(page(from = 0, count = 2, total = 2))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            val previousIds = store.state.items.map { it.id }

            store.accept(GridIntent.SetSort(LibrarySort.Name))
            runCurrent()

            assertTrue(store.state.loading)
            assertTrue(store.state.retainingPreviousCriteria)
            assertEquals(previousIds, store.state.items.map { it.id })

            releaseSortedPage.complete(Unit)
            advanceUntilIdle()
            assertFalse(store.state.retainingPreviousCriteria)
            assertEquals(previousIds, store.state.items.map { it.id })
            store.dispose()
        }

    @Test
    fun failed_filter_change_never_displays_items_from_the_previous_criteria() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var filteredAttempts = 0
            val repo =
                testRepo(dispatcher) { request ->
                    when {
                        request.url.encodedPath.endsWith("/Genres") ->
                            json("""{"Items":[{"Id":"g1","Name":"科幻"}]}""")
                        request.url.parameters["Genres"] == "科幻" -> {
                            filteredAttempts += 1
                            if (filteredAttempts == 1) {
                                throw kotlinx.io.IOException("filtered request failed")
                            }
                            json(page(from = 0, count = 2, total = 2))
                        }
                        else -> json(page(from = 0, count = 2, total = 2))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertTrue(!store.state.loading && store.state.items.size == 2)
            assertTrue(store.state.genres.isNotEmpty())

            store.accept(GridIntent.SetGenre("科幻"))
            advanceUntilIdle()

            val failed = store.state
            assertEquals("科幻", failed.genre)
            assertTrue(failed.error != null)
            assertTrue(failed.items.isEmpty())
            assertEquals(0, failed.totalCount)
            assertEquals(0, failed.nextStartIndex)

            store.accept(GridIntent.Retry)
            advanceUntilIdle()
            val recovered = store.state
            assertTrue(!recovered.loading && recovered.items.size == 2)
            assertEquals(2, filteredAttempts)
            assertEquals("科幻", recovered.genre)
            assertEquals(null, recovered.error)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun genres_fill_the_filter_row_and_a_selection_narrows_the_query() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val genres = mutableListOf<String?>()
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[{"Id":"g1","Name":"科幻"},{"Id":"g2","Name":"悬疑"}]}""")
                    } else {
                        genres += request.url.parameters["Genres"]
                        json(page(from = 0, count = 1, total = 1))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertTrue(
                store.state.genres.isNotEmpty() &&
                    !store.state.loading &&
                    store.state.items.isNotEmpty(),
            )

            store.accept(GridIntent.SetGenre("科幻"))

            advanceUntilIdle()
            assertEquals("科幻", store.state.genre)
            assertFalse(store.state.loading)
            assertEquals(listOf(null, "科幻"), genres)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun media_spec_filter_reloads_with_server_side_4k_parameters() =
        runTest {
            val requests = mutableListOf<Pair<String?, String?>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo =
                testRepo(dispatcher = dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        requests +=
                            (
                                request.url.parameters["IsHD"] to
                                    request.url.parameters["MinWidth"]
                            )
                        json(page(from = 0, count = 1, total = 1))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertFalse(store.state.loading)
            assertTrue(store.state.items.isNotEmpty())

            store.accept(GridIntent.SetResolution(LibraryResolution.FourK))

            advanceUntilIdle()
            val filtered = store.state
            assertFalse(filtered.loading)
            assertEquals(LibraryResolution.FourK, filtered.resolution)
            assertEquals(listOf(null to null, "true" to "2560"), requests)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun retry_recovers_genres_after_the_initial_facet_request_fails() =
        runTest {
            var genreAttempts = 0
            val repo =
                testRepo { request ->
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        genreAttempts += 1
                        if (genreAttempts == 1) {
                            throw kotlinx.io.IOException("facet timeout")
                        }
                        json("""{"Items":[{"Id":"g1","Name":"科幻"}]}""")
                    } else {
                        json(page(from = 0, count = 1, total = 1))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = StandardTestDispatcher(testScheduler),
                ).create()
            val failedState =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.states.first {
                        !it.loading && it.items.isNotEmpty() && it.genreLoadError != null
                    }
                }
            runCurrent()
            val failed = failedState.await()
            assertTrue(store.state.genres.isEmpty())
            assertTrue(failed.genreLoadError!!.isNotBlank())

            val recoveredState =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.states.first { it.genres == listOf("科幻") }
                }
            store.accept(GridIntent.RetryGenres)

            val recovered = recoveredState.await()
            assertEquals(2, genreAttempts)
            assertEquals(listOf("科幻"), recovered.genres)
            assertEquals(null, recovered.genreLoadError)
            store.dispose()
            runCurrent()
        }

    @Test
    fun a_failed_page_keeps_what_is_already_loaded() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo =
                testRepo(dispatcher = dispatcher) { request ->
                    val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
                    when {
                        request.url.encodedPath.endsWith("/Genres") -> json("""{"Items":[]}""")
                        start == 0 -> json(page(from = 0, count = 2, total = 10))
                        else -> throw kotlinx.io.IOException("network down")
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "lib1",
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertEquals(2, store.state.items.size)
            store.accept(GridIntent.LoadMore)
            advanceUntilIdle()

            val failed = store.state
            assertFalse(failed.loadingMore)
            assertTrue(failed.loadMoreError?.isNotBlank() == true)
            assertEquals(2, failed.items.size)
            // The page-level failure must not become the whole screen's error state.
            assertEquals(null, failed.error)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun playlist_grid_keeps_first_entry_order_deduplicates_media_and_ignores_sort_intents() =
        runTest {
            val sortParameters = mutableListOf<String?>()
            val repo =
                testRepo { request ->
                    sortParameters += request.url.parameters["SortBy"]
                    json(
                        """{"Items":[{"Id":"m1","Name":"第一部（再次）","Type":"Movie","PlaylistItemId":"e2"},{"Id":"m1","Name":"第一部","Type":"Movie","PlaylistItemId":"e1"}],"TotalRecordCount":2}""",
                    )
                }
            val store =
                LibraryGridStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    repo = repo,
                    registry = registry(),
                    libraryId = "p1",
                    serverId = "id1",
                    containerKind = MediaContainerKind.Playlist,
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()

            val loaded = store.states.first { !it.loading && it.items.isNotEmpty() }
            assertEquals(listOf("e2"), loaded.items.map { it.playlistItemId })
            assertEquals(1, loaded.totalCount)
            assertFalse(loaded.sortable)

            store.accept(GridIntent.SetSort(LibrarySort.Name))

            assertEquals(LibrarySort.RecentlyAdded, store.state.sort)
            assertEquals(listOf<String?>(null), sortParameters)
            store.dispose()
            runCurrent()
        }

    @Test
    fun favorites_grid_deduplicates_repeated_media_ids() =
        runTest {
            val repo =
                testRepo {
                    json(
                        """{"Items":[{"Id":"m1","Name":"第一部","Type":"Movie"},{"Id":"m1","Name":"第一部（重复）","Type":"Movie"}],"TotalRecordCount":2}""",
                    )
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    FAVORITES_COLLECTION_ID,
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()

            val loaded = store.states.first { !it.loading && it.items.isNotEmpty() }
            assertEquals(listOf("m1"), loaded.items.map { it.id })
            assertEquals(1, loaded.totalCount)
            store.dispose()
            runCurrent()
        }

    @Test
    fun watch_later_grid_deduplicates_repeated_media_ids() =
        runTest {
            val repo =
                testRepo { request ->
                    if (request.url.encodedPath.endsWith("/Playlists/p1/Items")) {
                        json(
                            """{"Items":[{"Id":"m1","Name":"第一部","Type":"Movie"},{"Id":"m1","Name":"第一部（重复）","Type":"Movie"}],"TotalRecordCount":2}""",
                        )
                    } else {
                        json("""{"Items":[{"Id":"p1","Name":"稍后观看","Type":"Playlist"}]}""")
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    WATCH_LATER_COLLECTION_ID,
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()

            val loaded = store.states.first { !it.loading && it.items.isNotEmpty() }
            assertEquals(listOf("m1"), loaded.items.map { it.id })
            assertEquals(1, loaded.totalCount)
            store.dispose()
            runCurrent()
        }

    @Test
    fun playlist_removal_commits_the_optimistic_local_change() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var removedEntryId: String? = null
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.method == HttpMethod.Delete) {
                        removedEntryId = request.url.parameters["EntryIds"]
                        json("{}")
                    } else {
                        json(
                            """{"Items":[{"Id":"m1","Name":"第一部","Type":"Movie","PlaylistItemId":"e1"},{"Id":"m2","Name":"第二部","Type":"Movie","PlaylistItemId":"e2"}],"TotalRecordCount":2}""",
                        )
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "p1",
                    serverId = "id1",
                    containerKind = MediaContainerKind.Playlist,
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertEquals(2, store.state.items.size)

            store.accept(GridIntent.RequestRemove("m1"))
            assertEquals("e1", store.state.pendingRemoval?.playlistItemId)
            store.accept(GridIntent.ConfirmRemove)

            advanceUntilIdle()
            val committed = store.state
            assertTrue(committed.actionMessage != null)
            assertEquals("e1", removedEntryId)
            assertEquals(listOf("e2"), committed.items.map { it.playlistItemId })
            assertEquals(1, committed.totalCount)
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun failed_container_removal_rolls_the_item_back_in_place() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.method == HttpMethod.Delete) {
                        throw kotlinx.io.IOException("offline")
                    }
                    json(
                        """{"Items":[{"Id":"m1","Name":"第一部","Type":"Movie","PlaylistItemId":"e1"},{"Id":"m2","Name":"第二部","Type":"Movie","PlaylistItemId":"e2"}],"TotalRecordCount":2}""",
                    )
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "p1",
                    serverId = "id1",
                    containerKind = MediaContainerKind.Playlist,
                    mainContext = dispatcher,
                ).create()
            advanceUntilIdle()
            assertEquals(2, store.state.items.size)

            store.accept(GridIntent.RequestRemove("m1"))
            store.accept(GridIntent.ConfirmRemove)

            // MockEngine and the Store share this scheduler, so the failed request and rollback
            // finish deterministically before the state is asserted or the Store is disposed.
            advanceUntilIdle()
            val rolledBack = store.state
            assertTrue(rolledBack.actionMessage != null)
            assertEquals(listOf("e1", "e2"), rolledBack.items.map { it.playlistItemId })
            assertEquals(2, rolledBack.totalCount)
            assertTrue(rolledBack.locallyRemovedRowIds.isEmpty())
            store.dispose()
            advanceUntilIdle()
        }

    @Test
    fun grid_keeps_the_origin_server_after_default_server_switches() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id1", "http://one.test", "一号", "u1", "one", "tok1"))
                    addOrUpdate(SavedServer("id2", "http://two.test", "二号", "u2", "two", "tok2"))
                    setDefault("id1")
                }
            val requestedHosts = mutableListOf<String>()
            val repo =
                testRepo { request ->
                    requestedHosts += request.url.host
                    if (request.url.encodedPath.endsWith("/Genres")) {
                        json("""{"Items":[]}""")
                    } else {
                        json(page(from = 0, count = 1, total = 1))
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    repo = repo,
                    registry = registry,
                    libraryId = "lib1",
                    serverId = "id1",
                    mainContext = StandardTestDispatcher(testScheduler),
                ).create()

            registry.setDefault("id2")
            runCurrent()
            store.states.first { !it.loading && it.items.isNotEmpty() }

            assertTrue(requestedHosts.isNotEmpty())
            assertTrue(requestedHosts.all { it == "one.test" }, requestedHosts.toString())
            store.dispose()
            runCurrent()
        }

    @Test
    fun container_directory_pages_and_deduplicates_by_container_identity() =
        runTest {
            val starts = mutableListOf<Int>()
            val repo =
                testRepo { request ->
                    val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
                    starts += start
                    if (start == 0) {
                        json(
                            """{"Items":[{"Id":"c1","Name":"合集1","Type":"BoxSet"},{"Id":"c2","Name":"合集2","Type":"BoxSet"}],"TotalRecordCount":4}""",
                        )
                    } else {
                        json(
                            """{"Items":[{"Id":"c2","Name":"合集2","Type":"BoxSet"},{"Id":"c3","Name":"合集3","Type":"BoxSet"}],"TotalRecordCount":4}""",
                        )
                    }
                }
            val store =
                LibraryGridStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry(),
                    "directory",
                    serverId = "id1",
                    directoryKind = MediaContainerKind.BoxSet,
                    mainContext = UnconfinedTestDispatcher(testScheduler),
                ).create()
            store.states.first { !it.loading && it.containers.size == 2 }

            store.accept(GridIntent.LoadMore)

            val appended = store.states.first { !it.loadingMore && it.nextStartIndex == 4 }
            assertEquals(listOf("c1", "c2", "c3"), appended.containers.map { it.id })
            assertEquals(listOf(0, 2), starts)
            assertFalse(appended.canLoadMore)
            store.dispose()
            runCurrent()
        }
}
