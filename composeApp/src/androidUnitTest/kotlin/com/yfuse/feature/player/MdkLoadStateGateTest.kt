package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdkLoadStateGateTest {
    @Test
    fun the_first_source_can_fail_without_waiting_for_a_nonexistent_previous_load() {
        val gate = MdkLoadStateGate(3_000L) { 0L }
        val epoch = gate.beginLoad()
        assertEquals(MdkLoadStatus.Waiting, gate.observe(epoch, rawInvalid = true))
        assertTrue(gate.didSubmit(epoch))
        assertEquals(MdkLoadStatus.Invalid, gate.observe(epoch, rawInvalid = true))
    }

    @Test
    fun native_event_bursts_cannot_turn_stale_invalid_into_a_terminal_failure() {
        var now = 0L
        val gate = MdkLoadStateGate(3_000L) { now }
        gate.didSubmit(gate.beginLoad())
        val fallback = gate.beginLoad()
        gate.didSubmit(fallback)

        repeat(10_000) {
            assertEquals(MdkLoadStatus.Waiting, gate.observe(fallback, rawInvalid = true))
        }
        now = 2_999L
        assertEquals(MdkLoadStatus.Waiting, gate.observe(fallback, rawInvalid = true))
        now = 3_000L
        assertEquals(MdkLoadStatus.Invalid, gate.observe(fallback, rawInvalid = true))
    }

    @Test
    fun encoder_cleanup_time_does_not_consume_the_new_streams_settle_window() {
        var now = 0L
        val gate = MdkLoadStateGate(3_000L) { now }
        gate.didSubmit(gate.beginLoad())
        val progressive = gate.beginLoad()
        now = 60_000L
        assertEquals(MdkLoadStatus.Waiting, gate.observe(progressive, rawInvalid = true))
        assertEquals(MdkLoadStatus.Waiting, gate.observe(progressive, rawInvalid = false))

        gate.didSubmit(progressive)
        now += 2_999L
        assertEquals(MdkLoadStatus.Waiting, gate.observe(progressive, rawInvalid = true))
        now++
        assertEquals(MdkLoadStatus.Invalid, gate.observe(progressive, rawInvalid = true))
    }

    @Test
    fun a_superseded_cleanup_or_poll_cannot_be_attributed_to_the_current_load() {
        var now = 0L
        val gate = MdkLoadStateGate(3_000L) { now }
        gate.didSubmit(gate.beginLoad())
        val old = gate.beginLoad()
        val current = gate.beginLoad()
        gate.didSubmit(current)
        now = 3_000L

        assertFalse(gate.isCurrent(old))
        assertFalse(gate.didSubmit(old))
        assertEquals(MdkLoadStatus.Stale, gate.observe(old, rawInvalid = true))
        assertEquals(MdkLoadStatus.Stale, gate.observe(old, rawInvalid = false))
        assertEquals(MdkLoadStatus.Invalid, gate.observe(current, rawInvalid = true))
    }

    @Test
    fun a_healthy_replacement_can_report_progress_during_the_settle_window() {
        val gate = MdkLoadStateGate(3_000L) { 0L }
        gate.didSubmit(gate.beginLoad())
        val current = gate.beginLoad()
        gate.didSubmit(current)

        assertEquals(MdkLoadStatus.Active, gate.observe(current, rawInvalid = false))
    }
}
