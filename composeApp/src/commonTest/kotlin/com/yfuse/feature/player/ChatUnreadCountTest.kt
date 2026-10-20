package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatUnreadCountTest {
    @Test fun deliveryAcknowledgementDoesNotCountAsANewMessage() {
        assertEquals(0, chatUnreadCount(listOf("client-1"), "client-1"))
        assertEquals(2, chatUnreadCount(listOf("client-1", "remote-2", "remote-3"), "client-1"))
    }

    @Test fun aTrimmedAnchorCountsOnlyRetainedMessages() {
        assertEquals(2, chatUnreadCount(listOf("new-1", "new-2"), "old"))
        assertEquals(0, chatUnreadCount(emptyList<String>(), null))
    }
}
