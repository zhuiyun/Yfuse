package com.yfuse.feature.player

import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerStoreTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun episode_loads_series_queue_and_resume_position() = runTest {
        val registry = testRegistry().apply {
            addOrUpdate(SavedServer("id", "http://host:8096", "server", "u1", "user", "tok"))
        }
        val repo = testRepo { request ->
            if (request.url.encodedPath.contains("/Shows/s1/Episodes")) {
                json(
                    """{"Items":[{"Id":"e1","Name":"开场","Type":"Episode","IndexNumber":1,"ParentIndexNumber":2},""" +
                        """{"Id":"e2","Name":"转折","Type":"Episode","IndexNumber":2,"ParentIndexNumber":2}]}""",
                )
            } else {
                json("""{"Id":"e2","Name":"转折","Type":"Episode","SeriesId":"s1","SeriesName":"某剧"}""")
            }
        }
        val store = PlayerStoreFactory(
            DefaultStoreFactory(),
            repo,
            registry,
            itemId = "e2",
            startPositionTicks = 25_000_000L,
        ).create()

        val state = store.states.first { !it.loading }

        assertEquals(listOf("e1", "e2"), state.items.map { it.id })
        assertEquals(listOf(2, 2), state.items.map { it.seasonNumber })
        assertEquals(listOf(1, 2), state.items.map { it.episodeNumber })
        assertEquals(1, state.startIndex)
        assertEquals(2_500L, state.startPositionMs)
        store.dispose()
    }
}
