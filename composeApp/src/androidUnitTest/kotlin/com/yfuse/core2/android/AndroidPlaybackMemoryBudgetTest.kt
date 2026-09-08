package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlaybackMemoryBudgetTest {
    @Test
    fun os_trim_survives_heap_sampling_and_repeated_trim_extends_the_cooldown() {
        var now = 0L
        val policy = PlaybackMemoryPressurePolicy(nowNs = { now }, trimCooldownNs = 30L)
        assertTrue(policy.allowsSpeculation(false, 900L, 1_000L))
        policy.trim()
        assertFalse(policy.allowsSpeculation(false, 900L, 1_000L))
        now = 29L
        assertFalse(policy.allowsSpeculation(false, 900L, 1_000L))
        policy.trim()
        now = 30L
        assertFalse(policy.allowsSpeculation(false, 900L, 1_000L))
        now = 59L
        assertTrue(policy.allowsSpeculation(false, 900L, 1_000L))
    }

    @Test
    fun expired_trim_requires_both_foreground_and_recovered_heap() {
        var now = 0L
        val policy = PlaybackMemoryPressurePolicy(nowNs = { now }, trimCooldownNs = 30L)
        policy.trim()
        now = 30L
        assertFalse(policy.allowsSpeculation(true, 900L, 1_000L))
        assertFalse(policy.allowsSpeculation(false, 100L, 1_000L))
        assertTrue(policy.allowsSpeculation(false, 900L, 1_000L))
    }

    @Test fun concurrent_players_share_one_ceiling_and_release_returns_capacity() {
        val pool = PlaybackMemoryPool(1200L)
        val demux = pool.acquire(PlaybackBufferKind.Demux, 1000L)
        val transport = pool.acquire(PlaybackBufferKind.Transport, 1000L)
        val render = pool.acquire(PlaybackBufferKind.Render, 200L)
        assertEquals(200L, render.limitBytes)
        assertTrue(demux.limitBytes + transport.limitBytes + render.limitBytes <= 1200L)
        transport.close()
        transport.close()
        assertEquals(1000L, demux.limitBytes)
        assertEquals(0L, transport.limitBytes)
    }

    @Test fun pressure_reclaims_speculation_and_halves_active_budget_then_recovers() {
        val pool = PlaybackMemoryPool(1200L)
        val active = pool.acquire(PlaybackBufferKind.Demux, 1000L)
        val preload = pool.acquire(PlaybackBufferKind.Preload, 100L)
        val write = pool.acquire(PlaybackBufferKind.CacheWrite, 100L)
        pool.setPressure(true)
        assertEquals(600L, active.limitBytes)
        assertEquals(0L, preload.limitBytes)
        assertEquals(0L, write.limitBytes)
        pool.setPressure(false)
        assertEquals(1000L, active.limitBytes)
        assertEquals(100L, preload.limitBytes)
    }

    @Test fun small_heap_and_low_ram_devices_use_smaller_budgets() {
        val mib = 1024L * 1024L
        assertEquals(96L * mib, playbackMemoryBudgetBytes(512L * mib, false))
        assertTrue(playbackMemoryBudgetBytes(128L * mib, true) < playbackMemoryBudgetBytes(128L * mib, false))
    }
}
