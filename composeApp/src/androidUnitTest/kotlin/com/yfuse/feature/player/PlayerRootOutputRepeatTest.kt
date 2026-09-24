package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [nextOutputRepeatCount] decides whether a playback-output generation change is a real startup
 * (a fresh `playback_startup_stage`) or a repeat for the item already on screen (`output_resumed`)
 * - chiefly a rebuffer recovery, which bumps `outputEvidenceGeneration` without the item
 * changing. Mixing the two up either lost first-frame counts for autoplayed episodes or, the bug
 * this backs, double-counted a startup on every rebuffer.
 */
class PlayerRootOutputRepeatTest {
    @Test
    fun the_very_first_generation_is_never_a_repeat() {
        assertEquals(0, nextOutputRepeatCount(previousItemIndex = null, currentItemIndex = 0, previousRepeatCount = 0))
    }

    @Test
    fun a_generation_change_for_the_same_item_index_counts_up() {
        assertEquals(1, nextOutputRepeatCount(previousItemIndex = 2, currentItemIndex = 2, previousRepeatCount = 0))
        assertEquals(4, nextOutputRepeatCount(previousItemIndex = 2, currentItemIndex = 2, previousRepeatCount = 3))
    }

    @Test
    fun a_new_item_index_resets_the_repeat_count_even_mid_binge() {
        // Episode 2 autoplaying into the same engine after episode 1 rebuffered twice.
        assertEquals(0, nextOutputRepeatCount(previousItemIndex = 0, currentItemIndex = 1, previousRepeatCount = 2))
    }
}
