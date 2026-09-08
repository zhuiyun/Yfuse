package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YAdaptiveReopenGateTest {
    @Test
    fun incompatible_switch_is_single_use_and_old_requests_cannot_reopen_after_seek() {
        val gate = YAdaptiveReopenGate(cooldownNs = 0L)
        val first = gate.beginTarget(1L)
        gate.propose(first, 1L, "low")
        assertEquals("low", gate.consume(1L))
        assertNull(gate.consume(1L))
        val afterSeek = gate.beginTarget(2L)
        gate.propose(first, 2L, "old-read")
        assertNull(gate.consume(2L))
        gate.propose(afterSeek, 1L, "old-feedback")
        assertNull(gate.consume(2L))
        gate.propose(afterSeek, 2L, "current")
        assertEquals("current", gate.consume(2L))
    }

    @Test
    fun cooldown_and_generation_budget_prevent_an_automatic_reopen_loop() {
        var now = 0L
        val gate =
            YAdaptiveReopenGate(
                nowNs = { now },
                cooldownNs = 10L,
                maximumReopensPerGeneration = 2,
                reopenWindowNs = 20L,
            )
        var revision = gate.beginTarget(3L)
        gate.propose(revision, 3L, "low")
        assertEquals("low", gate.consume(3L))
        revision = gate.beginTarget(3L)
        gate.propose(revision, 3L, "high")
        assertNull(gate.consume(3L))
        now = 10L
        gate.propose(revision, 3L, "high")
        assertEquals("high", gate.consume(3L))
        now = 20L
        revision = gate.beginTarget(3L)
        gate.propose(revision, 3L, "low")
        assertNull(gate.consume(3L))
        revision = gate.beginTarget(4L)
        gate.propose(revision, 4L, "fresh")
        assertEquals("fresh", gate.consume(4L))
    }

    @Test
    fun attaching_new_children_does_not_reset_the_rolling_reopen_limit() {
        var now = 0L
        val gate =
            YAdaptiveReopenGate(
                nowNs = { now },
                cooldownNs = 0L,
                maximumReopensPerGeneration = 2,
                reopenWindowNs = 100L,
            )
        repeat(2) { index ->
            val generation = index.toLong()
            val revision = gate.beginTarget(generation)
            gate.propose(revision, generation, "switch-$index")
            assertEquals("switch-$index", gate.consume(generation))
        }
        val revision = gate.beginTarget(3L)
        gate.propose(revision, 3L, "loop")
        assertNull(gate.consume(3L))
        now = 100L
        gate.propose(revision, 3L, "recovered")
        assertEquals("recovered", gate.consume(3L))
    }
}
