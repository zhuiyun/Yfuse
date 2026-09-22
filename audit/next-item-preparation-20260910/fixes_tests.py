from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
C='composeApp/src/commonMain/kotlin/com/yfuse/'
P=A+'AndroidAdaptiveCore2YPlayer.kt'
replace(P,'    private val nextPreparationBoundary = AtomicReference<NextItemPreparationBoundary?>(null)', '    private val nextPreparationBoundary = AtomicReference<NextItemPreparationBoundary?>(null)\n    private val nextPreparationRevision = AtomicLong()')
replace(P,'        if (nextPreparationBoundary.getAndSet(next) != next) commands.trySend(Command.PreparationBoundaryChanged)', '''        if (nextPreparationBoundary.getAndSet(next) != next) {
            nextPreparationRevision.incrementAndGet()
            commands.trySend(Command.PreparationBoundaryChanged)
        }''')
replace(P,'        fun discardNextPreparation() {\n', '        fun discardNextPreparation() {\n            nextPreparationRevision.incrementAndGet()\n')
replace(P,'            nextItemPreloadJob =\n                scope.launch(Dispatchers.IO) {','            val preparationRevision = nextPreparationRevision.get()\n            nextItemPreloadJob =\n                scope.launch(Dispatchers.IO) {')
replace(P,'                            activeChild === preloadChild &&\n', '                            activeChild === preloadChild &&\n                            nextPreparationRevision.get() == preparationRevision &&\n')
replace(P,'                    fun healthy(): Boolean = allowed() && snapshot()?.let(::nextItemPlaybackHealthy) == true', '''                    fun healthy(): Boolean =
                        allowed() && snapshot()?.let(::nextItemPlaybackHealthy) == true''')
replace(P,'Command.NextItemPreloaded(preloadChild, route)', 'Command.NextItemPreloaded(preloadChild, route, preparationRevision)')
replace(P,'                            if (child !== command.fromChild ||', '                            if (command.revision != nextPreparationRevision.get() ||\n                                child !== command.fromChild ||')
replace(P,'            val route: PreloadedNextRoute,\n', '            val route: PreloadedNextRoute,\n            val revision: Long,\n')
replace(A+'AndroidNextItemPreparation.kt','                    if (!allowed()) budget.cancel("next_item_yield")', '''                    AndroidPlaybackMemoryBudget.refreshPressure()
                    if (!allowed()) budget.cancel("next_item_yield")''')

write('composeApp/src/commonTest/kotlin/com/yfuse/feature/player/NextItemCreditsBoundaryTest.kt','''package com.yfuse.feature.player

import com.yfuse.core.data.SkipMode
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextItemCreditsBoundaryTest {
    private val segments = listOf(PlaybackSegment(PlaybackSegmentType.Intro, 0, 60_000),
        PlaybackSegment(PlaybackSegmentType.Credits, 1_200_000, null))
    @Test fun credits_button_and_auto_skip_prepare_before_credits_instead_of_file_end() {
        for (mode in listOf(SkipMode.Button, SkipMode.Auto)) {
            assertEquals(1_200_000L, nextItemCreditsBoundary(segments, 1_320_000, mode, false, false))
        }
    }
    @Test fun disabling_or_cancelling_skip_and_guest_authority_restore_natural_end() {
        assertNull(nextItemCreditsBoundary(segments, 1_320_000, SkipMode.Off, false, false))
        assertNull(nextItemCreditsBoundary(segments, 1_320_000, SkipMode.Auto, true, false))
        assertNull(nextItemCreditsBoundary(segments, 1_320_000, SkipMode.Auto, false, true))
    }
    @Test fun invalid_markers_and_intro_never_advance_the_next_item_boundary() {
        assertNull(nextItemCreditsBoundary(segments, 1_000_000, SkipMode.Button, false, false))
        assertNull(nextItemCreditsBoundary(segments.take(1), 1_320_000, SkipMode.Auto, false, false))
        assertEquals(1_200_000L, nextItemCreditsBoundary(segments, 0, SkipMode.Auto, false, false))
    }
}
''')
write('composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidNextItemPreparationTest.kt','''package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNextItemPreparationTest {
    private val healthy = YPlayerState(phase = YPlaybackPhase.Ready, playing = true,
        durationMs = 1_320_000, positionMs = 1_150_000, bufferedPositionMs = 1_250_000)
    @Test fun boundary_uses_credits_and_playback_speed_and_handles_unknown_duration() {
        assertEquals(170_000L, nextItemRemainingMs(healthy, null))
        assertEquals(50_000L, nextItemRemainingMs(healthy, 1_200_000))
        assertEquals(25_000L, nextItemRemainingMs(healthy.copy(speed = 2f), 1_200_000))
        assertEquals(0L, nextItemRemainingMs(healthy.copy(positionMs = 1_220_000), 1_200_000))
        assertEquals(50_000L, nextItemRemainingMs(healthy.copy(durationMs = 0), 1_200_000))
        assertNull(nextItemRemainingMs(healthy.copy(durationMs = 0), null))
        assertNull(nextItemRemainingMs(healthy.copy(speed = Float.NaN), null))
    }
    @Test fun prepared_source_window_tracks_changed_markers_and_does_not_start_while_paused() = runTest {
        var state = healthy.copy(playing = false)
        var boundary: Long? = 1_160_000
        val ready = async { awaitNextItemBoundary(20_000, { boundary }, { state }, { true }) }
        advanceTimeBy(30_000); runCurrent(); assertFalse(ready.isCompleted)
        boundary = null
        state = healthy
        advanceTimeBy(30_000); runCurrent(); assertFalse(ready.isCompleted)
        boundary = 1_160_000
        advanceTimeBy(1_000); runCurrent(); assertTrue(ready.await())
    }
    @Test fun pressure_stalls_and_replaced_children_never_gain_permission_by_timeout() = runTest {
        var state: YPlayerState? = healthy
        val ready = async { awaitNextItemBoundary(90_000, { 1_200_000 }, { state }, { false }) }
        advanceTimeBy(120_000); runCurrent(); assertFalse(ready.isCompleted)
        state = null
        advanceTimeBy(250); runCurrent(); assertFalse(ready.await())
        assertFalse(nextItemPlaybackHealthy(healthy.copy(buffering = true)))
        assertFalse(nextItemPlaybackHealthy(healthy.copy(bufferedPositionMs = healthy.positionMs + 1_000)))
    }
    @Test fun byte_budget_is_bounded_even_for_overflowing_or_missing_bitrate() {
        assertEquals(4L * 1024 * 1024, nextItemPreloadBytes(0))
        assertEquals(2L * 1024 * 1024, nextItemPreloadBytes(1))
        assertEquals(12L * 1024 * 1024, nextItemPreloadBytes(Long.MAX_VALUE))
    }
}
''')
