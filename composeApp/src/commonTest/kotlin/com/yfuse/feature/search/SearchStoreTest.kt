package com.yfuse.feature.search

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun submit_searches_default_server_and_exposes_results() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(
            SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
        )
        val repo = testRepo { request ->
            assertEquals("沙丘", request.url.parameters["SearchTerm"])
            json(
                """{"Items":[{"Id":"m1","Name":"沙丘2","Type":"Movie","ProductionYear":2024,""" +
                    """"ImageTags":{"Primary":"poster"}}]}""",
            )
        }
        val store = SearchStoreFactory(DefaultStoreFactory(), repo, registry).create()

        store.accept(SearchIntent.QueryChanged("  沙丘  "))
        store.accept(SearchIntent.Submit)

        val state = store.states.first { it.hasSearched && !it.loading }
        assertEquals("沙丘", state.searchedQuery)
        assertEquals(1, state.items.size)
        assertEquals("沙丘2", state.items.first().title)
        assertEquals(null, state.error)
        store.dispose()
    }

    @Test
    fun submit_without_server_shows_actionable_error() = runTest {
        val store = SearchStoreFactory(
            DefaultStoreFactory(),
            testRepo { json("""{"Items":[]}""") },
            testRegistry(),
        ).create()

        store.accept(SearchIntent.QueryChanged("沙丘"))
        store.accept(SearchIntent.Submit)

        val state = store.states.first { it.hasSearched && it.error != null }
        assertTrue(state.error!!.contains("添加服务器"))
        assertTrue(state.items.isEmpty())
        store.dispose()
    }

    @Test
    fun type_filter_narrows_the_groups_and_the_heading_count() {
        val state = SearchState(
            groups = listOf(
                ServerSearchGroup(
                    serverId = "id1",
                    serverName = "甲",
                    items = listOf(mediaItem("m1", "Movie"), mediaItem("s1", "Series")),
                ),
                ServerSearchGroup(
                    serverId = "id2",
                    serverName = "乙",
                    items = listOf(mediaItem("s2", "Series")),
                ),
            ),
        )

        assertEquals(3, state.visibleCount)
        assertEquals(listOf(SearchType.All, SearchType.Movie, SearchType.Series), state.availableTypes)

        val movies = state.copy(type = SearchType.Movie)
        // 乙 held no movies, so it drops out entirely rather than showing an empty heading.
        assertEquals(listOf("甲"), movies.visibleGroups.map { it.serverName })
        assertEquals(1, movies.visibleCount)
    }

    @Test
    fun type_filter_is_not_offered_for_a_kind_nothing_matched() {
        val state = SearchState(
            groups = listOf(
                ServerSearchGroup("id1", "甲", items = listOf(mediaItem("m1", "Movie"))),
            ),
        )

        assertEquals(listOf(SearchType.All, SearchType.Movie), state.availableTypes)
    }

    @Test
    fun a_new_query_resets_the_previous_type_filter() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(
            SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
        )
        val store = SearchStoreFactory(
            DefaultStoreFactory(),
            testRepo { json("""{"Items":[]}""") },
            registry,
        ).create()
        store.accept(SearchIntent.SetType(SearchType.Movie))

        store.accept(SearchIntent.QueryChanged("沙丘"))
        store.accept(SearchIntent.Submit)

        val state = store.states.first { it.hasSearched && !it.loading }
        assertEquals(SearchType.All, state.type)
        store.dispose()
    }

    @Test
    fun a_failed_server_survives_the_type_filter() {
        val state = SearchState(
            groups = listOf(
                ServerSearchGroup("id1", "甲", items = listOf(mediaItem("m1", "Movie"))),
                ServerSearchGroup("id2", "乙", error = "连接失败"),
            ),
            type = SearchType.Movie,
        )

        assertEquals(listOf("甲", "乙"), state.visibleGroups.map { it.serverName })
        // The failed group contributes no titles, so it does not inflate the count.
        assertEquals(1, state.visibleCount)
    }

    @Test
    fun clear_resets_query_results_and_error() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(
            SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
        )
        val store = SearchStoreFactory(DefaultStoreFactory(), testRepo {
            delay(10_000)
            json("""{"Items":[]}""")
        }, registry).create()

        store.accept(SearchIntent.QueryChanged("沙丘"))
        store.accept(SearchIntent.Submit)
        store.accept(SearchIntent.Clear)

        assertEquals(
            SearchState(
                serverOptions = listOf(SearchOption("id1", "我的服务器")),
            ),
            store.state,
        )
        store.dispose()
    }

    private fun mediaItem(id: String, type: String) = MediaItem(
        id = id,
        title = id,
        subtitle = null,
        type = type,
        posterItemId = id,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
    )
}
