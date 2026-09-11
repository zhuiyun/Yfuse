package com.yfuse.feature.home

import app.cash.turbine.test
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbHomeRefresh
import com.yfuse.core.data.TmdbRecommendationFailure
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.isoDateDaysBefore
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeStoreTest {
    @Test
    fun quick_home_revisits_reuse_refresh_but_explicit_refresh_still_fetches() =
        runTest(scheduler) {
            var requests = 0
            val registry =
                testRegistry().apply {
                    addOrUpdate(
                        SavedServer("one", "http://one", "One", "u", "User", "token"),
                    )
                }
            val store =
                HomeStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    tmdb = unavailableTmdb(),
                    emby =
                        testRepo(dispatcher = UnconfinedTestDispatcher(testScheduler)) { request ->
                            requests++
                            homeRoutes(request)
                        },
                    registry = registry,
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).create()
            try {
                advanceUntilIdle()
                store.accept(HomeIntent.RefreshLibrary)
                advanceUntilIdle()
                val afterReturn = requests
                assertTrue(afterReturn > 0)
                repeat(3) { store.accept(HomeIntent.RefreshLibrary) }
                advanceUntilIdle()
                assertEquals(afterReturn, requests)
                store.accept(HomeIntent.Refresh)
                advanceUntilIdle()
                assertTrue(requests > afterReturn)
            } finally {
                store.dispose()
            }
        }

    /**
     * One clock for the store and for the test that awaits it.
     *
     * `setMain(UnconfinedTestDispatcher())` builds a dispatcher on a *fresh* scheduler, so
     * the store's own coroutines — it collects on Main — were being driven by a clock
     * `runTest` neither advances nor waits for. Whether the test finished before that work
     * did was then a race, which surfaced as an intermittent UncompletedCoroutinesError on
     * CI. Sharing the scheduler makes the wait deterministic.
     */
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun home_avatar_uses_a_recognizable_user_initial() {
        assertEquals("A", homeUserInitial("  alice "))
        assertEquals("林", homeUserInitial("林海"))
        assertEquals("U", homeUserInitial("_user"))
        assertEquals("访", homeUserInitial("  "))
        assertEquals("访", homeUserInitial(null))
    }

    @Test
    fun continue_watching_copy_formats_resume_position_and_date() {
        assertEquals("6:29", resumePositionLabel(3_890_000_000L))
        assertEquals("1:06:29", resumePositionLabel(39_890_000_000L))
        assertEquals(null, resumePositionLabel(0L))
        assertEquals("08/24", compactLastPlayedDate("2026-08-24T12:00:00.000Z"))
        assertEquals(null, compactLastPlayedDate(null))
    }

    @Test
    fun unavailable_recommendations_do_not_claim_the_emby_server_is_offline() =
        runTest(scheduler) {
            val store =
                homeStore(
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            val state = store.states.first { !it.loading }

            assertFalse(state.loading)
            assertEquals("无法连接影视推荐服务，请检查网络或代理后重试", state.error)
            store.dispose()
        }

    @Test
    fun cached_recommendations_remain_visible_when_live_refresh_fails() =
        runTest(scheduler) {
            val settings = MapSettings()
            TmdbHomeCache(settings) { isoDateDaysBefore(currentIsoDate(), 1) }.write(CACHED_HOME)
            val cache = TmdbHomeCache(settings)
            val store = homeStore(cache, UnconfinedTestDispatcher(testScheduler))

            val state = store.states.first { !it.loading }

            assertFalse(state.loading)
            assertEquals(42, state.featuredToday?.id)
            assertEquals(
                42,
                state.content.rows
                    .single()
                    .items
                    .single()
                    .id,
            )
            assertEquals(null, state.error)
            assertNotNull(state.recommendationNotice)
            store.dispose()
        }

    @Test
    fun partial_refresh_keeps_yesterdays_four_shelves_without_refreshing_the_cache_date() =
        runTest(scheduler) {
            val settings = MapSettings()
            val yesterday = isoDateDaysBefore(currentIsoDate(), 1)
            TmdbHomeCache(settings) { yesterday }.write(FOUR_ROW_HOME)
            val originalCache = settings.getStringOrNull(CACHE_KEY)
            val cache = TmdbHomeCache(settings)
            var requests = 0
            val tmdb =
                mockTmdb { request ->
                    requests++
                    if (request.url.encodedPath.endsWith("/movie/popular")) {
                        popularRecommendations(42, 55)
                    } else {
                        throw IOException("This feed is unavailable")
                    }
                }

            repeat(2) {
                val before = requests
                val store = homeStore(cache, UnconfinedTestDispatcher(testScheduler), tmdb)
                try {
                    val state = store.states.first { !it.loading }
                    assertEquals(
                        FOUR_ROW_HOME.rows.map { row ->
                            row.title
                        },
                        state.content.rows.map { row -> row.title },
                    )
                    val popular =
                        state.content.rows
                            .first()
                            .items
                    assertEquals(listOf(42, 55, 43), popular.map { item -> item.id })
                    assertEquals("新推荐-42", popular.first().title, "Fresh metadata must win over the cached duplicate")
                    assertEquals(listOf(42, 55, 43), state.content.featured.map { item -> item.id })
                    assertEquals(FOUR_ROW_HOME.rows.drop(1), state.content.rows.drop(1))
                    assertTrue(assertNotNull(state.recommendationNotice).contains("部分推荐未更新"))
                    assertTrue(state.recommendationNotice!!.contains("保留上次结果"))
                    assertNull(state.error)
                    assertEquals(originalCache, settings.getStringOrNull(CACHE_KEY))
                    assertEquals(yesterday, cache.readCached()?.savedOn)
                    assertTrue(requests > before, "Reopening on the same day must retry the incomplete refresh")
                } finally {
                    store.dispose()
                }
            }
        }

    @Test
    fun complete_refresh_clears_the_partial_notice_and_replaces_old_shelves() =
        runTest(scheduler) {
            val settings = MapSettings()
            TmdbHomeCache(settings) { isoDateDaysBefore(currentIsoDate(), 1) }.write(FOUR_ROW_HOME)
            val cache = TmdbHomeCache(settings)
            var complete = false
            val tmdb =
                mockTmdb { request ->
                    when {
                        request.url.encodedPath.endsWith("/movie/popular") ->
                            popularRecommendations(if (complete) 88 else 55)
                        complete -> json("""{"results":[]}""")
                        else -> throw IOException("This feed is unavailable")
                    }
                }
            val store = homeStore(cache, UnconfinedTestDispatcher(testScheduler), tmdb)
            try {
                assertNotNull(store.states.first { !it.loading }.recommendationNotice)
                complete = true
                store.accept(HomeIntent.Refresh)
                val state = store.states.first { !it.loading }

                assertNull(state.recommendationNotice)
                assertNull(state.error)
                assertFalse(state.refreshing)
                assertEquals(listOf("热门"), state.content.rows.map { it.title })
                assertEquals(
                    listOf(88),
                    state.content.rows
                        .single()
                        .items
                        .map { it.id },
                )
                assertEquals(listOf(88), state.content.featured.map { it.id })
                assertEquals(currentIsoDate(), cache.readCached()?.savedOn)
                assertEquals(state.content, cache.read())
            } finally {
                store.dispose()
            }
        }

    @Test
    fun partial_first_load_does_not_claim_to_show_old_content() =
        runTest(scheduler) {
            val tmdb =
                mockTmdb { request ->
                    if (request.url.encodedPath.endsWith("/movie/popular")) {
                        popularRecommendations(55)
                    } else {
                        throw IOException("This feed is unavailable")
                    }
                }
            val cache = TmdbHomeCache(MapSettings())
            val store = homeStore(cache, UnconfinedTestDispatcher(testScheduler), tmdb)
            try {
                val notice = assertNotNull(store.states.first { !it.loading }.recommendationNotice)
                assertTrue(notice.contains("部分推荐未更新"))
                assertFalse(notice.contains("上次"))
                assertFalse(notice.contains("缓存"))
                assertNull(cache.readCached())
            } finally {
                store.dispose()
            }
        }

    @Test
    fun empty_successful_feeds_clear_old_shelves_while_only_the_failed_popular_feed_is_retained() =
        runTest(scheduler) {
            for (hasCache in listOf(true, false)) {
                val settings = MapSettings()
                val yesterday = isoDateDaysBefore(currentIsoDate(), 1)
                if (hasCache) TmdbHomeCache(settings) { yesterday }.write(FOUR_ROW_HOME)
                val originalCache = settings.getStringOrNull(CACHE_KEY)
                val cache = TmdbHomeCache(settings)
                var requests = 0
                val tmdb =
                    mockTmdb { request ->
                        requests++
                        if (request.url.encodedPath.endsWith("/tv/popular")) {
                            respond(
                                "{}",
                                HttpStatusCode.ServiceUnavailable,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            json("""{"results":[]}""")
                        }
                    }
                val store = homeStore(cache, UnconfinedTestDispatcher(testScheduler), tmdb)
                try {
                    repeat(2) { attempt ->
                        val before = requests
                        if (attempt > 0) store.accept(HomeIntent.Retry)
                        val state = store.states.first { !it.loading }
                        val notice = assertNotNull(state.recommendationNotice)
                        assertTrue(notice.contains("部分推荐未更新"), notice)
                        assertTrue(notice.contains("暂时不可用"), notice)
                        assertNull(state.error)
                        if (hasCache) {
                            assertEquals(listOf(FOUR_ROW_HOME.rows.first()), state.content.rows)
                            assertEquals(FOUR_ROW_HOME.featured, state.content.featured)
                            assertTrue(notice.contains("保留上次结果"), notice)
                            assertEquals(yesterday, cache.readCached()?.savedOn)
                        } else {
                            assertTrue(state.content.isEmpty)
                            assertFalse(notice.contains("上次"), notice)
                            assertNull(cache.readCached())
                        }
                        assertEquals(originalCache, settings.getStringOrNull(CACHE_KEY))
                        if (attempt > 0) assertTrue(requests > before, "An empty partial result must remain retryable")
                    }
                } finally {
                    store.dispose()
                }
            }
        }

    @Test
    fun authorization_and_rate_limit_failures_have_distinct_actionable_notices() =
        runTest(scheduler) {
            for ((status, expected) in listOf(
                HttpStatusCode.Unauthorized to "认证失败",
                HttpStatusCode.TooManyRequests to "过于频繁",
            )) {
                for (hasCache in listOf(false, true)) {
                    val settings = MapSettings()
                    if (hasCache) {
                        TmdbHomeCache(settings) { isoDateDaysBefore(currentIsoDate(), 1) }.write(CACHED_HOME)
                    }
                    val tmdb =
                        mockTmdb {
                            respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    val store = homeStore(TmdbHomeCache(settings), UnconfinedTestDispatcher(testScheduler), tmdb)
                    try {
                        val state = store.states.first { !it.loading }
                        val message = assertNotNull(if (hasCache) state.recommendationNotice else state.error)
                        assertTrue(message.contains(expected), message)
                        assertFalse(message.contains("服务器离线"), message)
                        if (hasCache) {
                            assertEquals(CACHED_HOME, state.content)
                            assertTrue(message.contains("已保留上次显示的推荐内容"))
                            assertNull(state.error)
                        }
                    } finally {
                        store.dispose()
                    }
                }
            }
        }

    @Test
    fun settings_read_and_cleanup_failures_do_not_prevent_live_recommendations() =
        runTest(scheduler) {
            val delegate = MapSettings()
            val settings =
                object : Settings by delegate {
                    override fun getStringOrNull(key: String): String? = error("Read unavailable")

                    override fun remove(key: String) = error("Cleanup unavailable")
                }
            var requests = 0
            val tmdb =
                mockTmdb { request ->
                    requests++
                    if (request.url.encodedPath.endsWith(
                            "/movie/popular",
                        )
                    ) {
                        popularRecommendations(55)
                    } else {
                        json("""{"results":[]}""")
                    }
                }
            val store = homeStore(TmdbHomeCache(settings), UnconfinedTestDispatcher(testScheduler), tmdb)
            try {
                val state = store.states.first { !it.loading }
                assertTrue(requests > 0)
                assertEquals(listOf(55), state.content.featured.map { it.id })
                assertNull(state.error)
                assertNull(state.recommendationNotice)
            } finally {
                store.dispose()
            }
        }

    @Test
    fun partial_merge_preserves_media_type_identity_and_clears_successfully_empty_shelves() {
        val movie = CACHED_ITEM
        val tv = movie.copy(mediaType = "tv")
        val old = TmdbHome(listOf(movie, tv), listOf(TmdbRow("热门", listOf(movie, tv)), TmdbRow("最新上线", listOf(movie))))
        val freshMovie = movie.copy(title = "新资料")
        val fresh = TmdbHome(listOf(freshMovie), listOf(TmdbRow("热门", listOf(freshMovie))))

        val partial =
            mergeRecommendationRefresh(old, TmdbHomeRefresh(fresh, setOf("热门"), TmdbRecommendationFailure.TIMEOUT))
        assertEquals(
            listOf(freshMovie, tv),
            partial.content.rows
                .single()
                .items,
        )
        assertEquals(listOf(freshMovie, tv), partial.content.featured)
        assertTrue(partial.usedPreviousContent)

        val complete = mergeRecommendationRefresh(old, TmdbHomeRefresh(fresh))
        assertEquals(fresh, complete.content)
        assertFalse(complete.usedPreviousContent)

        val sameIds = mergeRecommendationRefresh(TmdbHome(listOf(movie)), TmdbHomeRefresh(fresh, setOf("热门")))
        assertFalse(sameIds.usedPreviousContent, "Replacing old metadata for the same item does not reuse old content")
    }

    @Test
    fun repeated_partial_refreshes_are_bounded_and_restore_missing_shelves_in_display_order() {
        var previous = TmdbHome(rows = listOf(TmdbRow("即将上映", listOf(CACHED_ITEM))))
        repeat(100) { index ->
            val item = CACHED_ITEM.copy(id = 100 + index)
            previous =
                mergeRecommendationRefresh(
                    previous,
                    TmdbHomeRefresh(
                        TmdbHome(listOf(item), listOf(TmdbRow("热门", listOf(item)))),
                        setOf("热门", "最新上线", "正在上映", "即将上映"),
                    ),
                ).content
        }
        assertEquals(listOf("热门", "即将上映"), previous.rows.map { it.title })
        assertEquals(
            80,
            previous.rows
                .first()
                .items.size,
        )
        assertEquals(21, previous.featured.size)
        assertEquals(
            199,
            previous.rows
                .first()
                .items
                .first()
                .id,
        )
        assertEquals(199, previous.featured.first().id)

        val restored =
            mergeRecommendationRefresh(
                previous,
                TmdbHomeRefresh(
                    TmdbHome(rows = listOf(TmdbRow("最新上线", listOf(CACHED_ITEM)))),
                    setOf("热门", "正在上映", "即将上映"),
                ),
            )
        assertEquals(listOf("热门", "最新上线", "即将上映"), restored.content.rows.map { it.title })

        val replacement = List(80) { CACHED_ITEM.copy(id = 1_000 + it) }
        val allNew =
            mergeRecommendationRefresh(
                previous,
                TmdbHomeRefresh(
                    TmdbHome(replacement.take(21), listOf(TmdbRow("热门", replacement))),
                    setOf("热门"),
                ),
            )
        assertFalse(
            allNew.usedPreviousContent,
            "Old entries outside the visible bound must not trigger an old-content notice",
        )
    }

    @Test
    fun matched_recommendation_opens_emby_detail() =
        runTest(scheduler) {
            val server =
                SavedServer(
                    id = "one",
                    baseUrl = "http://one",
                    serverName = "One",
                    userId = "u",
                    userName = "User",
                    accessToken = "token",
                )
            val registry = testRegistry().apply { addOrUpdate(server) }
            val store =
                HomeStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    tmdb = unavailableTmdb(),
                    emby =
                        testRepo(dispatcher = UnconfinedTestDispatcher(testScheduler)) { request ->
                            if (request.url.parameters["AnyProviderIdEquals"] == "tmdb.42") {
                                json(
                                    """
                                    {
                                      "Items": [
                                        {"Id":"emby-42","Name":"缓存推荐","Type":"Movie","ProductionYear":2026}
                                      ]
                                    }
                                    """.trimIndent(),
                                )
                            } else {
                                homeRoutes(request)
                            }
                        },
                    registry = registry,
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).create()

            store.labels.test {
                store.accept(HomeIntent.Open(CACHED_ITEM))
                assertEquals(HomeLabel.OpenEmbyItem("one", "emby-42"), awaitItem())
            }
            store.dispose()
        }

    @Test
    fun hero_play_action_starts_a_matched_emby_item() =
        runTest(scheduler) {
            val server =
                SavedServer(
                    id = "one",
                    baseUrl = "http://one",
                    serverName = "One",
                    userId = "u",
                    userName = "User",
                    accessToken = "token",
                )
            val registry = testRegistry().apply { addOrUpdate(server) }
            val store =
                HomeStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    tmdb = unavailableTmdb(),
                    emby =
                        testRepo(dispatcher = UnconfinedTestDispatcher(testScheduler)) { request ->
                            if (request.url.parameters["AnyProviderIdEquals"] == "tmdb.42") {
                                json("""{"Items":[{"Id":"emby-42","Name":"缓存推荐","Type":"Movie"}]}""")
                            } else {
                                homeRoutes(request)
                            }
                        },
                    registry = registry,
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).create()

            store.labels.test {
                store.accept(HomeIntent.Play(CACHED_ITEM))
                assertEquals(HomeLabel.PlayEmbyItem("one", "emby-42"), awaitItem())
            }
            store.dispose()
        }

    @Test
    fun canceled_old_cache_write_finishes_before_the_newer_write() =
        runTest(scheduler) {
            val firstWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseFirstWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
            var lastWrittenId: Int? = null
            val writer =
                RecommendationCacheWriter(
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    persist = { content ->
                        val id = content.featured.single().id
                        if (id == 1) {
                            firstWriteStarted.complete(Unit)
                            // Models a Settings commit that cannot be interrupted once started.
                            withContext(NonCancellable) { releaseFirstWrite.await() }
                        }
                        lastWrittenId = id
                    },
                )

            val oldWrite =
                launch {
                    writer.write(TmdbHome(featured = listOf(CACHED_ITEM.copy(id = 1))))
                }
            firstWriteStarted.await()
            oldWrite.cancel()
            val newWrite =
                launch {
                    writer.write(TmdbHome(featured = listOf(CACHED_ITEM.copy(id = 2))))
                }

            releaseFirstWrite.complete(Unit)
            joinAll(oldWrite, newWrite)

            assertEquals(2, lastWrittenId)
        }

    @Test
    fun known_unavailable_default_server_does_not_start_home_requests() =
        runTest(scheduler) {
            val baseUrl = "https://gy.emby.yun:8096"
            val registry =
                testRegistry().apply {
                    addOrUpdate(
                        SavedServer(
                            id = SavedServer.idOf(baseUrl, "user"),
                            baseUrl = baseUrl,
                            serverName = "Retired Emby",
                            userId = "user",
                            userName = "User",
                            accessToken = "token",
                        ),
                    )
                }
            var embyRequests = 0
            val store =
                HomeStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    tmdb = unavailableTmdb(),
                    emby =
                        testRepo {
                            embyRequests++
                            homeRoutes(it)
                        },
                    registry = registry,
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).create()

            advanceUntilIdle()

            assertEquals(0, embyRequests)
            store.dispose()
        }

    private fun homeStore(
        cache: TmdbHomeCache,
        cacheDispatcher: CoroutineDispatcher,
        tmdb: TmdbRepository = unavailableTmdb(),
    ) = HomeStoreFactory(
        storeFactory = DefaultStoreFactory(),
        tmdb = tmdb,
        emby = testRepo { homeRoutes(it) },
        registry = testRegistry(),
        cache = cache,
        cacheDispatcher = cacheDispatcher,
    ).create()

    private fun unavailableTmdb(): TmdbRepository = mockTmdb { throw IOException("TMDB unavailable") }

    private fun mockTmdb(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): TmdbRepository =
        TmdbRepository(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        // Cancellation must finish on the same clock before resetMain().
                        // Real IO work can otherwise report failures into the next test.
                        dispatcher = UnconfinedTestDispatcher(scheduler)
                        addHandler(handler)
                    },
                ),
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

    private fun MockRequestHandleScope.popularRecommendations(vararg ids: Int): HttpResponseData =
        json(
            """{"results":[""" +
                ids.joinToString(",") { id ->
                    """{"id":$id,"title":"新推荐-$id","poster_path":"/$id.jpg","backdrop_path":"/$id-bg.jpg","genre_ids":[18]}"""
                } + "]}",
        )

    private companion object {
        const val CACHE_KEY = "tmdb.home.cache.v1"
        val CACHED_ITEM =
            TmdbItem(
                id = 42,
                title = "缓存推荐",
                overview = "上次成功加载的推荐",
                posterPath = "/42.jpg",
                backdropPath = "/42-backdrop.jpg",
                year = "2026",
                mediaType = "movie",
                rating = 8.5,
            )
        val CACHED_HOME =
            TmdbHome(
                featured = listOf(CACHED_ITEM),
                rows = listOf(TmdbRow("热门", listOf(CACHED_ITEM))),
            )
        val FOUR_ROW_HOME =
            TmdbHome(
                featured = listOf(CACHED_ITEM, CACHED_ITEM.copy(id = 43)),
                rows =
                    listOf(
                        TmdbRow("热门", listOf(CACHED_ITEM, CACHED_ITEM.copy(id = 43))),
                        TmdbRow("最新上线", listOf(CACHED_ITEM.copy(id = 44))),
                        TmdbRow("正在上映", listOf(CACHED_ITEM.copy(id = 45))),
                        TmdbRow("即将上映", listOf(CACHED_ITEM.copy(id = 46))),
                    ),
            )
    }
}
