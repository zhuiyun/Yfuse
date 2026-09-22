package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidVideoOutputEpochTest {
    @Test
    fun repeated_media_pts_retain_one_slot_per_genuinely_submitted_frame() {
        val evidence = AndroidVideoOutputEpoch()
        val epoch = evidence.reset(100L)
        evidence.submitted(10L)
        evidence.submitted(10L)
        assertTrue(evidence.rendered(epoch, 10L, 90L, frameIdentityIsolated = true) {})
        assertTrue(evidence.rendered(epoch, 10L, 90L, frameIdentityIsolated = true) {})
        assertFalse(evidence.rendered(epoch, 10L, 90L, frameIdentityIsolated = true) {})
    }

    @Test
    fun isolated_frame_identity_accepts_real_callback_with_stale_vendor_time_without_measuring_it() {
        val evidence = AndroidVideoOutputEpoch()
        val previous = evidence.reset(100L)
        evidence.submitted(2_000_000L)
        val current = evidence.reset(200L)
        evidence.submitted(2_000_000L)
        assertFalse(evidence.rendered(previous, 2_000_000L, 90L, frameIdentityIsolated = true) {})
        assertFalse(evidence.rendered(current, 9_000_000L, 90L, frameIdentityIsolated = true) {})
        assertFalse(evidence.verified)
        assertTrue(evidence.rendered(current, 2_000_000L, 90L, frameIdentityIsolated = true) {})
        assertTrue(evidence.verified)
        assertFalse(evidence.recordRenderTime(current, 90L, 300L))
    }

    @Test
    fun fixed_backwards_future_and_old_epoch_render_times_cannot_produce_sync_measurements() {
        val evidence = AndroidVideoOutputEpoch()
        val epoch = evidence.reset(100L)
        assertFalse(evidence.recordRenderTime(epoch, 110L, 200L))
        evidence.submitted(0L)
        assertTrue(evidence.rendered(epoch, 0L, 110L, frameIdentityIsolated = true) {})
        assertTrue(evidence.recordRenderTime(epoch, 110L, 200L))
        assertFalse(evidence.recordRenderTime(epoch, 110L, 300L))
        assertFalse(evidence.recordRenderTime(epoch, 105L, 300L))
        assertFalse(evidence.recordRenderTime(epoch, 500L, 300L))
        assertTrue(evidence.recordRenderTime(epoch, 120L, 300L))
        val current = evidence.reset(400L)
        evidence.submitted(0L)
        assertTrue(evidence.rendered(current, 0L, 110L, frameIdentityIsolated = true) {})
        assertFalse(evidence.recordRenderTime(epoch, 450L, 500L))
        assertFalse(evidence.recordRenderTime(current, 120L, 500L))
        assertTrue(evidence.recordRenderTime(current, 450L, 500L))
    }

    @Test
    fun submission_is_not_render_evidence_and_old_callbacks_cannot_verify_a_seek() {
        val evidence = AndroidVideoOutputEpoch()
        val previous = evidence.reset(100L)
        evidence.submitted(8_000_000L)
        assertFalse(evidence.verified)
        val current = evidence.reset(200L)
        evidence.submitted(2_000_000L)
        assertFalse(evidence.rendered(previous, 8_000_000L, 210L) {})
        assertFalse(evidence.rendered(current, 8_000_000L, 210L) {})
        assertFalse(evidence.rendered(current, 2_000_000L, 190L) {})
        assertFalse(evidence.verified)
        assertTrue(evidence.rendered(current, 2_000_000L, 220L) {})
        assertTrue(evidence.verified)
    }

    @Test
    fun reusing_a_presentation_timestamp_on_a_new_surface_requires_a_new_render() {
        val evidence = AndroidVideoOutputEpoch()
        val previous = evidence.reset(100L)
        evidence.submitted(0L)
        assertTrue(evidence.rendered(previous, 0L, 110L) {})
        val current = evidence.reset(200L)
        evidence.submitted(0L)
        assertFalse(evidence.rendered(previous, 0L, 210L) {})
        assertFalse(evidence.verified)
        assertTrue(evidence.rendered(current, 0L, 210L) {})
    }
}
