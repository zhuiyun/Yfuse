package com.yfuse.feature.home

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackProgressProjection
import com.yfuse.core.data.TmdbHomeCache
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeLibraryShelvesTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun next_up_leaves_out_a_show_continue_watching_already_holds() {
        val resume = listOf(episode("e3", series = "s1"))
        val nextUp =
            listOf(
                // The local next-up list names a half-watched episode as its own next one.
                episode("e3", series = "s1"),
                episode("e4", series = "s1"),
                episode("f1", series = "s2", title = "另一部剧"),
            )

        assertEquals(listOf("f1"), homeNextUpShelf(nextUp, resume).map { it.item.id })
    }

    @Test
    fun next_up_keeps_one_card_per_show() {
        val nextUp =
            listOf(
                episode("e6", series = "s1"),
                episode("e3", series = "s1"),
                episode("f1", series = "s2", title = "另一部剧"),
            )

        assertEquals(listOf("e6", "f1"), homeNextUpShelf(nextUp, emptyList()).map { it.item.id })
    }

    @Test
    fun across_servers_a_show_is_known_by_its_name_and_a_film_is_never_a_show() {
        val resume = listOf(episode("e3", series = "s1", title = "某剧"), movie("m1", title = "另一部剧"))
        val nextUp =
            listOf(
                episode("x4", series = "t9", title = "某 剧", server = TWO),
                episode("y1", series = "t8", title = "另一部剧", server = TWO),
            )

        assertEquals(listOf("y1"), homeNextUpShelf(nextUp, resume).map { it.item.id })
    }

    @Test
    fun a_shelf_page_says_what_the_home_page_leaves_out() {
        assertEquals("Emby · 3 项", libraryRowPageCaption("Emby", shown = 3, total = 3))
        assertEquals("媒体库 · 显示 16 项，共 57 项", libraryRowPageCaption("媒体库", shown = 16, total = 57))
    }

    @Test
    fun marking_a_card_watched_writes_it_to_the_server_and_takes_it_off_both_shelves() =
        runTest(scheduler) {
            val registry = testRegistry().apply { addOrUpdate(ONE) }
            val progress = PlaybackSyncStore(MapSettings()) { 1_000L }
            progress.updatePlayback(
                mediaKey = "emby:e1",
                aliases = emptyList(),
                positionMs = 30_000L,
                durationMs = 100_000L,
                played = false,
                sessionId = "local",
                serverId = ONE.id,
                serverItemId = "e1",
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Periodic,
            )
            val writes = mutableListOf<String>()
            val store =
                HomeStoreFactory(
                    storeFactory = DefaultStoreFactory(),
                    tmdb = unreachableTmdb(),
                    emby =
                        testRepo(
                            dispatcher = UnconfinedTestDispatcher(testScheduler),
                            progressProjection = PlaybackProgressProjection(progress) { true },
                        ) { request ->
                            when {
                                "/PlayedItems/" in request.url.encodedPath -> {
                                    writes += "${request.method.value} ${request.url.encodedPath}"
                                    json("{}")
                                }
                                request.url.parameters["Ids"] == "e1" ->
                                    json(
                                        """{"Items":[{"Id":"e1","Name":"第1集","Type":"Episode",""" +
                                            """"SeriesName":"某剧","SeriesId":"s1","RunTimeTicks":1000000000}]}""",
                                    )
                                else -> homeRoutes(request)
                            }
                        },
                    registry = registry,
                    cache = TmdbHomeCache(MapSettings()),
                    cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
                ).create()
            try {
                advanceUntilIdle()
                val entry = store.state.resume.single()
                assertEquals(listOf("e1"), store.state.nextUp.map { it.item.id })
                // The half-watched episode is not offered twice, a shelf apart.
                assertTrue(homeNextUpShelf(store.state.nextUp, store.state.resume).isEmpty())

                store.accept(HomeIntent.SetPlayed(entry, true))
                advanceUntilIdle()

                assertEquals(listOf("POST /Users/u/PlayedItems/e1"), writes)
                assertTrue(store.state.resume.isEmpty())
                assertTrue(store.state.nextUp.isEmpty())
                assertEquals("已标记为看过", store.state.actionMessage)
            } finally {
                store.dispose()
            }
        }

    private fun unreachableTmdb(): TmdbRepository =
        TmdbRepository(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = UnconfinedTestDispatcher(scheduler)
                        addHandler { throw IOException("TMDB unavailable") }
                    },
                ),
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
        )

    private fun episode(
        id: String,
        series: String,
        title: String = "某剧",
        server: SavedServer = ONE,
    ) = HomeResumeEntry(media(id, title, type = "Episode", posterItemId = series), server)

    private fun movie(
        id: String,
        title: String,
        server: SavedServer = ONE,
    ) = HomeResumeEntry(media(id, title, type = "Movie", posterItemId = id), server)

    private fun media(
        id: String,
        title: String,
        type: String,
        posterItemId: String,
    ) = MediaItem(
        id = id,
        title = title,
        subtitle = null,
        type = type,
        posterItemId = posterItemId,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = null,
    )

    private companion object {
        val ONE = SavedServer("one", "http://one", "One", "u", "User", "token")
        val TWO = SavedServer("two", "http://two", "Two", "u", "User", "token")
    }
}
