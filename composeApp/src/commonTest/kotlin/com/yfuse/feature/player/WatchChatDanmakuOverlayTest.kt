package com.yfuse.feature.player

import com.yfuse.core.sync.WatchChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchChatDanmakuOverlayTest {
    @Test
    fun only_messages_after_the_seen_id_are_animated() {
        val messages = (1L..4L).map { id ->
            WatchChatMessage(id, "c$id", "用户$id", 0, "消息$id", id, false)
        }

        assertEquals(listOf(3L, 4L), watchChatMessagesAfter(messages, 2L).map { it.id })
        assertEquals(emptyList(), watchChatMessagesAfter(messages, 4L))
    }

    @Test
    fun out_of_order_arrivals_are_sorted_before_animation() {
        val messages = listOf(5L, 3L, 4L).map { id ->
            WatchChatMessage(id, "c$id", "用户$id", 0, "消息$id", id, false)
        }

        assertEquals(listOf(3L, 4L, 5L), watchChatMessagesAfter(messages, 2L).map { it.id })
    }
}
