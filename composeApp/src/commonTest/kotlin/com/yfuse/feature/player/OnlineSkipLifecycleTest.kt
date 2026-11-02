package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.TheOtherDbRepository
import com.yfuse.core.network.TheOtherDbEpisode
import com.yfuse.core.network.TheOtherDbMarker
import com.yfuse.core.network.TheOtherDbSeason
import com.yfuse.core.network.TheOtherDbVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineSkipLifecycleTest {
    @Test
    fun episode_switch_cancels_old_response_and_source_change_rechecks_runtime() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val oldResponse = CompletableDeferred<Unit>()
            var cancelled = false
            var calls = 0
            val repository =
                TheOtherDbRepository(
                    MapSettings(),
                    fetch = { query ->
                        calls++
                        val episode = requireNotNull(query.episode)
                        if (query.episode == 1) {
                            try {
                                oldResponse.await()
                            } finally {
                                cancelled = true
                            }
                        }
                        listOf(
                            TheOtherDbVersion(
                                id = episode.toLong(),
                                runtime = 1300.0,
                                videoType = "tv",
                                season = TheOtherDbSeason(1),
                                episode = TheOtherDbEpisode(episode),
                                markers =
                                    listOf(
                                        TheOtherDbMarker(
                                            "intro",
                                            0.0,
                                            if (query.episode ==
                                                1
                                            ) {
                                                27.0
                                            } else {
                                                90.0
                                            },
                                        ),
                                    ),
                            ),
                        )
                    },
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            val item =
                mutableStateOf(
                    PlayerMediaItem(
                        "one",
                        "url1",
                        "url1",
                        "剧",
                        seriesKey = "tmdb:226045",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    ),
                )
            val duration = mutableStateOf(1_300_000L)
            val enabled = mutableStateOf(true)
            lateinit var markers: OnlineSkipMarkers
            var now = 0L

            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++now * 16_000_000L)
                runCurrent()
            }
            try {
                composition.setContent {
                    markers =
                        rememberOnlineSkipMarkers(item.value, duration.value, enabled.value, repository)
                }
                repeat(3) { frame() }
                assertTrue(markers.segments.isEmpty())
                item.value = item.value.copy(id = "two", url = "url2", episodeNumber = 2)
                repeat(5) { frame() }
                assertTrue(cancelled)
                assertEquals(90_000L, markers.segments.single().endMs)
                oldResponse.complete(Unit)
                repeat(2) { frame() }
                assertEquals(90_000L, markers.segments.single().endMs)
                item.value = item.value.copy(url = "other-cut", versionId = "other")
                duration.value = 1_400_000L
                repeat(5) { frame() }
                assertTrue(markers.segments.isEmpty())
                assertEquals(2, calls, "A source change reuses raw metadata but matches its own duration")
                enabled.value = false
                duration.value = 1_300_000L
                repeat(3) { frame() }
                assertTrue(markers.segments.isEmpty())
                assertEquals(2, calls)
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
