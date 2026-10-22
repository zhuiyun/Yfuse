package com.yfuse.feature.stremio

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.stremio.StremioClient
import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.core.stremio.StremioSource
import com.yfuse.core.stremio.StremioStream
import com.yfuse.core.stremio.StremioVideo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StremioListStateTest {
    @Test
    fun navigatingBackRestoresEachParentAndNewBranchesStartAtTheTop() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val catalog = mutableStateOf<String?>(null)
            val detail = mutableStateOf<Pair<String, String>?>(null)
            val video = mutableStateOf<String?>(null)
            lateinit var list: LazyListState
            var time = 0L

            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++time * 16_000_000)
                runCurrent()
            }
            try {
                composition.setContent {
                    // Episode/source changes recompose detail but do not introduce a new page.
                    video.value
                    list = rememberStremioListState(catalog.value, detail.value)
                }
                frame()
                val home = list
                home.requestScrollToItem(7, 19)
                catalog.value = "catalog"
                frame()
                val catalogList = list
                catalogList.requestScrollToItem(25, 38)
                detail.value = "series" to "title"
                frame()
                val detailList = list
                detailList.requestScrollToItem(12, 51)
                video.value = "episode-2"
                frame()
                assertSame(detailList, list)
                assertEquals(12, list.firstVisibleItemIndex)
                video.value = "episode-3"
                frame()
                assertSame(detailList, list)
                video.value = null
                frame()
                assertSame(detailList, list)
                assertEquals(12, list.firstVisibleItemIndex)
                assertEquals(51, list.firstVisibleItemScrollOffset)
                detail.value = null
                frame()
                assertSame(catalogList, list)
                assertEquals(25, list.firstVisibleItemIndex)
                assertEquals(38, list.firstVisibleItemScrollOffset)
                detail.value = "movie" to "title"
                frame()
                assertNotSame(detailList, list)
                assertEquals(0, list.firstVisibleItemIndex)
                detail.value = null
                catalog.value = null
                frame()
                assertSame(home, list)
                assertEquals(7, list.firstVisibleItemIndex)
                catalog.value = "other-catalog"
                frame()
                assertNotSame(catalogList, list)
                assertEquals(0, list.firstVisibleItemIndex)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.join()
            }
        }

    @Test
    fun inlineSourceKeysBelongToTheirEpisodeAndCannotMoveTheScrollAnchorToAnotherEpisode() =
        runTest {
            HttpClient(MockEngine { error("Recording list keys must not issue network requests") }).use { http ->
                val controller =
                    StremioController(StremioRepository(StremioClient(http), TestSecureStore(), "adult"), this)
                val episodes = listOf(StremioVideo("episode-one", "One"), StremioVideo("episode-two", "Two"))
                val state = StremioScreenState(detail = StremioMeta("show", "series", "Show", videos = episodes))

                fun keys(value: StremioScreenState): List<Any> =
                    KeyRecordingScope().apply { stremioDetail(value, controller) }.keys
                val detailKeys = keys(state)
                // Exercise the empty state and real source rows: all moving items, including
                // the source heading, must disappear instead of retaining an anchor across episodes.
                listOf(emptyList(), listOf(StremioSource("Plugin", StremioStream(url = "https://cdn.example/video"))))
                    .forEach { sources ->
                        val firstState = state.copy(video = episodes[0], sources = sources)
                        val secondState = state.copy(video = episodes[1], sources = sources)
                        val firstKeys = keys(firstState)
                        val secondKeys = keys(secondState)
                        val firstSources = firstKeys.filterNot(detailKeys::contains)
                        val secondSources = secondKeys.filterNot(detailKeys::contains)
                        assertEquals(2, firstSources.size)
                        assertEquals(2, secondSources.size)
                        assertTrue(firstSources.toSet().intersect(secondSources.toSet()).isEmpty())
                        assertEquals(detailKeys, firstKeys.filter(detailKeys::contains))
                        assertEquals(detailKeys, secondKeys.filter(detailKeys::contains))
                        assertEquals(secondKeys.size, secondKeys.distinct().size)
                        val selectedIndex = secondKeys.indexOf("video-${episodes[1].id}")
                        assertEquals(
                            secondSources,
                            secondKeys.subList(
                                selectedIndex + 1,
                                selectedIndex + 1 + secondSources.size,
                            ),
                        )
                        assertEquals(secondKeys, keys(secondState))
                    }
            }
        }

    /** Capture the real lazy-list DSL without mounting media artwork or network-backed rows. */
    private class KeyRecordingScope : LazyListScope {
        val keys = mutableListOf<Any>()

        override fun item(
            key: Any?,
            contentType: Any?,
            content: @Composable LazyItemScope.() -> Unit,
        ) {
            keys += checkNotNull(key)
        }

        override fun items(
            count: Int,
            key: ((Int) -> Any)?,
            contentType: (Int) -> Any?,
            itemContent: @Composable LazyItemScope.(Int) -> Unit,
        ) {
            repeat(count) { keys += checkNotNull(key).invoke(it) }
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }
}
