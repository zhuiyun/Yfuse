package com.yfuse.core2.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidVideoOutputEpochTest {
    @Test
    fun fps_count_ignores_stale_duplicate_and_unsubmitted_callbacks_and_resets_on_seek() {
        val evidence = AndroidVideoOutputEpoch()
        val previous = evidence.reset(100L)
        evidence.submitted(10L)
        assertEquals(0L, evidence.renderedFrameCount)
        assertTrue(evidence.rendered(previous, 10L, 110L) {})
        assertFalse(evidence.rendered(previous, 10L, 115L) {})
        assertFalse(evidence.rendered(previous, 20L, 120L) {})
        assertEquals(1L, evidence.renderedFrameCount)
        val current = evidence.reset(200L)
        evidence.submitted(20L)
        assertFalse(evidence.rendered(previous, 20L, 210L) {})
        assertEquals(0L, evidence.renderedFrameCount)
        assertTrue(evidence.rendered(current, 20L, 220L) {})
        assertEquals(1L, evidence.renderedFrameCount)
    }

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

    @Test
    fun concurrent_callbacks_count_every_submitted_frame_once_and_publish_the_first_frame_once() {
        val evidence = AndroidVideoOutputEpoch()
        val epoch = evidence.reset(0L)
        repeat(200) { frame -> evidence.submitted(frame.toLong()) }
        val gate = CountDownLatch(1)
        val firstFrames = AtomicInteger()
        val callbacks =
            (0 until 4).map { worker ->
                thread {
                    gate.await()
                    repeat(50) { frame ->
                        evidence.rendered(epoch, (worker * 50 + frame).toLong(), 1L) { firstFrames.incrementAndGet() }
                    }
                }
            }
        gate.countDown()
        callbacks.forEach { it.join() }
        assertEquals(200L, evidence.renderedFrameCount)
        assertEquals(1, firstFrames.get())
    }

    @Test
    fun a_reset_racing_previous_generation_callbacks_always_wins() {
        repeat(50) {
            val evidence = AndroidVideoOutputEpoch()
            val previous = evidence.reset(0L)
            repeat(64) { frame -> evidence.submitted(frame.toLong()) }
            val gate = CountDownLatch(1)
            val firstFrames = AtomicInteger()
            val callbacks =
                thread {
                    gate.await()
                    repeat(64) { frame ->
                        evidence.rendered(previous, frame.toLong(), 1L) { firstFrames.incrementAndGet() }
                    }
                }
            val resetting =
                thread {
                    gate.await()
                    evidence.reset(0L)
                }
            gate.countDown()
            callbacks.join()
            resetting.join()
            // Whatever the interleaving, nothing the previous generation rendered survives the reset.
            assertEquals(0L, evidence.renderedFrameCount)
            assertFalse(evidence.verified)
            assertTrue(firstFrames.get() <= 1)
        }
    }
}
