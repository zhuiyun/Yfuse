package com.yfuse.feature.detail

import androidx.compose.runtime.mutableStateOf
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastMediaProfile
import com.yfuse.core.cast.CastQueueEntry
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.CastTrackKind
import com.yfuse.feature.player.PlaybackPreloadKey
import com.yfuse.feature.player.PlayerIntent
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerState
import com.yfuse.feature.player.PreparedPlayerStore
import com.yfuse.feature.player.QuickCastKind
import com.yfuse.feature.player.RecentCastTarget
import com.yfuse.feature.player.RecentCastTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailCastTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeCast(
        devices: List<CastDevice> = listOf(LIVING_ROOM),
    ) : CastManager {
        override val state = MutableStateFlow(CastState(devices = devices))

        override suspend fun discover() = Unit

        override suspend fun play(
            deviceId: String,
            mediaUrl: String,
            title: String,
            positionMs: Long,
            fallbackMediaUrl: String?,
            mediaProfile: CastMediaProfile,
            queue: List<CastQueueEntry>,
            queueIndex: Int,
        ) = true

        override suspend fun resume() = true

        override suspend fun pause() = true

        override suspend fun seekTo(positionMs: Long) = true

        override suspend fun setVolume(volume: Float) = true

        override suspend fun selectTrack(
            kind: CastTrackKind,
            language: String?,
            label: String,
            enabled: Boolean,
        ) = true

        override suspend fun queueNext() = true

        override suspend fun queuePrevious() = true

        override suspend fun stop() = true
    }

    /** A queue that is already what it will be: the player's Store after it has loaded. */
    private fun queue(state: PlayerState): PreparedPlayerStore =
        DefaultStoreFactory().create<PlayerIntent, Nothing, Nothing, PlayerState, Nothing>(
            name = "CastQueue",
            initialState = state,
            executorFactory = { object : CoroutineExecutor<PlayerIntent, Nothing, PlayerState, Nothing, Nothing>() {} },
        )

    private val episodes =
        List(3) { index ->
            PlayerMediaItem(id = "e$index", url = "https://tv/e$index", transcodeUrl = "", title = "e$index")
        }

    private fun TestScope.launcher(
        cast: CastManager?,
        store: PreparedPlayerStore,
        messages: MutableList<String>,
        load: suspend (CastManager, List<PlayerMediaItem>, String, Int, Long) -> Boolean,
    ) = DetailCastLauncher(
        scope = this,
        castManager = { cast },
        recentTargets = { RecentCastTargets(MapSettings()) },
        queue = { store },
        report = { messages += it },
        load = load,
        scanWithoutAsking = { false },
    )

    @Test
    fun whatPlayWouldOpenGoesToTheTelevisionFromItsResumePointAndIsRemembered() =
        runTest {
            val ready = PlayerState(loading = false, items = episodes, startIndex = 1, startPositionMs = 754_000L)
            val store = queue(ready)
            val loads = mutableListOf<String>()
            val messages = mutableListOf<String>()
            val launcher =
                launcher(FakeCast(), store, messages) { _, items, device, index, position ->
                    loads += "$device ${items.size} $index $position"
                    true
                }
            val outcomes = mutableListOf<DetailCastOutcome>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { launcher.outcomes.collect(outcomes::add) }

            launcher.launch(PICK, KEY)
            assertEquals(PICK, launcher.preparing.value)
            advanceUntilIdle()

            // The whole queue goes, so a receiver that keeps one plays the next episode on.
            assertEquals(listOf("dlna:living 3 1 754000"), loads)
            assertEquals(listOf("已在「客厅电视」开始播放"), messages)
            assertEquals(listOf(DetailCastOutcome(PICK, failure = null)), outcomes)
            assertNull(launcher.preparing.value)
            assertTrue(store.isDisposed)
            assertEquals(listOf(RecentCastTarget(QuickCastKind.Dlna, "dlna:living", "客厅电视")), launcher.recent.value)
        }

    @Test
    fun aReceiverThatRefusesSaysWhyAndIsNotRemembered() =
        runTest {
            val cast = FakeCast()
            cast.state.value = cast.state.value.copy(error = "投屏设备没有开始播放，请确认设备在线后重试，或换一台设备")
            val store = queue(PlayerState(loading = false, items = episodes))
            val messages = mutableListOf<String>()
            val launcher = launcher(cast, store, messages) { _, _, _, _, _ -> false }

            launcher.launch(PICK, KEY)
            advanceUntilIdle()

            assertEquals(listOf("投屏设备没有开始播放，请确认设备在线后重试，或换一台设备"), messages)
            assertEquals(emptyList(), launcher.recent.value)
            assertTrue(store.isDisposed)
        }

    @Test
    fun aQueueThatCannotBeBuiltIsReportedInThePlayersWordsAndNothingIsCast() =
        runTest {
            val store = queue(PlayerState(loading = false, error = "播放准备超时，请检查服务器连接后重试"))
            val messages = mutableListOf<String>()
            var loaded = false
            val launcher =
                launcher(FakeCast(), store, messages) { _, _, _, _, _ ->
                    loaded = true
                    true
                }

            launcher.launch(PICK, KEY)
            advanceUntilIdle()

            assertFalse(loaded)
            assertEquals(listOf("播放准备超时，请检查服务器连接后重试"), messages)
            assertTrue(store.isDisposed)
        }

    @Test
    fun aRememberedDeviceThatDoesNotTurnUpIsNamedAndLeftAlone() =
        runTest {
            val store = queue(PlayerState(loading = false, items = episodes))
            val messages = mutableListOf<String>()
            var loaded = false
            val launcher =
                launcher(FakeCast(devices = emptyList()), store, messages) { _, _, _, _, _ ->
                    loaded = true
                    true
                }

            launcher.launch(DetailCastRequest("dlna:study", "书房电视"), KEY)
            advanceTimeBy(7_000L)
            runCurrent()
            assertTrue(messages.isEmpty(), "still looking for it")
            advanceUntilIdle()

            assertFalse(loaded)
            assertEquals(listOf("没有找到「书房电视」"), messages)
        }

    @Test
    fun theLaterEpisodesAreWaitedForOnlySoLong() =
        runTest {
            // Queue enrichment that never finishes: the episode already known goes by itself.
            val store = queue(PlayerState(loading = false, items = episodes.take(1), enrichmentPending = true))
            val loads = mutableListOf<Int>()
            val launcher =
                launcher(FakeCast(), store, mutableListOf()) { _, items, _, _, _ ->
                    loads += items.size
                    true
                }

            launcher.launch(PICK, KEY)
            advanceTimeBy(3_000L)
            runCurrent()
            assertTrue(loads.isEmpty())
            advanceUntilIdle()

            assertEquals(listOf(1), loads)
        }

    @Test
    fun theKeyStandsOnlyWithSomethingToCastTo() =
        runTest {
            val summary = mutableStateOf(DetailCastSummary())
            val recent = mutableStateOf(emptyList<RecentCastTarget>())
            val preparing = mutableStateOf<DetailCastRequest?>(null)
            val launcher = launcher(null, queue(PlayerState()), mutableListOf()) { _, _, _, _, _ -> false }
            val cast = DetailCast(launcher, summary, recent, preparing, scan = {})

            assertFalse(cast.available)
            assertNull(cast.stateLabel)

            // Cast to before: offered, and looked for when picked.
            recent.value = listOf(RecentCastTarget(QuickCastKind.Dlna, "dlna:living", "客厅电视"))
            assertTrue(cast.available)
            assertEquals("上次用过 · 暂未找到", castTargetCaption(cast.targets.single(), searching = false))
            assertEquals("上次用过 · 正在查找", castTargetCaption(cast.targets.single(), searching = true))

            // Found by a scan — and a Chromecast after it, remembered devices first.
            val bedroom = CastDevice("chromecast:1", "卧室 · Chromecast")
            summary.value = DetailCastSummary(devices = listOf(LIVING_ROOM, bedroom))
            assertEquals(listOf("客厅电视", "卧室"), cast.targets.map { it.name })
            assertEquals(listOf("DLNA", "Chromecast"), cast.targets.map { castTargetCaption(it, searching = false) })

            // Live: lit, and it says where.
            summary.value =
                summary.value.copy(activeDeviceId = "dlna:living", activeDeviceName = "客厅电视", casting = true)
            assertEquals("正在投屏到 客厅电视", cast.stateLabel)
            assertEquals("正在投屏 · DLNA", castTargetCaption(cast.targets.first(), searching = false))
            preparing.value = PICK
            assertTrue(cast.busy)
            assertEquals("正在准备投屏", cast.stateLabel)

            // Nothing remembered, nothing found, nothing live: gone again.
            summary.value = DetailCastSummary()
            recent.value = emptyList()
            preparing.value = null
            assertFalse(cast.available)
        }

    @Test
    fun aCastWaitingOnItsPlayGivesWayToAnyTapThatPlaysHereOrChangesWhatPlays() {
        assertTrue(DetailIntent.Play.supersedesCast())
        assertTrue(DetailIntent.PlayFromStart.supersedesCast())
        assertTrue(DetailIntent.SelectEpisode("e1", 0L).supersedesCast())
        assertTrue(DetailIntent.SelectSource("s", "i").supersedesCast())
        assertTrue(DetailIntent.SelectVersion("v").supersedesCast())
        // Browsing seasons leaves 播放's target alone.
        assertFalse(DetailIntent.SelectSeason("s2").supersedesCast())
        assertFalse(DetailIntent.ToggleFavorite.supersedesCast())
        assertFalse(DetailIntent.ShowMessage("已在「客厅电视」开始播放").supersedesCast())
        assertFalse(DetailIntent.SelectAudioLanguage("zh").supersedesCast())
    }

    @Test
    fun theListSaysWhatGoesToTheTelevision() {
        assertEquals(
            "继续播放 · 第 2 季 · 第 3 集 · 45分钟 · 12:34",
            detailCastSubtitle(resumeTicks = 7_540_000_000L, detailLine = "第 2 季 · 第 3 集 · 45分钟"),
        )
        assertEquals("播放", detailCastSubtitle(resumeTicks = 0L, detailLine = null))
    }

    private companion object {
        val LIVING_ROOM = CastDevice("dlna:living", "客厅电视")
        val PICK = DetailCastRequest("dlna:living", "客厅电视")
        val KEY = PlaybackPreloadKey("s", "e1", startPositionTicks = 7_540_000_000L, mediaSourceId = null)
    }
}
