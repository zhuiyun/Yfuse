package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGridStoreTest {
    private fun registry() = testRegistry().apply {
        addOrUpdate(SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"))
    }

    /** `{"Items":[…],"TotalRecordCount":total}` holding [count] items numbered from [from]. */
    private fun page(from: Int, count: Int, total: Int): String {
        val items = (from until from + count).joinToString(",") {
            """{"Id":"m$it","Name":"电影$it","Type":"Movie"}"""
        }
        return """{"Items":[$items],"TotalRecordCount":$total}"""
    }

    @Test
    fun load_more_appends_the_next_page_and_stops_at_the_total() = runTest {
        val requested = mutableListOf<String?>()
        val repo = testRepo { request ->
            requested += request.url.parameters["StartIndex"]
            val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
            if (request.url.encodedPath.endsWith("/Genres")) {
                json("""{"Items":[]}""")
            } else {
                json(page(from = start, count = if (start == 0) 60 else 30, total = 90))
            }
        }
        val store = LibraryGridStoreFactory(
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
    fun a_repeated_item_is_merged_rather_than_duplicated() = runTest {
        // Emby's order is not total, so a title can sit on both sides of a page boundary.
        val repo = testRepo { request ->
            val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
            if (request.url.encodedPath.endsWith("/Genres")) {
                json("""{"Items":[]}""")
            } else if (start == 0) {
                json(page(from = 0, count = 2, total = 4))
            } else {
                json(page(from = 1, count = 2, total = 4))
            }
        }
        val store = LibraryGridStoreFactory(
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
    fun an_entirely_duplicated_page_advances_the_server_offset() = runTest {
        val requested = mutableListOf<Int>()
        val repo = testRepo { request ->
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
        val store = LibraryGridStoreFactory(
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
    fun changing_the_sort_reloads_from_the_first_page() = runTest {
        val sorts = mutableListOf<String?>()
        val repo = testRepo { request ->
            if (request.url.encodedPath.endsWith("/Genres")) {
                json("""{"Items":[]}""")
            } else {
                sorts += request.url.parameters["SortBy"]
                json(page(from = 0, count = 3, total = 3))
            }
        }
        val store = LibraryGridStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry(),
            "lib1",
            mainContext = UnconfinedTestDispatcher(testScheduler),
        ).create()
        store.states.first { !it.loading && it.items.isNotEmpty() }

        store.accept(GridIntent.SetSort(LibrarySort.Name))

        val sorted = store.states.first { it.sort == LibrarySort.Name && !it.loading }
        assertEquals(3, sorted.items.size)
        assertEquals(listOf<String?>("DateCreated", "SortName"), sorts)
        store.dispose()
        runCurrent()
    }

    @Test
    fun failed_filter_change_never_displays_items_from_the_previous_criteria() = runTest {
        var failFilteredRequest = true
        val repo = testRepo { request ->
            when {
                request.url.encodedPath.endsWith("/Genres") ->
                    json("""{"Items":[{"Id":"g1","Name":"科幻"}]}""")
                request.url.parameters["Genres"] == "科幻" && failFilteredRequest ->
                    throw kotlinx.io.IOException("filtered request failed")
                else -> json(page(from = 0, count = 2, total = 2))
            }
        }
        val store = LibraryGridStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry(),
            "lib1",
            mainContext = UnconfinedTestDispatcher(testScheduler),
        ).create()
        store.states.first { !it.loading && it.items.size == 2 && it.genres.isNotEmpty() }

        val failedState = async(start = CoroutineStart.UNDISPATCHED) {
            store.states.first { it.genre == "科幻" && it.error != null }
        }
        store.accept(GridIntent.SetGenre("科幻"))

        val failed = failedState.await()
        assertTrue(failed.items.isEmpty())
        assertEquals(0, failed.totalCount)
        assertEquals(0, failed.nextStartIndex)

        failFilteredRequest = false
        val recoveredState = async(start = CoroutineStart.UNDISPATCHED) {
            store.states.first {
                !it.loading && it.items.size == 2 && it.genre == "科幻" && it.error == null
            }
        }
        store.accept(GridIntent.Retry)
        val recovered = recoveredState.await()
        assertEquals("科幻", recovered.genre)
        assertEquals(null, recovered.error)
        store.dispose()
        runCurrent()
    }

    @Test
    fun genres_fill_the_filter_row_and_a_selection_narrows_the_query() = runTest {
        val genres = mutableListOf<String?>()
        val repo = testRepo { request ->
            if (request.url.encodedPath.endsWith("/Genres")) {
                json("""{"Items":[{"Id":"g1","Name":"科幻"},{"Id":"g2","Name":"悬疑"}]}""")
            } else {
                genres += request.url.parameters["Genres"]
                json(page(from = 0, count = 1, total = 1))
            }
        }
        val store = LibraryGridStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry(),
            "lib1",
            mainContext = UnconfinedTestDispatcher(testScheduler),
        ).create()
        store.states.first { it.genres.isNotEmpty() && !it.loading && it.items.isNotEmpty() }

        store.accept(GridIntent.SetGenre("科幻"))

        store.states.first { it.genre == "科幻" && !it.loading }
        assertEquals(listOf(null, "科幻"), genres)
        store.dispose()
        runCurrent()
    }

    @Test
    fun retry_recovers_genres_after_the_initial_facet_request_fails() = runTest {
        var genreAttempts = 0
        val repo = testRepo { request ->
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
        val store = LibraryGridStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry(),
            "lib1",
            mainContext = StandardTestDispatcher(testScheduler),
        ).create()
        val failedState = async(start = CoroutineStart.UNDISPATCHED) {
            store.states.first {
                !it.loading && it.items.isNotEmpty() && it.genreLoadError != null
            }
        }
        runCurrent()
        val failed = failedState.await()
        assertTrue(store.state.genres.isEmpty())
        assertTrue(failed.genreLoadError!!.isNotBlank())

        val recoveredState = async(start = CoroutineStart.UNDISPATCHED) {
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
    fun a_failed_page_keeps_what_is_already_loaded() = runTest {
        val repo = testRepo { request ->
            val start = request.url.parameters["StartIndex"]?.toInt() ?: 0
            when {
                request.url.encodedPath.endsWith("/Genres") -> json("""{"Items":[]}""")
                start == 0 -> json(page(from = 0, count = 2, total = 10))
                else -> throw kotlinx.io.IOException("network down")
            }
        }
        val store = LibraryGridStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry(),
            "lib1",
            mainContext = UnconfinedTestDispatcher(testScheduler),
        ).create()
        store.states.first { !it.loading && it.items.isNotEmpty() }

        // Register before the intent: an unconfined mock failure may complete before accept returns.
        val failedState = async(start = CoroutineStart.UNDISPATCHED) {
            store.states.first { !it.loadingMore && it.loadMoreError != null }
        }
        store.accept(GridIntent.LoadMore)

        val failed = failedState.await()
        assertFalse(failed.loadingMore)
        assertTrue(failed.loadMoreError?.isNotBlank() == true)
        assertEquals(2, failed.items.size)
        // The page-level failure must not become the whole screen's error state.
        assertEquals(null, failed.error)
        store.dispose()
        runCurrent()
    }
}
