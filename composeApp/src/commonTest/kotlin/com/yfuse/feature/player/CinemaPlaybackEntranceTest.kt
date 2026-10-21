package com.yfuse.feature.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinemaPlaybackEntranceTest {
    @Test
    fun slow_network_stops_the_camera_without_uncovering_an_unverified_frame() {
        val entrance = CinemaPlaybackEntrance(eligible = true)
        entrance.update(awaitingPicture = true, pictureReady = false, allowed = true)
        entrance.advance(10_000f)
        assertFalse(entrance.needsFrames)
        assertEquals(0f, entrance.revealProgress)
        assertEquals(1.14f, entrance.scale, 0.0001f)
        repeat(100) { entrance.advance(1000f) }
        assertEquals(0f, entrance.revealProgress)
        assertEquals(1.14f, entrance.scale, 0.0001f)
    }

    @Test
    fun fast_first_frame_reveals_immediately_without_waiting_for_the_push_to_finish() {
        val entrance = CinemaPlaybackEntrance(eligible = true)
        entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
        entrance.advance(16f)
        assertTrue(entrance.revealProgress > 0f)
        entrance.advance(CinemaPlaybackEntrance.REVEAL_MS.toFloat())
        assertEquals(1f, entrance.revealProgress)
        assertFalse(entrance.needsFrames)
    }

    @Test
    fun readiness_keeps_camera_position_and_continues_forward_to_the_movie() {
        for (readyAfter in listOf(0f, 80f, 360f, 720f, 3000f)) {
            val entrance = CinemaPlaybackEntrance(eligible = true)
            entrance.advance(readyAfter)
            var previous = entrance.scale
            entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
            assertEquals(previous, entrance.scale)
            repeat(36) {
                entrance.advance(10f)
                assertTrue(entrance.scale >= previous, "The camera moved backwards at $readyAfter ms")
                previous = entrance.scale
                assertTrue(entrance.artworkDim in 0f..0.34f)
            }
            assertEquals(1.24f, entrance.scale, 0.0001f)
            assertEquals(1f, entrance.revealProgress)
        }
    }

    @Test
    fun retry_during_or_after_reveal_restores_an_opaque_cover_without_replaying_entry() {
        for (revealedFor in listOf(80f, 1000f)) {
            val entrance = CinemaPlaybackEntrance(eligible = true)
            entrance.advance(200f)
            entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
            entrance.advance(revealedFor)
            entrance.update(awaitingPicture = true, pictureReady = false, allowed = true)
            assertNeutral(entrance)
            entrance.advance(3000f)
            entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
            assertNeutral(entrance)
        }
    }

    @Test
    fun error_audio_only_and_motion_suppression_cancel_and_do_not_restart() {
        for (duringReveal in listOf(false, true)) {
            for (suppressed in listOf(false, true)) {
                val entrance = CinemaPlaybackEntrance(eligible = true)
                entrance.advance(150f)
                if (duringReveal) {
                    entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
                    entrance.advance(100f)
                }
                entrance.update(awaitingPicture = suppressed, pictureReady = false, allowed = !suppressed)
                assertNeutral(entrance)
                entrance.update(awaitingPicture = true, pictureReady = false, allowed = true)
                assertNeutral(entrance)
            }
        }
    }

    @Test
    fun fast_already_playing_or_missing_artwork_does_not_insert_an_entrance() {
        val entrance = CinemaPlaybackEntrance(eligible = false)
        entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
        entrance.advance(1000f)
        assertNeutral(entrance)
        entrance.update(awaitingPicture = true, pictureReady = false, allowed = true)
        assertNeutral(entrance)
    }

    @Test
    fun display_refresh_rate_does_not_change_the_camera_or_handoff_duration() {
        fun sample(steps: Int): CinemaPlaybackEntrance =
            CinemaPlaybackEntrance(eligible = true).also { entrance ->
                repeat(steps) { entrance.advance(360f / steps) }
                entrance.update(awaitingPicture = false, pictureReady = true, allowed = true)
                repeat(steps) { entrance.advance(180f / steps) }
            }
        val sixty = sample(24)
        val highRefresh = sample(48)
        assertEquals(sixty.scale, highRefresh.scale, 0.0001f)
        assertEquals(sixty.revealProgress, highRefresh.revealProgress, 0.0001f)
    }

    @Test
    fun invalid_or_backward_frame_time_does_not_corrupt_the_picture() {
        val entrance = CinemaPlaybackEntrance(eligible = true)
        for (delta in listOf(-10f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            entrance.advance(delta)
            assertEquals(1f, entrance.scale)
            assertEquals(0f, entrance.revealProgress)
        }
    }

    @Test
    fun aperture_stays_centered_inside_portrait_landscape_and_tabletop_bounds() {
        for (size in listOf(Size(1920f, 1080f), Size(1080f, 1920f), Size(1600f, 600f))) {
            var previous = Rect(size.width / 2f, size.height / 2f, size.width / 2f, size.height / 2f)
            for (step in 0..100) {
                val rect = cinemaPlaybackAperture(size, step / 100f)
                assertEquals(size.width / 2f, rect.center.x, 0.001f)
                assertEquals(size.height / 2f, rect.center.y, 0.001f)
                assertTrue(rect.left >= 0f && rect.top >= 0f && rect.right <= size.width && rect.bottom <= size.height)
                assertTrue(rect.width >= previous.width && rect.height >= previous.height)
                previous = rect
            }
            assertEquals(Rect(0f, 0f, size.width, size.height), previous)
        }
    }

    @Test
    fun invalid_aperture_inputs_never_expose_unbounded_or_nonfinite_geometry() {
        assertEquals(Rect.Zero, cinemaPlaybackAperture(Size.Zero, 0.5f))
        assertEquals(Rect.Zero, cinemaPlaybackAperture(Size(Float.NaN, 100f), 1f))
        assertEquals(Rect.Zero, cinemaPlaybackAperture(Size(100f, Float.POSITIVE_INFINITY), 1f))
        assertEquals(Rect.Zero, cinemaPlaybackAperture(Size(-20f, 10f), 1f))
        val size = Size(800f, 400f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertTrue(abs(cinemaPlaybackAperture(size, value).width) < 0.001f)
        }
        assertEquals(Rect(0f, 0f, 800f, 400f), cinemaPlaybackAperture(size, 2f))
    }

    private fun assertNeutral(entrance: CinemaPlaybackEntrance) {
        assertFalse(entrance.choreographed)
        assertFalse(entrance.needsFrames)
        assertEquals(1f, entrance.scale)
        assertEquals(0f, entrance.revealProgress)
    }
}
