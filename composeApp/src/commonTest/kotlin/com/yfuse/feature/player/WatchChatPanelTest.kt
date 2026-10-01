package com.yfuse.feature.player

import com.yfuse.core.sync.ChatDeliveryState
import com.yfuse.core.sync.WatchChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WatchChatPanelTest {
    @Test
    fun a_line_you_sent_keeps_its_row_when_the_servers_copy_replaces_it() {
        val pending =
            WatchChatMessage(
                id = -1L,
                clientId = "mine",
                name = "我",
                avatarId = 1,
                text = "开始了",
                sentAtMs = 100L,
                isMine = true,
                clientMessageId = "local-1",
                deliveryState = ChatDeliveryState.Pending,
            )
        val echoed = pending.copy(id = 42L, deliveryState = ChatDeliveryState.Sent)
        assertEquals(pending.rowKey(), echoed.rowKey())
        // Another sender's message id, and a server id with no message id, are rows of their own.
        assertNotEquals(echoed.rowKey(), echoed.copy(clientId = "theirs", isMine = false).rowKey())
        assertNotEquals(echoed.rowKey(), echoed.copy(clientMessageId = null).rowKey())
    }
}
