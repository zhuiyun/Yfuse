package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Anime4KFrameLedgerTest {
    @Test
    fun coalesced_frame_confirms_only_the_texture_that_was_presented() {
        val ledger = Anime4KFrameLedger()
        ledger.scheduled(1_000_000L, 1L)
        ledger.scheduled(2_000_000L, 2L)
        assertEquals(2L, ledger.presented(2_000_000L))
        assertNull(ledger.presented(1_000_000L))
        assertNull(ledger.presented(2_000_000L))
    }

    @Test
    fun stale_seek_frame_cannot_be_assigned_to_new_codec_identity() {
        val ledger = Anime4KFrameLedger()
        ledger.scheduled(1_000_000L, 1L)
        ledger.clear()
        ledger.scheduled(2_000_000L, 2L)
        assertNull(ledger.presented(1_000_000L))
        assertEquals(2L, ledger.presented(2_000_000L))
    }

    @Test
    fun immediate_render_uses_codec_timestamp_and_duplicate_vsync_uses_latest_frame() {
        val ledger = Anime4KFrameLedger()
        ledger.scheduled(1_000L, 1L)
        assertEquals(1L, ledger.presented(1_000L))
        ledger.scheduled(5_000_000L, 2L)
        ledger.scheduled(5_000_000L, 3L)
        assertEquals(3L, ledger.presented(5_000_000L))
    }

    @Test
    fun missing_callbacks_do_not_retain_unbounded_frames_or_match_nearby_timestamps() {
        val ledger = Anime4KFrameLedger(capacity = 2)
        ledger.scheduled(1_000_000L, 1L)
        ledger.scheduled(2_000_000L, 2L)
        ledger.scheduled(3_000_000L, 3L)
        assertNull(ledger.presented(1_000_000L))
        assertNull(ledger.presented(2_000_001L))
        assertEquals(2L, ledger.presented(2_000_000L))
    }
}
