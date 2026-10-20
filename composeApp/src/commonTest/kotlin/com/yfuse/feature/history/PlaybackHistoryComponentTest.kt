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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
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

class PlaybackHistoryComponentTest {
    private val first = SavedServer("first", "https://first", "First", "u", "User", "token")
    private val second = first.copy(id = "second", baseUrl = "https://second")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDefaultServerSwitchNeverRedirectsTheRecordedEpisode() =
        runTest {
            val registry =
                testRegistry().apply {
                    addOrUpdate(first)
                    addOrUpdate(second)
                }
            val personal = PersonalLibraryRepository(MapSettings())
            val media =
                PersonalMediaRef("tmdb:7/s2e3", "第 3 集", "Episode", serverId = "first", serverItemId = "episode-3")
            personal.recordHistory(media, 60_000, 240_000, false)
            val lifecycle = LifecycleRegistry()
            val opened = mutableListOf<Pair<String, String>>()
            val played = mutableListOf<Triple<String, String, Long>>()
            val component =
                PlaybackHistoryComponent(
                    DefaultComponentContext(lifecycle),
                    registry,
                    testRepo { error("A detail tap must not prefetch or play") },
                    onBack = {},
                    onOpenItem = { server, item -> opened += server to item },
                    onOpenMetadata = {},
                    onContinue = { server, item, ticks -> played += Triple(server, item, ticks) },
                    personal = personal,
                    viewing = LocalViewingStore(MapSettings(), personal),
                )
            val row =
                playbackHistoryRows(
                    personal.state.value.history,
                    emptyList(),
                    registry.data.value.servers,
                    personal.policy.value,
                ).single()
            registry.setDefault("second")
            assertTrue(component.open(row))
            assertEquals(listOf("first" to "episode-3"), opened)
            assertTrue(component.canContinue(row))
            assertTrue(component.continuePlayback(row))
            assertEquals(listOf(Triple("first", "episode-3", 600_000_000L)), played)
            assertFalse(component.continuePlayback(row.copy(entry = row.entry.copy(profileId = "another-profile"))))
            registry.remove("first")
            assertFalse(component.open(row))
            assertFalse(component.continuePlayback(row))
            assertEquals(1, played.size)
            assertEquals(1, opened.size)
            lifecycle.destroy()
        }

    @Test
    fun visibleCardRequestsShareSynopsisLoadingAndRetainItForDetailReturn() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val lifecycle = LifecycleRegistry()
            try {
                val registry = testRegistry().apply { addOrUpdate(first) }
                val personal = PersonalLibraryRepository(MapSettings())
                val media =
                    PersonalMediaRef("tmdb:7/s2e3", "第 3 集", "Episode", serverId = "first", serverItemId = "episode-3")
                personal.recordHistory(media, 60_000, 240_000, false)
                val requested = mutableListOf<String>()
                val repo =
                    testRepo(dispatcher = StandardTestDispatcher(testScheduler)) { request ->
                        requested += request.url.encodedPath
                        when {
                            request.url.encodedPath.endsWith(
                                "/episode-3",
                            ) ->
                                json(
                                    """{
                                        "Id":"episode-3","Type":"Episode","SeriesId":"show","SeriesName":"剧名",
                                        "ParentIndexNumber":2,"IndexNumber":3,"Overview":"单集简介"
                                    }""",
                                )
                            request.url.encodedPath.endsWith(
                                "/show",
                            ) -> json("""{"Id":"show","Type":"Series","Name":"剧名","Overview":"剧集简介"}""")
                            else -> error("Unexpected request: ${request.url.encodedPath}")
                        }
                    }
                val component =
                    PlaybackHistoryComponent(
                        DefaultComponentContext(lifecycle),
                        registry,
                        repo,
                        onBack = {},
                        onOpenItem = {
                                _,
                                _,
                            ->
                        },
                        onOpenMetadata = {},
                        personal = personal,
                        viewing = LocalViewingStore(MapSettings(), personal),
                    )
                val row =
                    playbackHistoryRows(
                        personal.state.value.history,
                        emptyList(),
                        registry.data.value.servers,
                        personal.policy.value,
                    ).single()
                val a = async { component.loadMetadata(row) }
                val b = async { component.loadMetadata(row) }
                a.await()
                b.await()
                component.loadMetadata(row)
                assertEquals(2, requested.size)
                val loaded =
                    component.metadata.value.values
                        .single()!!
                assertEquals("剧集简介", loaded.series?.overview)
                assertEquals(3, loaded.item.episodeNumber)
            } finally {
                lifecycle.destroy()
                Dispatchers.resetMain()
            }
        }
}
