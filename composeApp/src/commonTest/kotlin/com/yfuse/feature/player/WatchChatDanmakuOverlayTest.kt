package com.yfuse.feature.player

import com.yfuse.core.sync.WatchChatMessage
import com.yfuse.core.sync.ChatDeliveryState
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchChatDanmakuOverlayTest {
    @Test
    fun only_messages_after_the_seen_id_are_animated() {
        val messages = (1L..4L).map { id ->
            WatchChatMessage(id, "c$id", "用户$id", 0, "消息$id", id, false)
        }
        val seen = messages.take(2).mapTo(linkedSetOf()) { it.animationKey() }

        assertEquals(listOf(3L, 4L), watchChatMessagesNotSeen(messages, seen).map { it.id })
        assertEquals(
            emptyList(),
            watchChatMessagesNotSeen(messages, messages.mapTo(linkedSetOf()) { it.animationKey() }),
        )
    }

    @Test
    fun out_of_order_arrivals_are_sorted_before_animation() {
        val messages = listOf(5L, 3L, 4L).map { id ->
            WatchChatMessage(id, "c$id", "用户$id", 0, "消息$id", id, false)
        }

        assertEquals(
            listOf(3L, 4L, 5L),
            watchChatMessagesNotSeen(messages, emptySet()).map { it.id },
        )
    }

    @Test
    fun local_pending_message_is_animated_but_its_server_echo_is_not_replayed() {
        val pending = WatchChatMessage(
            id = -1L,
            clientId = "mine",
            name = "我",
            avatarId = 1,
            text = "一起看",
            sentAtMs = 100L,
            isMine = true,
            clientMessageId = "local-1",
            deliveryState = ChatDeliveryState.Pending,
        )
        assertEquals(listOf(pending), watchChatMessagesNotSeen(listOf(pending), emptySet()))

        val seen = setOf(pending.animationKey())
        val echoed = pending.copy(id = 9L, deliveryState = ChatDeliveryState.Sent)
        assertEquals(pending.animationKey(), echoed.animationKey())
        assertEquals(emptyList(), watchChatMessagesNotSeen(listOf(echoed), seen))
    }

    @Test
    fun the_open_chat_panel_never_holds_back_your_own_message() {
        // Sending is only possible from inside the panel, so this is the state every message
        // you write is written in. Holding it back here is holding back all of them.
        val mine = WatchChatMessage(1L, "mine", "我", 0, "哈哈", 1L, true)
        val theirs = WatchChatMessage(2L, "them", "对方", 0, "确实", 2L, false)

        assertEquals(
            listOf(mine),
            watchChatDanmakuArrivals(listOf(mine, theirs), emptySet(), held = true, limit = 6),
        )
    }

    @Test
    fun what_the_panel_held_back_flies_once_it_closes() {
        val theirs = (1L..3L).map { id ->
            WatchChatMessage(id, "them", "对方", 0, "消息$id", id, false)
        }
        // Nothing was marked seen while it was held, so the whole backlog is still owed.
        assertEquals(
            emptyList(),
            watchChatDanmakuArrivals(theirs, emptySet(), held = true, limit = 6),
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            watchChatDanmakuArrivals(theirs, emptySet(), held = false, limit = 6).map { it.id },
        )
    }

    @Test
    fun a_released_backlog_is_capped_to_one_message_per_lane() {
        val messages = (1L..10L).map { id ->
            WatchChatMessage(id, "them", "对方", 0, "消息$id", id, false)
        }
        // The newest, not the oldest: a burst that overflows the lanes should show what was
        // said last rather than what has already scrolled out of the panel.
        assertEquals(
            listOf(8L, 9L, 10L),
            watchChatDanmakuArrivals(messages, emptySet(), held = false, limit = 3).map { it.id },
        )
    }

    @Test
    fun identical_client_message_ids_from_different_senders_remain_distinct() {
        val first = WatchChatMessage(
            1L,
            "sender-a",
            "甲",
            0,
            "消息",
            1L,
            false,
            clientMessageId = "same-id",
        )
        val second = first.copy(id = 2L, clientId = "sender-b", name = "乙", sentAtMs = 2L)

        assertEquals(
            listOf(second),
            watchChatMessagesNotSeen(listOf(first, second), setOf(first.animationKey())),
        )
    }
}
