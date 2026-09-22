from edit import read,write
C='composeApp/src/commonTest/kotlin/com/yfuse/'
write(C+'core/designsystem/ArtworkBlurCacheTest.kt','''package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtworkBlurCacheTest {
    @Test
    fun neighbouring_frames_share_effects_and_recently_used_entries_survive_eviction() {
        val cache = ArtworkBlurCache(capacity = 2)
        val first = cache.effect(1f)
        val second = cache.effect(2f)
        assertSame(first, cache.effect(1.1f))
        cache.effect(3f)
        assertSame(first, cache.effect(1f))
        assertNotSame(second, cache.effect(2f))
        assertEquals(2, cache.size)
    }

    @Test
    fun revealing_many_posters_never_grows_the_effect_cache_past_its_capacity() {
        val cache = ArtworkBlurCache()
        repeat(1_000) { index ->
            cache.effect(index * 0.5f)
            assertTrue(cache.size <= 64)
        }
        assertNull(cache.effect(0f))
        assertNull(cache.effect(Float.NaN))
        assertNull(cache.effect(Float.POSITIVE_INFINITY))
        assertEquals(64, cache.size)
    }

    @Test
    fun blur_quantization_is_monotonic_and_within_a_quarter_physical_pixel() {
        var last = 0
        repeat(10_001) { index ->
            val radius = index / 100f
            val step = artworkBlurStep(radius)
            assertTrue(step >= last)
            assertTrue(abs(step * ARTWORK_BLUR_STEP_PX - radius) <= 0.25001f)
            last = step
        }
    }
}
''')
write(C+'core/designsystem/ContentHandoffTest.kt','''package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentHandoffTest {
    @Test
    fun refresh_and_background_errors_keep_existing_content_visible() {
        for (loading in listOf(false, true)) for (error in listOf(false, true)) {
            assertEquals(ContentPhase.Content, contentPhase(loading, true, error))
        }
        assertEquals(ContentPhase.Loading, contentPhase(true, false, true))
        assertEquals(ContentPhase.Error, contentPhase(false, false, true))
        assertEquals(ContentPhase.Empty, contentPhase(false, false, false))
    }

    @Test
    fun dock_arrival_requires_an_explicit_click_and_can_only_be_consumed_once() {
        SearchDockOrigin.consume()
        val source = Rect(10f, 100f, 60f, 150f)
        SearchDockOrigin.bounds = source
        assertNull(SearchDockOrigin.consume())
        SearchDockOrigin.begin()
        SearchDockOrigin.bounds = Rect.Zero
        assertEquals(source, SearchDockOrigin.consume())
        assertNull(SearchDockOrigin.consume())
        SearchDockOrigin.bounds = null
    }
}
''')
# Actual animation-clock coverage of bounded prediction and immediate recalibration.
write(C+'feature/player/NextUpRingStateTest.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NextUpRingStateTest {
    @Test
    fun predictions_follow_speed_clamp_at_end_and_reject_invalid_speed() {
        assertEquals(9_000f, nextUpPredictedRemaining(10_000f, 2f))
        assertEquals(0f, nextUpPredictedRemaining(200f, 1f))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(9_500f, nextUpPredictedRemaining(10_000f, speed))
        }
    }

    @Test
    fun pause_seek_and_cancel_stop_an_inflight_prediction_and_replace_the_displayed_position() = runTest {
        val clock = BroadcastFrameClock()
        val ring = NextUpRingState(10_000L)
        var now = 0L
        fun frame() {
            runCurrent()
            now += 100_000_000L
            clock.sendFrame(now)
            runCurrent()
        }
        val playing = launch(clock) { ring.retarget(10_000L, true, 1f) }
        repeat(3) { frame() }
        assertTrue(ring.value.value in 9_500f..9_999f)
        playing.cancel()
        ring.retarget(9_800L, false, 1f)
        repeat(3) { frame() }
        assertEquals(9_800f, ring.value.value)
        assertFalse(clock.hasAwaiters)
        ring.retarget(2_000L, false, 1f)
        assertEquals(2_000f, ring.value.value)
        ring.retarget(12_000L, false, 1f)
        assertEquals(12_000f, ring.value.value)
        val restarted = launch(clock) { ring.retarget(12_000L, true, 2f) }
        repeat(9) { frame() }
        restarted.join()
        assertEquals(11_000f, ring.value.value)
        assertFalse(clock.hasAwaiters, "A missing engine sample must not leave a free-running countdown")
    }
}
''')
# Same real composition fixture as existing decorative tests, now checking item replacement and policies.
s=read(C+'core/designsystem/DecorativePhaseTest.kt')
fixture=s[s.index('    private class NoNodes'):s.rfind('}')]
write(C+'feature/player/NextUpRingCompositionTest.kt','''package com.yfuse.feature.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.yfuse.core.designsystem.AccessibilityOptions
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NextUpRingCompositionTest {
    @Test
    fun item_switch_discards_previous_ring_and_inactive_policies_remove_frame_requests() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        val key = mutableStateOf("first")
        val visible = mutableStateOf(true)
        val reduced = mutableStateOf(false)
        val advancing = mutableStateOf(true)
        lateinit var remaining: State<Float>
        var now = 0L
        fun frame() {
            Snapshot.sendApplyNotifications()
            runCurrent()
            now += 50_000_000L
            clock.sendFrame(now)
            runCurrent()
        }
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalRouteVisible provides visible.value,
                    LocalAccessibilityOptions provides AccessibilityOptions(reduceMotion = reduced.value),
                ) {
                    remaining = rememberNextUpRemaining(key.value, 8_000L, advancing.value, 1f)
                }
            }
            repeat(4) { frame() }
            val original = remaining
            key.value = "next"
            advancing.value = false
            repeat(3) { frame() }
            assertNotSame(original, remaining)
            assertEquals(8_000f, remaining.value)
            assertFalse(clock.hasAwaiters)
            for (gate in listOf(reduced, visible, advancing)) {
                advancing.value = true
                visible.value = true
                reduced.value = false
                repeat(3) { frame() }
                assertTrue(clock.hasAwaiters)
                gate.value = gate === reduced
                repeat(3) { frame() }
                assertEquals(8_000f, remaining.value)
                assertFalse(clock.hasAwaiters)
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

'''+fixture+'}\n')
