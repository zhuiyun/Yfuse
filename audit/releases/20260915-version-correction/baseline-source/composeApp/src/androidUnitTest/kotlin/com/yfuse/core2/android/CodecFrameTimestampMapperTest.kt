package com.yfuse.core2.android

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecFrameTimestampMapperTest {
    @Test
    fun negative_large_duplicate_and_reordered_media_timestamps_are_restored_exactly() {
        val mapper = CodecFrameTimestampMapper()
        val original = listOf(Long.MIN_VALUE, 9_000_000L, Long.MAX_VALUE, 9_000_000L, -37L)
        val tokens = original.map(mapper::queue)
        assertEquals(tokens.size, tokens.distinct().size)
        assertTrue(tokens.all { it > 0L })
        for (index in listOf(2, 0, 4, 1, 3)) {
            assertEquals(original[index], mapper.dequeue(tokens[index]))
            assertTrue(mapper.release(tokens[index], render = true))
        }
        for (index in listOf(0, 3, 2, 4, 1)) {
            assertEquals(original[index], mapper.rendered(tokens[index]))
            assertNull(mapper.rendered(tokens[index]), "Duplicate callback must not count twice")
        }
    }

    @Test
    fun flush_invalidates_all_old_states_without_reusing_a_timestamp() {
        val mapper = CodecFrameTimestampMapper()
        val rendered = mapper.queue(100L)
        val held = mapper.queue(200L)
        val waiting = mapper.queue(300L)
        assertEquals(100L, mapper.dequeue(rendered))
        assertTrue(mapper.release(rendered, render = true))
        assertEquals(200L, mapper.dequeue(held))
        mapper.flush()
        assertNull(mapper.rendered(rendered))
        assertFalse(mapper.release(held, render = true))
        assertNull(mapper.dequeue(waiting))
        val next = mapper.queue(100L)
        assertTrue(next > waiting)
        assertEquals(100L, mapper.dequeue(next))
        assertTrue(mapper.release(next, render = true))
        assertEquals(100L, mapper.rendered(next))
    }

    @Test
    fun changing_listener_clears_released_evidence_but_keeps_queued_and_held_outputs() {
        val mapper = CodecFrameTimestampMapper()
        val oldRelease = mapper.queue(10L)
        val held = mapper.queue(20L)
        val queued = mapper.queue(30L)
        assertEquals(10L, mapper.dequeue(oldRelease))
        assertTrue(mapper.release(oldRelease, render = true))
        assertEquals(20L, mapper.dequeue(held))
        mapper.listenerChanged()
        assertNull(mapper.rendered(oldRelease))
        assertTrue(mapper.release(held, render = true))
        assertEquals(20L, mapper.rendered(held))
        assertEquals(30L, mapper.dequeue(queued))
        assertTrue(mapper.release(queued, render = true))
        assertEquals(30L, mapper.rendered(queued))
    }

    @Test
    fun try_again_capacity_checks_do_not_consume_tokens_and_drops_recycle_capacity() {
        val mapper = CodecFrameTimestampMapper(maximumTrackedFrames = 2)
        val first = mapper.queue(10L)
        repeat(100) { assertTrue(mapper.canQueue()) }
        val second = mapper.queue(20L)
        assertEquals(first + 1L, second)
        repeat(100) { assertFalse(mapper.canQueue()) }
        assertFailsWith<IllegalStateException> { mapper.queue(30L) }
        assertEquals(10L, mapper.dequeue(first))
        assertFalse(mapper.canQueue(), "An app-owned output still occupies its tracking slot")
        assertTrue(mapper.release(first, render = false))
        assertNull(mapper.rendered(first))
        assertTrue(mapper.canQueue())
        val third = mapper.queue(30L)
        assertEquals(second + 1L, third)
        mapper.cancelQueue(third)
        assertNull(mapper.dequeue(third))
        val retry = mapper.queue(30L)
        assertTrue(retry > third, "A failed enqueue cannot recycle a callback identity")
    }

    @Test
    fun only_actually_released_frames_supply_evidence_and_failed_release_revokes_it() {
        val mapper = CodecFrameTimestampMapper()
        val token = mapper.queue(55L)
        assertNull(mapper.rendered(token))
        assertEquals(55L, mapper.dequeue(token))
        assertNull(mapper.rendered(token))
        assertTrue(mapper.release(token, render = true))
        mapper.cancelRelease(token)
        assertNull(mapper.rendered(token))
        assertFalse(mapper.release(token, render = true))
        assertNull(mapper.rendered(987654321L))
    }

    @Test
    fun missing_callbacks_have_bounded_retention_without_blocking_future_inputs() {
        val mapper = CodecFrameTimestampMapper(maximumTrackedFrames = 1, maximumReleasedFrames = 2)
        val tokens =
            (0L until 100L).map { mediaTimeUs ->
                assertTrue(mapper.canQueue())
                val token = mapper.queue(mediaTimeUs)
                assertEquals(mediaTimeUs, mapper.dequeue(token))
                assertTrue(mapper.release(token, render = true))
                token
            }
        tokens.dropLast(2).forEach { assertNull(mapper.rendered(it)) }
        assertEquals(98L, mapper.rendered(tokens[98]))
        assertEquals(99L, mapper.rendered(tokens[99]))
    }

    @Test
    fun exhausted_identity_space_fails_without_wrapping_even_after_flush() {
        val last = CodecFrameTimestampMapper.MAX_CODEC_TIMESTAMP_US
        val mapper = CodecFrameTimestampMapper(firstToken = last - 1L)
        val first = mapper.queue(Long.MIN_VALUE)
        val second = mapper.queue(Long.MAX_VALUE)
        assertEquals(last - 1L, first)
        assertEquals(last, second)
        assertEquals(Long.MIN_VALUE, mapper.dequeue(first))
        assertEquals(Long.MAX_VALUE, mapper.dequeue(second))
        assertFailsWith<IllegalStateException> { mapper.queue(0L) }
        mapper.flush()
        assertFailsWith<IllegalStateException> { mapper.queue(0L) }
    }
}
