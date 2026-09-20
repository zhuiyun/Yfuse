package com.yfuse.core2.android

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AndroidPlaybackStagingBufferTest {
    @Test
    fun large_software_staging_and_two_render_frames_reduce_caches_without_rejecting_the_frame() {
        val mib = 1024L * 1024L
        val pool = PlaybackMemoryPool(96L * mib)
        val transport = pool.acquire(PlaybackBufferKind.Transport, 32L * mib)
        val demux = pool.acquire(PlaybackBufferKind.Demux, 24L * mib)
        val preload = pool.acquire(PlaybackBufferKind.Preload, 8L * mib)
        val staging = pool.reserve()
        val render = pool.reserve()
        val frameBytes = 3840L * 2160L * 4L
        staging.resize(frameBytes)
        render.resize(frameBytes * 2L)
        assertEquals(frameBytes, staging.bytes)
        assertEquals(frameBytes * 2L, render.bytes)
        val disposable = transport.limitBytes + demux.limitBytes + preload.limitBytes
        kotlin.test.assertTrue(disposable <= 96L * mib - frameBytes * 3L)
        pool.setPressure(true)
        assertEquals(0L, transport.limitBytes + demux.limitBytes + preload.limitBytes)
        assertEquals(frameBytes * 2L, render.bytes, "Pressure cannot truncate an in-flight bitmap")
        render.close()
        staging.close()
        kotlin.test.assertTrue(transport.limitBytes > 0L)
    }

    @Test
    fun staging_is_lazy_reclaims_cache_budget_and_releases_capacity_on_close() {
        val pool = PlaybackMemoryPool(1_000L)
        val cache = pool.acquire(PlaybackBufferKind.Transport, 1_000L)
        var allocations = 0
        val storage =
            AndroidPlaybackStagingBuffer(1, 2_000, pool::reserve) {
                allocations++
                ByteBuffer.allocateDirect(it)
            }
        assertEquals(0, allocations)
        assertEquals(1_000L, cache.limitBytes)
        val first = storage.grow(600)
        assertEquals(400L, cache.limitBytes)
        assertSame(first, storage.grow(400))
        assertEquals(1, allocations)
        storage.grow(1_200)
        assertEquals(0L, cache.limitBytes, "A required large frame must reduce caches rather than be truncated")
        storage.close()
        storage.close()
        assertEquals(1_000L, cache.limitBytes)
        assertNotSame(first, storage.get())
        assertEquals(999L, cache.limitBytes)
        storage.close()
        cache.close()
    }

    @Test
    fun failed_growth_keeps_previous_storage_and_restores_its_reservation() {
        val pool = PlaybackMemoryPool(1_000L)
        val cache = pool.acquire(PlaybackBufferKind.Demux, 1_000L)
        val storage =
            AndroidPlaybackStagingBuffer(10, 1_000, pool::reserve) {
                if (it > 10) {
                    assertEquals(890L, cache.limitBytes, "Growth reserves both old and new allocations")
                    throw OutOfMemoryError("test allocation failure")
                }
                ByteBuffer.allocateDirect(it)
            }
        val original = storage.get()
        assertFailsWith<OutOfMemoryError> { storage.grow(100) }
        assertSame(original, storage.get())
        assertEquals(990L, cache.limitBytes)
        storage.close()
        assertEquals(1_000L, cache.limitBytes)
    }

    @Test
    fun memory_pressure_preserves_required_staging_while_shrinking_disposable_bytes() {
        val pool = PlaybackMemoryPool(1_000L)
        val active = pool.acquire(PlaybackBufferKind.Demux, 1_000L)
        val reservation = pool.reserve()
        reservation.resize(300L)
        pool.setPressure(true)
        assertEquals(200L, active.limitBytes)
        reservation.resize(700L)
        assertEquals(0L, active.limitBytes)
        reservation.close()
        assertEquals(500L, active.limitBytes)
    }
}
