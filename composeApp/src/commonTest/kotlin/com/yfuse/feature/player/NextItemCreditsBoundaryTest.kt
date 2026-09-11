package com.yfuse.feature.player

import com.yfuse.core.data.SkipMode
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextItemCreditsBoundaryTest {
    private val segments =
        listOf(
            PlaybackSegment(PlaybackSegmentType.Intro, 0, 60_000),
            PlaybackSegment(PlaybackSegmentType.Credits, 1_200_000, null),
        )

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
