package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.io.IOException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryStoreTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loads_home_content_for_default_server() = runTest {
        val registry = testRegistry()
        registry.addOrUpdate(SavedServer("id1", "http://host:8096", "我的服务器", "u1", "zhuiyun", "tok"))
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { homeRoutes(it) },
            registry,
            LibraryCache(MapSettings()),
            nowEpochMs = { 1_700_000_000_000L },
        ).create()

        val s = store.states.first { !it.loading && !it.content.isEmpty }
        assertEquals("我的服务器", s.currentServer?.serverName)
        assertEquals(1, s.content.resume.size)
        assertTrue(s.content.rows.isNotEmpty())
        assertEquals(42, s.content.counts?.movieCount)
        assertEquals(7, s.content.counts?.seriesCount)
        assertEquals(LibraryContentSource.Live, s.contentSource)
        assertEquals(1_700_000_000_000L, s.updatedAtEpochMs)
        store.dispose()
    }

    @Test
    fun no_server_shows_empty() = runTest {
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { json("{}") },
            testRegistry(),
            LibraryCache(MapSettings()),
        ).create()
        val s = store.states.first()
        assertEquals(null, s.currentServer)
        assertTrue(s.content.isEmpty)
        store.dispose()
    }

    @Test
    fun stale_server_response_cannot_replace_the_new_server_home() = runTest {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val oldRequestSettled = CompletableDeferred<Unit>()
        val registry = testRegistry().apply {
            addOrUpdate(SavedServer("old", "http://old:8096", "旧服务器", "u1", "user", "old-token"))
            addOrUpdate(SavedServer("new", "http://new:8096", "新服务器", "u1", "user", "new-token"))
        }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { request ->
                val oldServer = request.url.host == "old"
                if (oldServer && request.url.encodedPath.endsWith("/Views")) {
                    oldRequestStarted.complete(Unit)
                    try {
                        releaseOldRequest.await()
                    } catch (cancelled: CancellationException) {
                        oldRequestSettled.complete(Unit)
                        throw cancelled
                    }
                }
                homeRoutes(
                    request,
                    movieCount = if (oldServer) 1 else 99,
                    seriesCount = if (oldServer) 2 else 88,
                ).also {
                    if (oldServer && request.url.encodedPath.endsWith("/Items/Latest")) {
                        oldRequestSettled.complete(Unit)
                    }
                }
            },
            registry,
            LibraryCache(MapSettings()),
        ).create()

        oldRequestStarted.await()
        registry.setDefault("new")
        store.states.first {
            it.currentServer?.id == "new" && !it.loading && !it.content.isEmpty
        }

        releaseOldRequest.complete(Unit)
        oldRequestSettled.await()
        advanceUntilIdle()

        assertEquals("new", store.state.currentServer?.id)
        assertEquals(null, store.state.error)
        assertEquals(99, store.state.content.counts?.movieCount)
        assertEquals(88, store.state.content.counts?.seriesCount)
        assertTrue(store.state.content.rows.isNotEmpty())
        store.dispose()
    }

    @Test
    fun token_rotation_with_the_same_server_id_reloads_the_home() = runTest {
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val oldRequestSettled = CompletableDeferred<Unit>()
        val registry = testRegistry().apply {
            addOrUpdate(
                SavedServer(
                    "same",
                    "http://same:8096",
                    "服务器",
                    "u1",
                    "user",
                    "old-token",
                ),
            )
        }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { request ->
                val oldToken = request.headers["X-Emby-Token"] == "old-token"
                if (oldToken && request.url.encodedPath.endsWith("/Views")) {
                    oldRequestStarted.complete(Unit)
                    try {
                        releaseOldRequest.await()
                    } catch (cancelled: CancellationException) {
                        oldRequestSettled.complete(Unit)
                        throw cancelled
                    }
                }
                homeRoutes(
                    request,
                    movieCount = if (oldToken) 1 else 99,
                    seriesCount = if (oldToken) 2 else 88,
                )
            },
            registry,
            LibraryCache(MapSettings()),
        ).create()

        oldRequestStarted.await()
        assertTrue(
            registry.replace(
                "same",
                SavedServer(
                    "same",
                    "http://same:8096",
                    "服务器",
                    "u1",
                    "user",
                    "new-token",
                ),
            ),
        )
        val refreshed = store.states.first {
            !it.loading && it.content.counts?.movieCount == 99
        }

        releaseOldRequest.complete(Unit)
        oldRequestSettled.await()
        advanceUntilIdle()

        assertEquals("new-token", refreshed.currentServer?.accessToken)
        assertEquals(99, store.state.content.counts?.movieCount)
        assertEquals(null, store.state.error)
        store.dispose()
    }

    @Test
    fun token_rotation_does_not_replace_fresh_memory_with_an_old_cache_snapshot() = runTest {
        val newRequestStarted = CompletableDeferred<Unit>()
        val releaseNewRequest = CompletableDeferred<Unit>()
        val registry = testRegistry().apply {
            addOrUpdate(
                SavedServer(
                    "same",
                    "http://same:8096",
                    "服务器",
                    "u1",
                    "user",
                    "old-token",
                ),
            )
        }
        val cache = LibraryCache(MapSettings())
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { request ->
                val newToken = request.headers["X-Emby-Token"] == "new-token"
                if (newToken && request.url.encodedPath.endsWith("/Views")) {
                    newRequestStarted.complete(Unit)
                    releaseNewRequest.await()
                }
                homeRoutes(
                    request,
                    movieCount = if (newToken) 88 else 99,
                    seriesCount = if (newToken) 77 else 66,
                )
            },
            registry,
            cache,
        ).create()

        store.states.first { !it.loading && it.content.counts?.movieCount == 99 }
        cache.write(
            "same",
            store.state.content.copy(
                counts = store.state.content.counts?.copy(movieCount = 1),
            ),
            updatedAtEpochMs = 1_600_000_000_000L,
        )

        assertTrue(
            registry.replace(
                "same",
                SavedServer(
                    "same",
                    "http://same:8096",
                    "服务器",
                    "u1",
                    "user",
                    "new-token",
                ),
            ),
        )
        newRequestStarted.await()

        assertTrue(store.state.loading)
        assertEquals(99, store.state.content.counts?.movieCount)

        releaseNewRequest.complete(Unit)
        val refreshed = store.states.first {
            !it.loading && it.content.counts?.movieCount == 88
        }
        assertEquals("new-token", refreshed.currentServer?.accessToken)
        assertEquals(LibraryContentSource.Live, refreshed.contentSource)
        store.dispose()
    }

    @Test
    fun cachedColdStartBecomesLiveAndAdvancesTimestamp() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val oldUpdatedAt = 1_600_000_000_000L
        val liveUpdatedAt = 1_700_000_000_000L
        val server = SavedServer("id1", "http://host:8096", "服务器", "u1", "user", "token")
        val registry = testRegistry().apply { addOrUpdate(server) }
        val cache = LibraryCache(MapSettings()).apply {
            write(server.id, content("cached", movieCount = 1), oldUpdatedAt)
        }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { request ->
                if (request.url.encodedPath.endsWith("/Views")) {
                    requestStarted.complete(Unit)
                    releaseRequest.await()
                }
                homeRoutes(request, movieCount = 42, seriesCount = 7)
            },
            registry,
            cache,
            nowEpochMs = { liveUpdatedAt },
        ).create()

        requestStarted.await()
        assertEquals(LibraryContentSource.Cached, store.state.contentSource)
        assertEquals("cached", store.state.content.featured.single().id)
        assertEquals(oldUpdatedAt, store.state.updatedAtEpochMs)
        assertTrue(store.state.loading)

        releaseRequest.complete(Unit)
        val live = store.states.first { !it.loading && it.contentSource == LibraryContentSource.Live }
        assertEquals(42, live.content.counts?.movieCount)
        assertEquals(liveUpdatedAt, live.updatedAtEpochMs)
        assertEquals(liveUpdatedAt, cache.readSnapshot(server.id)?.updatedAtEpochMs)
        store.dispose()
    }

    @Test
    fun cachedFailureKeepsContentAndMarksItOffline() = runTest {
        val updatedAt = 1_600_000_000_000L
        val server = SavedServer("id1", "http://offline:8096", "服务器", "u1", "user", "token")
        val registry = testRegistry().apply { addOrUpdate(server) }
        val cache = LibraryCache(MapSettings()).apply {
            write(server.id, content("cached", movieCount = 3), updatedAt)
        }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { throw IOException("offline") },
            registry,
            cache,
        ).create()

        val failed = store.states.first { !it.loading && it.error != null }

        assertEquals(LibraryContentSource.Cached, failed.contentSource)
        assertEquals("cached", failed.content.featured.single().id)
        assertEquals(3, failed.content.counts?.movieCount)
        assertEquals(updatedAt, failed.updatedAtEpochMs)
        store.dispose()
    }

    @Test
    fun serverSwitchUsesOnlyTheSelectedServersCacheAndFreshness() = runTest {
        val first = SavedServer("first", "http://first:8096", "一号", "u1", "user", "one")
        val second = SavedServer("second", "http://second:8096", "二号", "u2", "user", "two")
        val registry = testRegistry().apply {
            addOrUpdate(first)
            addOrUpdate(second)
        }
        val cache = LibraryCache(MapSettings()).apply {
            write(first.id, content("first-cache", movieCount = 1), 1_600_000_000_001L)
            write(second.id, content("second-cache", movieCount = 2), 1_600_000_000_002L)
        }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo { throw IOException("offline") },
            registry,
            cache,
        ).create()

        store.states.first { it.currentServer?.id == first.id && !it.loading && it.error != null }
        registry.setDefault(second.id)
        val switched = store.states.first {
            it.currentServer?.id == second.id && !it.loading && it.error != null
        }

        assertEquals(LibraryContentSource.Cached, switched.contentSource)
        assertEquals("second-cache", switched.content.featured.single().id)
        assertEquals(2, switched.content.counts?.movieCount)
        assertEquals(1_600_000_000_002L, switched.updatedAtEpochMs)
        store.dispose()
    }

    private fun content(id: String, movieCount: Int): HomeContent = HomeContent(
        featured = listOf(
            MediaItem(
                id = id,
                title = id,
                subtitle = null,
                type = "Movie",
                posterItemId = id,
                posterTag = null,
                backdropItemId = null,
                backdropTag = null,
                playedPercentage = null,
            ),
        ),
        counts = LibraryCounts(movieCount = movieCount, seriesCount = 0),
    )
}
