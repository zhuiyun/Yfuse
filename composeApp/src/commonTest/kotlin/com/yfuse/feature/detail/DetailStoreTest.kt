package com.yfuse.feature.detail

import app.cash.turbine.test
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailStoreTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        startKoin { modules(module { single { PlaybackTrackRequest() } }) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun version_selects_first_and_selected_version_plays() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

            store.labels.test {
                store.accept(DetailIntent.SelectVersion("v2"))
                assertEquals("v2", store.state.selectedVersionId)
                expectNoEvents()

                store.accept(DetailIntent.SelectVersion("v2"))
                assertEquals(
                    DetailLabel.Play("one", "m1", 40_000_000L, "v2"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    @Test
    fun resource_selection_updates_main_play_target_before_playing() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" && it.sources.size == 2 }

            store.labels.test {
                store.accept(DetailIntent.SelectSource("two", "m2"))
                store.states.first { !it.selectionLoading && it.playTarget?.id == "m2" }

                assertEquals("two", store.state.playServer?.id)
                assertEquals("w1", store.state.selectedVersionId)
                assertEquals(90_000_000L, store.state.playPositionTicks)
                expectNoEvents()

                store.accept(DetailIntent.SelectSource("two", "m2"))
                assertEquals(
                    DetailLabel.Play("two", "m2", 90_000_000L, "w1"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    @Test
    fun changing_version_clears_track_choice_missing_from_new_file() {
        runTest {
            val store = movieStore()
            store.states.first { it.playTarget?.id == "m1" }

            store.accept(DetailIntent.SelectAudioLanguage("英语"))
            store.accept(DetailIntent.SelectVersion("v2"))

            assertEquals("v2", store.state.selectedVersionId)
            assertNull(store.state.preferredAudioLanguage)
            store.dispose()
        }
    }

    @Test
    fun episode_selects_first_and_selected_episode_plays_its_version() {
        runTest {
            val registry = testRegistry().apply {
                addOrUpdate(SavedServer("one", "http://one", "主库", "u", "user", "tok1"))
            }
            val repo = testRepo { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/Items/s1") -> json(SERIES)
                    path.endsWith("/Items/e1") -> json(EPISODE_ONE)
                    path.endsWith("/Items/e2") -> json(EPISODE_TWO)
                    path.endsWith("/Shows/NextUp") -> json(
                        """{"Items":[{"Id":"e1","Name":"第一集","Type":"Episode",""" +
                            """"UserData":{"PlaybackPositionTicks":10000000}}]}""",
                    )
                    path.endsWith("/Shows/s1/Seasons") -> json(
                        """{"Items":[{"Id":"season1","Name":"第 1 季","IndexNumber":1}]}""",
                    )
                    path.endsWith("/Shows/s1/Episodes") -> json(
                        """{"Items":[$EPISODE_ONE,$EPISODE_TWO]}""",
                    )
                    path.endsWith("/Similar") -> json("""{"Items":[]}""")
                    path.endsWith("/Items") -> json("""{"Items":[$SERIES]}""")
                    else -> json("{}")
                }
            }
            val store = DetailStoreFactory(
                DefaultStoreFactory(),
                repo,
                registry,
                itemId = "s1",
                serverId = "one",
            ).create()
            store.states.first { it.playTarget?.id == "e1" && it.episodes.size == 2 }

            store.labels.test {
                store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
                store.states.first { !it.selectionLoading && it.playTarget?.id == "e2" }

                assertEquals("ev2", store.state.selectedVersionId)
                assertEquals(20_000_000L, store.state.playPositionTicks)
                expectNoEvents()

                store.accept(DetailIntent.SelectEpisode("e2", 20_000_000L))
                assertEquals(
                    DetailLabel.Play("one", "e2", 20_000_000L, "ev2"),
                    awaitItem(),
                )
                cancelAndConsumeRemainingEvents()
            }
            store.dispose()
        }
    }

    private fun movieStore(): com.arkivanov.mvikotlin.core.store.Store<
        DetailIntent,
        DetailState,
        DetailLabel,
    > {
        val registry = testRegistry().apply {
            addOrUpdate(SavedServer("one", "http://one", "主库", "u", "user", "tok1"))
            addOrUpdate(SavedServer("two", "http://two", "备库", "u", "user", "tok2"))
        }
        val repo = testRepo { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            when {
                path.endsWith("/Items/m1") -> json(MOVIE_ONE)
                path.endsWith("/Items/m2") -> json(MOVIE_TWO)
                path.endsWith("/Similar") -> json("""{"Items":[]}""")
                path.endsWith("/Items") -> json(
                    if (host == "one") {
                        """{"Items":[$MOVIE_ONE]}"""
                    } else {
                        """{"Items":[$MOVIE_TWO]}"""
                    },
                )
                else -> json("{}")
            }
        }
        return DetailStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry,
            itemId = "m1",
            serverId = "one",
        ).create()
    }

    private companion object {
        const val MOVIE_ONE = """{"Id":"m1","Name":"电影","Type":"Movie",""" +
            """"UserData":{"PlaybackPositionTicks":40000000},"MediaSources":[""" +
            """{"Id":"v1","Name":"原盘","MediaStreams":[""" +
            """{"Type":"Video","Height":2160},{"Type":"Audio","Language":"eng"}]},""" +
            """{"Id":"v2","Name":"压制","MediaStreams":[""" +
            """{"Type":"Video","Height":1080},{"Type":"Audio","Language":"chi"}]}]}"""

        const val MOVIE_TWO = """{"Id":"m2","Name":"电影","Type":"Movie",""" +
            """"UserData":{"PlaybackPositionTicks":90000000},"MediaSources":[""" +
            """{"Id":"w1","Name":"备库版本","MediaStreams":[""" +
            """{"Type":"Video","Height":720},{"Type":"Audio","Language":"chi"}]}]}"""

        const val SERIES =
            """{"Id":"s1","Name":"剧集","Type":"Series","ProviderIds":{"Tmdb":"1"}}"""

        const val EPISODE_ONE = """{"Id":"e1","Name":"第一集","Type":"Episode",""" +
            """"SeriesId":"s1","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":1,"SeasonId":"season1",""" +
            """"UserData":{"PlaybackPositionTicks":10000000},"MediaSources":[""" +
            """{"Id":"ev1","Name":"第一集版本","MediaStreams":[""" +
            """{"Type":"Video","Height":1080}]}]}"""

        const val EPISODE_TWO = """{"Id":"e2","Name":"第二集","Type":"Episode",""" +
            """"SeriesId":"s1","SeriesName":"剧集","ParentIndexNumber":1,""" +
            """"IndexNumber":2,"SeasonId":"season1",""" +
            """"UserData":{"PlaybackPositionTicks":20000000},"MediaSources":[""" +
            """{"Id":"ev2","Name":"第二集版本","MediaStreams":[""" +
            """{"Type":"Video","Height":2160}]}]}"""
    }
}
