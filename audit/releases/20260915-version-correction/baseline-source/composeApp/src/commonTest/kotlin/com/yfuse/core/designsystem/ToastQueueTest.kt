package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastQueueTest {
    @Test fun old_timeout_cannot_clear_a_newer_notice() {
        val queue = ToastQueue()
        queue.post("first")
        val first = queue.entries.single()
        queue.post("second")
        assertFalse(queue.dismiss(first))
        assertTrue(queue.dismiss(queue.entries.last()))
        assertFalse(queue.dismiss(queue.entries.last()))
    }

    @Test fun bursts_are_bounded_and_duplicates_get_a_new_identity() {
        val queue = ToastQueue()
        repeat(10) { queue.post("notice-$it") }
        assertEquals(listOf("notice-7", "notice-8", "notice-9"), queue.entries.map { it.message })
        val old = queue.entries.last()
        queue.post("notice-9")
        assertEquals(3, queue.entries.size)
        assertFalse(queue.dismiss(old))
        assertTrue(queue.dismiss(queue.entries.last()))
    }

    @Test fun external_clear_disarms_retained_exiting_entries() {
        val queue = ToastQueue()
        queue.post("one")
        val entry = queue.entries.single()
        entry.visible = true
        queue.post(null)
        assertFalse(entry.visible)
        assertFalse(queue.dismiss(entry))
    }
}
