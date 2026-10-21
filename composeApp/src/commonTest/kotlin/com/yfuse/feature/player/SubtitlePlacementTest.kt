package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitlePlacementTest {
    private fun place(
        picture: Int,
        block: Int,
        region: SubtitleRegion = SubtitleRegion.PreferBlackBars,
        height: Int = 1080,
    ) = placeSubtitleBlock(height, picture, block, region, 0.92f, 8)

    @Test
    fun a_caption_is_centered_in_the_bottom_bar_without_changing_its_size() {
        assertEquals(SubtitlePlacement(980, true), place(800, 60))
    }

    @Test
    fun dual_tracks_are_one_measured_block_and_fall_back_together() {
        assertEquals(SubtitlePlacement(960, true), place(800, 100))
        val tooTall = place(800, 130)
        assertFalse(tooTall.inBlackBar)
        assertTrue(tooTall.top >= 140 && tooTall.top + 130 <= 940)
    }

    @Test
    fun a_bar_must_fit_the_whole_block_with_both_safe_insets() {
        assertTrue(place(800, 124).inBlackBar)
        assertFalse(place(800, 125).inBlackBar)
    }

    @Test
    fun picture_mode_restores_the_saved_position_even_when_a_bar_is_available() {
        assertEquals(SubtitlePlacement(816, false), place(800, 60, SubtitleRegion.Picture))
    }

    @Test
    fun side_bars_fill_stretch_and_unknown_video_size_have_no_bottom_caption_region() {
        for (picture in listOf(1080, 2000, 0, -1)) assertFalse(place(picture, 60).inBlackBar)
    }

    @Test
    fun portrait_window_and_window_resize_recompute_the_available_space() {
        assertTrue(place(450, 160, height = 2400).inBlackBar)
        assertFalse(place(800, 160, height = 850).inBlackBar)
        assertTrue(place(800, 160, height = 1400).inBlackBar)
    }

    @Test
    fun empty_caption_does_not_reserve_a_black_bar() {
        assertFalse(place(800, 0).inBlackBar)
    }

    @Test
    fun invalid_positions_and_large_blocks_never_create_negative_coordinates() {
        assertEquals(816, placeSubtitleBlock(1080, 800, 60, SubtitleRegion.Picture, Float.NaN, 8).top)
        assertEquals(0, placeSubtitleBlock(1080, 1080, 1080, SubtitleRegion.Picture, 0.6f, 8).top)
        assertEquals(0, placeSubtitleBlock(1080, 400, 1080, SubtitleRegion.Picture, 0.6f, 8).top)
        assertEquals(0, placeSubtitleBlock(0, 0, 0, SubtitleRegion.PreferBlackBars, 0.92f, 8).top)
    }

    @Test
    fun native_reserve_accounts_for_large_fonts_and_both_tracks() {
        val single = nativeSubtitleReserve(800, 1f, null, 28)
        val dual = nativeSubtitleReserve(800, 1f, 1.5f, 28)
        assertEquals(104, single)
        assertEquals(260, dual)
        assertTrue(place(800, single).inBlackBar)
        assertFalse(place(800, dual).inBlackBar)
        assertEquals(160, nativeSubtitleReserve(100, 1f, null, 80))
    }
}
