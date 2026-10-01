package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        // Overflow leaves by its exit rather than vanishing, so the older ones linger hidden.
        assertEquals(listOf("notice-7", "notice-8", "notice-9"), queue.entries.filter { it.visible }.map { it.message })
        assertTrue(queue.entries.size <= 6)
        val old = queue.entries.last()
        queue.post("notice-9")
        assertEquals(3, queue.entries.count { it.visible })
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

    @Test fun an_undo_cut_short_closes_as_its_timeout_would_and_answers_once() {
        val queue = ToastQueue()
        queue.post("已删除 3 项下载", action = ToastAction("撤销") {})
        val undo = queue.entries.single()
        assertSame(undo, queue.closeUndos())
        assertFalse(undo.visible)
        // Already gone: nothing to answer for a second time.
        assertNull(queue.closeUndos())
        assertFalse(queue.dismiss(undo))
    }

    @Test fun cutting_toasts_short_leaves_plain_notices_alone() {
        val queue = ToastQueue()
        queue.post("已删除「深海回声」的下载", action = ToastAction("撤销") {})
        val undo = queue.entries.single()
        queue.post("已加入收藏")
        // The newer notice retired the undo already; its producer committed when it posted.
        assertFalse(undo.visible)
        assertNull(queue.closeUndos())
        assertTrue(queue.entries.last().visible)
    }
}
