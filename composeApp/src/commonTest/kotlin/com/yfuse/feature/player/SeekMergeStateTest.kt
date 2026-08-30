package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class SeekMergeStateTest {
    @Test
    fun rapid_seeks_keep_only_the_latest_position() {
        val merged =
            SeekMergeState()
                .offer(1_000L)
                .offer(2_000L)
                .offer(1_500L)

        assertEquals(1_500L, merged.positionMs)
        assertEquals(3L, merged.sequence)
    }

    @Test
    fun stale_consumer_cannot_clear_a_newer_seek() {
        val first = SeekMergeState().offer(1_000L)
        val newer = first.offer(3_000L)

        assertEquals(newer, newer.consumed(first.sequence))
        assertEquals(null, newer.consumed(newer.sequence).positionMs)
    }
}
