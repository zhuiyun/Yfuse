package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PlayerHandoffGeometryTest {
    private val portrait = ScreenGeometry(rotation = 0, size = Size(1080f, 2400f))
    private val landscape = ScreenGeometry(rotation = 1, size = Size(2400f, 1080f))

    @Test fun natural_frame_round_trips_through_every_rotation() {
        val natural = Size(1080f, 2400f)
        val point = Offset(123f, 456f)
        for (rotation in 0..3) {
            val back = fromNatural(toNatural(point, rotation, natural), rotation, natural)
            assertEquals(point.x, back.x, absoluteTolerance = 0.001f)
            assertEquals(point.y, back.y, absoluteTolerance = 0.001f)
        }
    }

    @Test fun same_orientation_maps_to_the_very_same_pixels() {
        val box = requireNotNull(physicalBox(Rect(100f, 200f, 300f, 600f), portrait, portrait))
        assertEquals(Offset(200f, 400f), box.center)
        assertEquals(200f, box.width)
        assertEquals(400f, box.height)
        assertEquals(0f, box.rotation)
    }

    @Test fun a_portrait_hero_lands_on_the_same_glass_in_the_landscape_player() {
        // The detail hero: full width across the top of a portrait phone.
        val hero = Rect(0f, 0f, 1080f, 1150f)
        val box = requireNotNull(physicalBox(hero, portrait, landscape, corner = 8f))
        // ROTATION_90 puts the natural top edge on the left: the hero is a band down that side.
        assertEquals(575f, box.center.x, absoluteTolerance = 0.01f)
        assertEquals(540f, box.center.y, absoluteTolerance = 0.01f)
        // Its own frame is untouched; it is drawn turned a quarter back, so it reads upright on the glass.
        assertEquals(1080f, box.width)
        assertEquals(1150f, box.height)
        assertEquals(-90f, box.rotation)
        assertEquals(8f, box.corner)
    }

    @Test fun the_way_back_undoes_the_way_in() {
        val key = Rect(48f, 960f, 1032f, 1096f)
        val there = requireNotNull(physicalBox(key, portrait, landscape))
        val onLandscape =
            Rect(there.center - Offset(there.height / 2f, there.width / 2f), Size(there.height, there.width))
        val back = requireNotNull(physicalBox(onLandscape, landscape, portrait))
        assertEquals(key.center.x, back.center.x, absoluteTolerance = 0.01f)
        assertEquals(key.center.y, back.center.y, absoluteTolerance = 0.01f)
        assertEquals(90f, back.rotation)
    }

    @Test fun a_different_display_has_no_physical_match() {
        val tablet = ScreenGeometry(rotation = 1, size = Size(2560f, 1600f))
        assertNull(physicalBox(Rect(0f, 0f, 10f, 10f), portrait, tablet))
    }

    @Test fun window_offsets_are_on_screen_coordinates() {
        val origin =
            PlayerArtworkOrigin(
                MediaSharedElementKey("test", "offset"),
                Rect(0f, 0f, 100f, 100f),
                Rect(0f, 0f, 1000f, 2000f),
                listOf("test://hero"),
                ScreenGeometry(0, Size(1000f, 2000f), windowOffset = Offset(0f, 40f)),
            )
        assertEquals(Rect(0f, 40f, 100f, 140f), origin.boundsOnScreen)
    }

    @Test fun visible_share_counts_only_what_is_on_the_display() {
        val screen = Size(1000f, 2000f)
        assertEquals(1f, visibleShare(Rect(0f, 0f, 1000f, 500f), screen))
        assertEquals(0.25f, visibleShare(Rect(0f, -300f, 1000f, 100f), screen), absoluteTolerance = 0.001f)
        assertEquals(0f, visibleShare(Rect(0f, -500f, 1000f, -100f), screen))
    }

    @Test fun a_covering_turn_never_uncovers_a_corner() {
        val frame = Size(2400f, 1080f)
        val portraitFull = HandoffBox(Offset(1200f, 540f), 1080f, 2400f, rotation = -90f)
        val landscapeFull = HandoffBox(Offset(1200f, 540f), 2400f, 1080f)
        val halfDiagonal = sqrt(frame.width * frame.width + frame.height * frame.height) / 2f
        for (step in 0..20) {
            val box = coveringTurn(step / 20f, portraitFull, landscapeFull, frame)
            val cover = coverSize(box.rotation, frame)
            assertTrue(box.width >= cover.width - 0.01f, "width at step $step")
            assertTrue(box.height >= cover.height - 0.01f, "height at step $step")
            // Halfway round the box is at its largest — a square wider than the screen is tall.
            if (step == 10) assertTrue(box.width > halfDiagonal)
        }
        assertEquals(landscapeFull, coveringTurn(1f, portraitFull, landscapeFull, frame))
    }

    @Test fun cover_size_at_a_quarter_turn_swaps_the_frame() {
        val cover = coverSize(-90f, Size(2400f, 1080f))
        assertEquals(1080f, cover.width, absoluteTolerance = 0.01f)
        assertEquals(2400f, cover.height, absoluteTolerance = 0.01f)
    }

    @Test fun the_fitted_picture_is_pillarboxed_in_a_tall_phone() {
        val picture = fittedPicture(Size(2400f, 1080f), 16f / 9f)
        assertEquals(1920f, picture.width, absoluteTolerance = 0.01f)
        assertEquals(1080f, picture.height, absoluteTolerance = 0.01f)
        assertEquals(Offset(1200f, 540f), picture.center)
    }

    @Test fun a_window_on_time_runs_the_page_clock_and_a_late_one_waits() {
        for (style in PlayerTransitionStyle.entries) {
            val timing = style.timing
            // The delayed fade ends exactly where the player's own half starts.
            assertEquals(
                timing.morphStart,
                EXPECTED_FIRST_FRAME_MS + timing.windowDelay + timing.windowFade,
                "window fade for $style",
            )
            assertEquals(0f, handoffPlayerLag(timing, EXPECTED_FIRST_FRAME_MS.toFloat()), "on time: $style")
            assertEquals(260f, handoffPlayerLag(timing, EXPECTED_FIRST_FRAME_MS + 260f), "late: $style")
            // The page has reached the frame the player takes over from before the window shows.
            assertTrue(timing.pageHeld <= timing.morphStart, "held before takeover: $style")
        }
    }

    @Test fun the_tide_crest_rises_then_settles() {
        assertEquals(0f, tideCrest(0f))
        // A quarter period in, the crest is lifting the page (negative is up).
        assertTrue(tideCrest(TIDE_PERIOD_MS / 4f) < -0.2f)
        assertTrue(abs(tideCrest(1_200f)) < 0.01f)
    }

    @Test fun a_press_spring_overshoots_and_a_settle_spring_barely_does() {
        val press = (0..600).maxOf { handoffSpring(it.toFloat(), 0.6f, 1500f) }
        val settle = (0..900).maxOf { handoffSpring(it.toFloat(), 0.85f, 400f) }
        assertTrue(press > 1.05f)
        assertTrue(settle < 1.02f)
        assertEquals(1f, handoffSpring(2_000f, 0.85f, 400f), absoluteTolerance = 0.001f)
    }

    @Test fun glass_bar_stretches_a_pill_across_the_page_and_keeps_a_full_width_key() {
        val density = 3f
        val pill = HandoffKey(Rect(60f, 1392f, 348f, 1530f), corner = 69f, tint = null, ink = null, glass = true)
        val stretched = launch(key = pill).glassBarOnScreen(density)
        assertEquals(Rect(54f, 1392f, 1026f, 1530f), stretched)
        val wide = HandoffKey(Rect(54f, 1101f, 1026f, 1257f), corner = 48f, tint = null, ink = null, glass = false)
        assertEquals(wide.boundsOnScreen, launch(key = wide).glassBarOnScreen(density))
    }

    @Test fun push_in_covers_the_page_from_a_short_hero() {
        assertEquals(2400f / 1150f, launch(key = null).pushScale(), absoluteTolerance = 0.001f)
    }

    private fun launch(key: HandoffKey?): HandoffLaunch =
        HandoffLaunch(
            style = PlayerTransitionStyle.Glass,
            startedAt = TimeSource.Monotonic.markNow(),
            screen = ScreenGeometry(0, Size(1080f, 2400f)),
            hero = Rect(0f, 0f, 1080f, 1150f),
            urls = listOf("test://hero"),
            key = key,
        )
}
