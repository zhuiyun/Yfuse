package com.yfuse.feature.search

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchStoreTest {
    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        try {
            // MockEngine and the Store share this scheduler. Drain cancellation here so an
            // assertion or handler failure belongs to the test that launched it instead of
            // surfacing as UncaughtExceptionsBeforeTest in the following test on Linux CI.
            dispatcher.scheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun submit_searches_default_server_and_exposes_results() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
            )
            val repo =
                testRepo(dispatcher) { request ->
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
    fun submit_without_server_shows_actionable_error() =
        runTest {
            val store =
                SearchStoreFactory(
                    DefaultStoreFactory(),
                    testRepo(dispatcher) { json("""{"Items":[]}""") },
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
    fun smart_source_groups_provider_copies_and_switch_disables_aggregation() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id1", "http://one:8096", "甲", "u1", "user", "tok"))
                    addOrUpdate(SavedServer("id2", "http://two:8096", "乙", "u2", "user", "tok"))
                }
            val preferences = PlaybackPreferences(MapSettings())
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Persons")) {
                        return@testRepo json("""{"Items":[]}""")
                    }
                    val itemId = if (request.url.host == "one") "m1" else "m2"
                    json(
                        """{"Items":[{"Id":"$itemId","Name":"黑客帝国","Type":"Movie",""" +
                            """"ProductionYear":1999,"ProviderIds":{"Tmdb":"603"}}]}""",
                    )
                }

            suspend fun search(): SearchState {
                val store =
                    SearchStoreFactory(
                        DefaultStoreFactory(),
                        repo,
                        registry,
                        playbackPreferences = preferences,
                    ).create()
                store.accept(SearchIntent.QueryChanged("黑客帝国"))
                store.accept(SearchIntent.Submit)
                return store.states
                    .first { it.hasSearched && !it.loading }
                    .also { store.dispose() }
            }

            val aggregated = search()
            assertEquals(1, aggregated.aggregated.size)
            assertEquals(
                2,
                aggregated.aggregated
                    .single()
                    .copies.size,
            )

            preferences.setSmartCrossServerSource(false)
            assertTrue(search().aggregated.isEmpty())
        }

    @Test
    fun fast_server_results_are_visible_while_a_slow_server_is_still_loading() =
        runTest {
            val slowServerRelease = CompletableDeferred<Unit>()
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("slow", "http://slow:8096", "慢服务器", "u1", "user", "tok"))
                    addOrUpdate(SavedServer("fast", "http://fast:8096", "快服务器", "u2", "user", "tok"))
                }
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.host == "slow") slowServerRelease.await()
                    json(
                        """{"Items":[{"Id":"${request.url.host}","Name":"沙丘","Type":"Movie"}]}""",
                    )
                }
            val store = SearchStoreFactory(DefaultStoreFactory(), repo, registry).create()

            store.accept(SearchIntent.QueryChanged("沙丘"))
            store.accept(SearchIntent.Submit)

            val partial = store.states.first { it.loading && it.groups.any { group -> group.serverId == "fast" } }
            assertEquals(listOf("fast"), partial.groups.map { it.serverId })
            assertEquals("沙丘", partial.items.single().title)

            slowServerRelease.complete(Unit)
            val complete = store.states.first { it.hasSearched && !it.loading }
            assertEquals(listOf("slow", "fast"), complete.groups.map { it.serverId })
            store.dispose()
        }

    @Test
    fun type_filter_narrows_the_groups_and_the_heading_count() {
        val state =
            SearchState(
                groups =
                    listOf(
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
        val state =
            SearchState(
                groups =
                    listOf(
                        ServerSearchGroup("id1", "甲", items = listOf(mediaItem("m1", "Movie"))),
                    ),
            )

        assertEquals(listOf(SearchType.All, SearchType.Movie), state.availableTypes)
    }

    @Test
    fun a_new_query_resets_the_previous_type_filter() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
            )
            val store =
                SearchStoreFactory(
                    DefaultStoreFactory(),
                    testRepo(dispatcher) { json("""{"Items":[]}""") },
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
    fun a_failed_server_is_kept_in_coverage_but_not_the_result_stream() {
        val state =
            SearchState(
                groups =
                    listOf(
                        ServerSearchGroup("id1", "甲", items = listOf(mediaItem("m1", "Movie"))),
                        ServerSearchGroup("id2", "乙", error = "连接失败"),
                    ),
                type = SearchType.Movie,
            )

        assertEquals(listOf("甲"), state.visibleGroups.map { it.serverName })
        assertEquals(listOf("乙"), state.unavailableGroups.map { it.serverName })
        // The failed group contributes no titles, so it does not inflate the count.
        assertEquals(1, state.visibleCount)
    }

    @Test
    fun every_failed_server_keeps_actionable_per_server_context() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
            )
            val store =
                SearchStoreFactory(
                    DefaultStoreFactory(),
                    testRepo(dispatcher) { error("network unavailable") },
                    registry,
                ).create()

            store.accept(SearchIntent.QueryChanged("沙丘"))
            store.accept(SearchIntent.Submit)

            val state = store.states.first { it.hasSearched && !it.loading }
            assertEquals(null, state.error)
            assertEquals(listOf("我的服务器"), state.unavailableGroups.map { it.serverName })
            assertTrue(
                state.unavailableGroups
                    .single()
                    .error
                    .orEmpty()
                    .isNotBlank(),
            )
            store.dispose()
        }

    @Test
    fun load_more_appends_the_next_server_page_without_duplicates() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
            )
            val starts = mutableListOf<String?>()
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Persons")) {
                        return@testRepo json("""{"Items":[]}""")
                    }
                    val start = request.url.parameters["StartIndex"]
                    starts += start
                    if (start == null) {
                        json(
                            """{"Items":[{"Id":"m1","Name":"沙丘","Type":"Movie"}],"TotalRecordCount":2}""",
                        )
                    } else {
                        assertEquals("1", start)
                        json(
                            """{"Items":[{"Id":"m1","Name":"沙丘","Type":"Movie"},{"Id":"m2","Name":"沙丘2","Type":"Movie"}],"TotalRecordCount":2}""",
                        )
                    }
                }
            val store = SearchStoreFactory(DefaultStoreFactory(), repo, registry).create()

            store.accept(SearchIntent.QueryChanged("沙丘"))
            store.accept(SearchIntent.Submit)
            store.states.first { it.hasSearched && !it.loading && it.groups.singleOrNull()?.canLoadMore == true }
            store.accept(SearchIntent.LoadMore("id1"))

            val loaded =
                store.states.first {
                    it.groups.singleOrNull()?.let { group -> !group.loadingMore && group.items.size == 2 } == true
                }
            assertEquals(
                listOf("m1", "m2"),
                loaded.groups
                    .single()
                    .items
                    .map { it.id },
            )
            assertEquals(listOf(null, "1"), starts)
            store.dispose()
        }

    @Test
    fun clear_resets_query_results_and_error() =
        runTest {
            val registry = testRegistry()
            registry.addOrUpdate(
                SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"),
            )
            val store =
                SearchStoreFactory(
                    DefaultStoreFactory(),
                    testRepo(dispatcher) {
                        delay(10_000)
                        json("""{"Items":[]}""")
                    },
                    registry,
                ).create()

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

    private fun mediaItem(
        id: String,
        type: String,
    ) = MediaItem(
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
