package com.yfuse.feature.search

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
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

        assertEquals(SearchState(), store.state)
        store.dispose()
    }
}
