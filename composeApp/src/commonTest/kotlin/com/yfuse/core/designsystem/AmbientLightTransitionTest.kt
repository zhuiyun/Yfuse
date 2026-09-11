package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientLightTransitionTest {
    private val warm = AmbientLight.uniform(Color(0xFF8A4A20))
    private val cool = AmbientLight.uniform(Color(0xFF203A6A))

    @Test
    fun transition_is_paced_and_reaches_the_exact_target() = runTest {
        val frames = mutableListOf<Pair<Long, AmbientLight>>()
        runAmbientLightTransition(warm, cool, 600L, { testScheduler.currentTime }) {
            frames += testScheduler.currentTime to it
        }
        assertEquals(warm, frames.first().second)
        assertEquals(cool, frames.last().second)
        assertEquals(600L, frames.last().first)
        assertTrue(frames.size <= 20, "600 ms must not subscribe to 36 or 72 display frames")
        frames.dropLast(1).zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 34L) }
    }

    @Test
    fun cancelling_visibility_stops_all_future_publications() = runTest {
        var frames = 0
        val job = launch {
            runAmbientLightTransition(warm, cool, 600L, { testScheduler.currentTime }) { frames++ }
        }
        delay(170L)
        job.cancelAndJoin()
        val before = frames
        delay(1_000L)
        assertEquals(before, frames)
    }

    @Test
    fun zero_duration_publishes_only_the_target_without_starting_a_clock() = runTest {
        val frames = mutableListOf<AmbientLight>()
        runAmbientLightTransition(warm, cool, 0L, { error("No clock when animations are disabled") }) { frames += it }
        assertEquals(listOf(cool), frames)
    }

    @Test
    fun dark_frame_fades_the_precomputed_accent_back_to_the_artwork() {
        val fallback = Color(0xFF628AD1)
        val middle = lerpAmbientLight(warm, AmbientLight.Off, 0.5f)
        assertEquals(warm.accent, ambientSeekAccent(warm, fallback))
        assertEquals(0.5f, middle.accentWeight)
        assertEquals(fallback, ambientSeekAccent(AmbientLight.Off, fallback))
        assertEquals(fallback, ambientSeekAccent(null, fallback))
        assertEquals(interpolateArtworkPageColor(warm.accent, AmbientLight.Off.accent, 0.5f), middle.accent)
    }
}
