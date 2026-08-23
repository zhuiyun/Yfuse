package com.yfuse.feature.player

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSeriesLaunchTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun series_is_resolved_to_episode_before_playback_info() =
        runBlocking {
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
                }
            val requestedPaths = mutableListOf<String>()
            val repo =
                testRepo { request ->
                    val path = request.url.encodedPath
                    requestedPaths += path
                    when {
                        path.endsWith("/Shows/NextUp") ->
                            json(
                                """{"Items":[{"Id":"e4","Name":"第四集","Type":"Episode",""" +
                                    """"SeriesId":"series","IndexNumber":4,"ParentIndexNumber":1,""" +
                                    """"UserData":{"PlaybackPositionTicks":33000000}}]}""",
                            )
                        path.endsWith("/Items/e4/PlaybackInfo") ->
                            json("""{"MediaSources":[],"PlaySessionId":"session-e4"}""")
                        path.contains("/Shows/series/Episodes") ->
                            json(
                                """{"Items":[{"Id":"e4","Name":"第四集","Type":"Episode",""" +
                                    """"SeriesId":"series","IndexNumber":4,"ParentIndexNumber":1,""" +
                                    """"UserData":{"PlaybackPositionTicks":33000000}}]}""",
                            )
                        path.endsWith("/Items/e4") ->
                            json(
                                """{"Id":"e4","Name":"第四集","Type":"Episode","SeriesId":"series",""" +
                                    """"SeriesName":"某剧","IndexNumber":4,"ParentIndexNumber":1,""" +
                                    """"UserData":{"PlaybackPositionTicks":33000000}}""",
                            )
                        path.endsWith("/Items/series") ->
                            json("""{"Id":"series","Name":"某剧","Type":"Series"}""")
                        else -> json("{}")
                    }
                }
            val store =
                PlayerStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    itemId = "series",
                    startPositionTicks = 0L,
                ).create()

            val state = store.states.first { !it.loading }

            assertEquals(listOf("e4"), state.items.map { it.id })
            assertEquals(3_300L, state.startPositionMs)
            assertTrue(requestedPaths.any { it.endsWith("/Items/e4/PlaybackInfo") })
            assertFalse(requestedPaths.any { it.endsWith("/Items/series/PlaybackInfo") })
            store.dispose()
        }
}
