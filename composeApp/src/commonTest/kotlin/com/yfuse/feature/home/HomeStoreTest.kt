package com.yfuse.feature.home

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbRow
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

class HomeStoreTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun unavailable_recommendations_do_not_claim_the_emby_server_is_offline() = runTest {
        val store = homeStore(TmdbHomeCache(MapSettings()))

        val state = store.states.first { !it.loading }

        assertFalse(state.loading)
        assertEquals("影视推荐服务暂时不可用，请稍后重试", state.error)
        store.dispose()
    }

    @Test
    fun cached_recommendations_remain_visible_when_live_refresh_fails() = runTest {
        val cache = TmdbHomeCache(MapSettings()).apply { write(CACHED_HOME) }
        val store = homeStore(cache)

        val state = store.states.first { !it.loading }

        assertFalse(state.loading)
        assertEquals(42, state.featuredToday?.id)
        assertEquals(42, state.content.rows.single().items.single().id)
        assertEquals(null, state.error)
        assertNotNull(state.recommendationNotice)
        store.dispose()
    }

    @Test
    fun canceled_old_cache_write_finishes_before_the_newer_write() = runTest {
        val firstWriteStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseFirstWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
        var lastWrittenId: Int? = null
        val writer = RecommendationCacheWriter(
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

        val oldWrite = launch {
            writer.write(TmdbHome(featured = listOf(CACHED_ITEM.copy(id = 1))))
        }
        firstWriteStarted.await()
        oldWrite.cancel()
        val newWrite = launch {
            writer.write(TmdbHome(featured = listOf(CACHED_ITEM.copy(id = 2))))
        }

        releaseFirstWrite.complete(Unit)
        joinAll(oldWrite, newWrite)

        assertEquals(2, lastWrittenId)
    }

    private fun homeStore(cache: TmdbHomeCache) = HomeStoreFactory(
        storeFactory = DefaultStoreFactory(),
        tmdb = unavailableTmdb(),
        emby = testRepo { homeRoutes(it) },
        registry = testRegistry(),
        cache = cache,
    ).create()

    private fun unavailableTmdb(): TmdbRepository = TmdbRepository(
        HttpClient(
            MockEngine { throw IOException("TMDB unavailable") },
        ) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
    )

    private companion object {
        val CACHED_ITEM = TmdbItem(
            id = 42,
            title = "缓存推荐",
            overview = "上次成功加载的推荐",
            posterPath = "/42.jpg",
            backdropPath = "/42-backdrop.jpg",
            year = "2026",
            mediaType = "movie",
            rating = 8.5,
        )
        val CACHED_HOME = TmdbHome(
            featured = listOf(CACHED_ITEM),
            rows = listOf(TmdbRow("热门", listOf(CACHED_ITEM))),
        )
    }
}
