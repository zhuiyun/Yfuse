package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryGridStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

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
        val store = LibraryGridStoreFactory(DefaultStoreFactory(), repo, registry(), "lib1").create()

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
        val store = LibraryGridStoreFactory(DefaultStoreFactory(), repo, registry(), "lib1").create()
        store.states.first { !it.loading && it.items.isNotEmpty() }

        store.accept(GridIntent.LoadMore)

        val merged = store.states.first { it.items.size > 2 }
        assertEquals(listOf("m0", "m1", "m2"), merged.items.map { it.id })
        store.dispose()
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
        val store = LibraryGridStoreFactory(DefaultStoreFactory(), repo, registry(), "lib1").create()
        store.states.first { !it.loading && it.items.isNotEmpty() }

        store.accept(GridIntent.SetSort(LibrarySort.Name))

        val sorted = store.states.first { it.sort == LibrarySort.Name && !it.loading }
        assertEquals(3, sorted.items.size)
        assertEquals(listOf("DateCreated", "SortName"), sorts)
        store.dispose()
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
        val store = LibraryGridStoreFactory(DefaultStoreFactory(), repo, registry(), "lib1").create()
        store.states.first { it.genres.isNotEmpty() }

        store.accept(GridIntent.SetGenre("科幻"))

        store.states.first { it.genre == "科幻" && !it.loading }
        assertEquals(listOf(null, "科幻"), genres)
        store.dispose()
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
        val store = LibraryGridStoreFactory(DefaultStoreFactory(), repo, registry(), "lib1").create()
        store.states.first { !it.loading && it.items.isNotEmpty() }

        store.accept(GridIntent.LoadMore)

        val failed = store.states.first { it.loadMoreError != null }
        assertEquals(2, failed.items.size)
        // The page-level failure must not become the whole screen's error state.
        assertEquals(null, failed.error)
        store.dispose()
    }
}
