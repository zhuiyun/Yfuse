package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class BoundedFrameLeasePoolTest {
    private data class Frame(
        val size: Int,
    )

    @Test
    fun presentation_leases_are_exclusive_and_frames_are_reused() {
        var allocations = 0
        val pool =
            BoundedFrameLeasePool<Int, Frame>(2, {
                allocations++
                Frame(it)
            }, {})
        val first = checkNotNull(pool.acquire(8))
        val second = checkNotNull(pool.acquire(8))
        assertNotSame(first.value, second.value)
        assertNull(pool.acquire(8))
        first.close()
        val next = checkNotNull(pool.acquire(8))
        assertSame(first.value, next.value)
        first.close() // A stale lease cannot release a frame currently used by its successor.
        assertNull(pool.acquire(8))
        next.close()
        second.close()
        assertEquals(2, allocations)
    }

    @Test
    fun resize_and_release_do_not_recycle_frames_still_being_presented() {
        val recycled = mutableListOf<Frame>()
        val pool = BoundedFrameLeasePool<Int, Frame>(2, ::Frame, recycled::add)
        val active = checkNotNull(pool.acquire(8))
        val idle = checkNotNull(pool.acquire(8))
        idle.close()
        val resized = checkNotNull(pool.acquire(16))
        assertEquals(listOf(idle.value), recycled)
        pool.clear()
        assertNull(pool.acquire(16))
        assertEquals(1, recycled.size)
        active.close()
        resized.close()
        assertEquals(3, recycled.size)
        assertEquals(16, checkNotNull(pool.acquire(16)).value.size)
    }
}
