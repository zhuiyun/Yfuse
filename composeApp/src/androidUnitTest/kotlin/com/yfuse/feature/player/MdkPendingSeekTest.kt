package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkPendingSeekTest {
    @Test
    fun a_new_item_at_zero_does_not_inherit_the_departing_native_clock() {
        val pending = MdkPendingSeek(0L)
        assertEquals(0L, pending.positionForHandover(0L, loadPending = true) { 45000L })
    }

    @Test
    fun handover_preserves_pending_resume_until_native_seek_submission() {
        val pending = MdkPendingSeek(12000L)
        assertEquals(12000L, pending.positionForHandover(0L) { 0L })
        pending.request(0L)
        assertEquals(0L, pending.positionForHandover(12000L) { 45000L })
        pending.applyIfLoaded(true) { }
        assertEquals(600L, pending.positionForHandover(0L) { 600L })
        assertEquals(900L, pending.positionForHandover(900L) { error("released native player") })
    }

    @Test
    fun the_latest_seek_waits_for_media_and_replaces_the_initial_resume_position() {
        val pending = MdkPendingSeek(12_000L)
        pending.request(45_000L)
        assertFalse(pending.applyIfLoaded(false) { error("Media is not loaded") })
        assertEquals(45_000L, pending.positionMs)
        val applied = mutableListOf<Long>()
        assertTrue(pending.applyIfLoaded(true, applied::add))
        assertEquals(listOf(45_000L), applied)
        assertFalse(pending.applyIfLoaded(true, applied::add))
    }

    @Test
    fun explicit_zero_seeks_but_a_fresh_load_at_zero_does_not() {
        val pending = MdkPendingSeek(0L)
        assertFalse(pending.applyIfLoaded(true) { error("Already starts at zero") })
        pending.request(0L)
        assertTrue(pending.applyIfLoaded(true) { assertEquals(0L, it) })
    }

    @Test
    fun a_seek_received_during_native_application_is_not_cleared_by_the_old_completion() {
        val pending = MdkPendingSeek(12_000L)
        assertTrue(pending.applyIfLoaded(true) { pending.request(50_000L) })
        assertEquals(50_000L, pending.positionMs)
        assertTrue(pending.applyIfLoaded(true) { assertEquals(50_000L, it) })
    }

    @Test
    fun a_failed_native_seek_keeps_the_requested_position() {
        val pending = MdkPendingSeek(12_000L)
        assertFailsWith<IllegalStateException> {
            pending.applyIfLoaded(true) { error("Native seek failed") }
        }
        assertEquals(12_000L, pending.positionMs)
        assertTrue(pending.applyIfLoaded(true) { assertEquals(12_000L, it) })
    }

    @Test
    fun selecting_another_item_discards_the_previous_seek_and_retry_restores_its_resume_point() {
        val pending = MdkPendingSeek(12_000L)
        pending.reset(0L)
        assertFalse(pending.applyIfLoaded(true) { error("Old item must not seek the new one") })
        pending.reset(23_000L)
        assertTrue(pending.applyIfLoaded(true) { assertEquals(23_000L, it) })
    }
}
