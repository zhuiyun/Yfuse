package com.yfuse.feature.library

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
        ).create()

        val s = store.states.first { !it.loading && !it.content.isEmpty }
        assertEquals("我的服务器", s.currentServer?.serverName)
        assertEquals(1, s.content.resume.size)
        assertTrue(s.content.rows.isNotEmpty())
        assertEquals(42, s.content.counts?.movieCount)
        assertEquals(7, s.content.counts?.seriesCount)
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
        store.dispose()
    }
}
