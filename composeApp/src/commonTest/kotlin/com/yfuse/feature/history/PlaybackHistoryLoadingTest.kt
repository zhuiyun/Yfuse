package com.yfuse.feature.history

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.LocalViewingStore
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHistoryLoadingTest {
    private val server = SavedServer("first", "https://first", "First", "u", "User", "token")

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rapidScrollingCancelsOldAndQueuedCardsBeforeLoadingTheVisibleCard() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val lifecycle = LifecycleRegistry()
            try {
                val registry = testRegistry().apply { addOrUpdate(server) }
                val personal = history("old-0", "old-1", "old-2", "old-3", "old-4", "visible")
                val requested = mutableListOf<String>()
                val never = CompletableDeferred<Unit>()
                val component =
                    PlaybackHistoryComponent(
                        DefaultComponentContext(lifecycle),
                        registry,
                        testRepo(dispatcher = StandardTestDispatcher(testScheduler)) { request ->
                            val id = request.url.encodedPath.substringAfterLast('/')
                            requested += id
                            if (id.startsWith("old-")) never.await()
                            json("""{"Id":"$id","Name":"$id","Type":"Movie","Overview":"ready"}""")
                        },
                        onBack = {},
                        onOpenItem = { _, _ -> },
                        onOpenMetadata = {},
                        personal = personal,
                        viewing = LocalViewingStore(MapSettings(), personal),
                    )
                val rows = rows(personal)
                component.updateVisibleRows(
                    rows.filter {
                        it.entry.media.serverItemId!!
                            .startsWith("old-")
                    },
                )
                runCurrent()
                assertEquals(3, requested.size)
                val visible = rows.single { it.entry.media.serverItemId == "visible" }
                component.updateVisibleRows(listOf(visible))
                advanceUntilIdle()
                assertTrue("visible" in requested)
                assertEquals(4, requested.size)
                assertEquals(
                    "ready",
                    component.metadata.value[component.metadataKey(visible)]
                        ?.item
                        ?.overview,
                )
                assertFalse(
                    component.metadata.value.keys
                        .any { it.itemId.startsWith("old-") },
                )
            } finally {
                lifecycle.destroy()
            }
        }

    @Test
    fun aFailureHasACooldownButExplicitRetryCanRecoverImmediately() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val lifecycle = LifecycleRegistry()
            try {
                val registry = testRegistry().apply { addOrUpdate(server) }
                val personal = history("movie")
                var healthy = false
                var calls = 0
                var now = 1_000L
                val component =
                    PlaybackHistoryComponent(
                        DefaultComponentContext(lifecycle),
                        registry,
                        testRepo(dispatcher = StandardTestDispatcher(testScheduler)) {
                            calls++
                            if (!healthy) throw IOException("offline")
                            json("""{"Id":"movie","Type":"Movie","Overview":"Recovered"}""")
                        },
                        onBack = {},
                        onOpenItem = { _, _ -> },
                        onOpenMetadata = {},
                        personal = personal,
                        viewing = LocalViewingStore(MapSettings(), personal),
                        nowMs = { now },
                    )
                val row = rows(personal).single()
                component.loadMetadata(row)
                val key = component.metadataKey(row)!!
                assertTrue(component.metadata.value.containsKey(key))
                assertNull(component.metadata.value[key])
                val failedCalls = calls
                healthy = true
                now += 1_000L
                component.loadMetadata(row)
                assertEquals(failedCalls, calls)
                component.retryMetadata(row)
                advanceUntilIdle()
                assertTrue(calls > failedCalls)
                assertEquals(
                    "Recovered",
                    component.metadata.value[key]
                        ?.item
                        ?.overview,
                )
                val successCalls = calls
                component.loadMetadata(row)
                assertEquals(successCalls, calls)
            } finally {
                lifecycle.destroy()
            }
        }

    @Test
    fun concurrentEpisodesShareTheParentSeriesRequest() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val lifecycle = LifecycleRegistry()
            try {
                val registry = testRegistry().apply { addOrUpdate(server) }
                val personal = history("episode-a", "episode-b")
                val parentReady = CompletableDeferred<Unit>()
                val calls = mutableListOf<String>()
                val component =
                    PlaybackHistoryComponent(
                        DefaultComponentContext(lifecycle),
                        registry,
                        testRepo(dispatcher = StandardTestDispatcher(testScheduler)) { request ->
                            val id = request.url.encodedPath.substringAfterLast('/')
                            calls += id
                            if (id == "series") {
                                parentReady.await()
                                json("""{"Id":"series","Type":"Series","Overview":"Shared synopsis"}""")
                            } else {
                                json("""{"Id":"$id","Type":"Episode","SeriesId":"series"}""")
                            }
                        },
                        onBack = {},
                        onOpenItem = { _, _ -> },
                        onOpenMetadata = {},
                        personal = personal,
                        viewing = LocalViewingStore(MapSettings(), personal),
                    )
                component.updateVisibleRows(rows(personal))
                runCurrent()
                assertEquals(1, calls.count { it == "series" })
                parentReady.complete(Unit)
                advanceUntilIdle()
                assertEquals(3, calls.size)
                assertEquals(2, component.metadata.value.size)
                component.metadata.value.values
                    .forEach { assertNotNull(it?.series) }
            } finally {
                lifecycle.destroy()
            }
        }

    private fun history(vararg ids: String) =
        PersonalLibraryRepository(MapSettings()).apply {
            ids.forEach { id ->
                val type = if (id.startsWith("episode-")) "Episode" else "Movie"
                recordHistory(
                    PersonalMediaRef("emby:$id", id, type, serverId = server.id, serverItemId = id),
                    10,
                    100,
                    false,
                )
            }
        }

    private fun rows(personal: PersonalLibraryRepository) =
        playbackHistoryRows(personal.state.value.history, emptyList(), listOf(server), personal.policy.value)
}
