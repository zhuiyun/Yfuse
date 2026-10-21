package com.yfuse.feature.stremio

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

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
                composition.setContent { list = rememberStremioListState(catalog.value, detail.value, video.value) }
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
                assertNotSame(detailList, list)
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
