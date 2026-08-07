package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class WatchTogetherChatTest {
    @Test
    fun server_echo_replaces_optimistic_chat_instead_of_duplicating_it() {
        val pending = chat(
            id = -1L,
            clientId = "mine",
            clientMessageId = "mine-1",
            deliveryState = ChatDeliveryState.Pending,
        )
        val echoed = pending.copy(id = 7L, deliveryState = ChatDeliveryState.Sent)

        val merged = mergeIncomingWatchChat(listOf(pending), echoed, maxHistory = 50)

        assertEquals(listOf(echoed), merged)
    }

    @Test
    fun repeated_server_echo_is_idempotent() {
        val echoed = chat(
            id = 7L,
            clientId = "mine",
            clientMessageId = "mine-1",
            deliveryState = ChatDeliveryState.Sent,
        )
        val current = listOf(echoed)

        val merged = mergeIncomingWatchChat(current, echoed, maxHistory = 50)

        assertSame(current, merged)
    }

    @Test
    fun repeated_echo_also_clears_an_extreme_stale_pending_duplicate() {
        val echoed = chat(
            id = 7L,
            clientId = "mine",
            clientMessageId = "mine-1",
            deliveryState = ChatDeliveryState.Sent,
        )
        val stalePending = echoed.copy(id = -1L, deliveryState = ChatDeliveryState.Pending)

        assertEquals(
            listOf(echoed),
            mergeIncomingWatchChat(listOf(echoed, stalePending), echoed, maxHistory = 50),
        )
    }

    @Test
    fun another_senders_matching_correlation_id_is_not_removed() {
        val other = chat(
            id = -2L,
            clientId = "other",
            clientMessageId = "same",
            deliveryState = ChatDeliveryState.Pending,
        )
        val mine = chat(
            id = 8L,
            clientId = "mine",
            clientMessageId = "same",
            deliveryState = ChatDeliveryState.Sent,
        )

        assertEquals(
            listOf(other, mine),
            mergeIncomingWatchChat(listOf(other), mine, maxHistory = 50),
        )
    }

    private fun chat(
        id: Long,
        clientId: String,
        clientMessageId: String,
        deliveryState: ChatDeliveryState,
    ) = WatchChatMessage(
        id = id,
        clientId = clientId,
        name = clientId,
        avatarId = 0,
        text = "消息",
        sentAtMs = 100L,
        isMine = clientId == "mine",
        clientMessageId = clientMessageId,
        deliveryState = deliveryState,
    )
}
