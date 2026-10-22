package com.yfuse.watch

import io.ktor.websocket.WebSocketSession
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WatchRoomChatTest {
    @Test
    fun retryAfterHistoryEvictionReturnsOriginalReceiptWithoutConsumingQuota() {
        val room = room()
        val session = room.participants.getValue("host").session
        val first = assertIs<RoomChatResult.Accepted>(room.acceptChat(session, "host", "first", "id-0", 0))
        repeat(55) { i ->
            assertIs<RoomChatResult.Accepted>(
                room.acceptChat(session, "host", "later", "id-${i + 1}", (i + 1) * 3_001L),
            )
        }
        assertEquals(50, room.chatHistory.size)
        val retry = assertIs<RoomChatResult.Duplicate>(room.acceptChat(session, "host", "changed", "id-0", 200_000))
        assertEquals(first.message, retry.message)
        assertEquals(56L, room.nextChatId)
    }

    @Test
    fun receiptsExpireAndEvictOldestWithoutRefreshingOnRetry() {
        var now = 0L
        val receipts = WatchChatReceipts(capacity = 2, ttlMs = 100, clock = { now })
        val room = room(receipts)
        val session = room.participants.getValue("host").session
        repeat(3) { i -> room.acceptChat(session, "host", "text", "id-$i", i * 3_001L) }
        assertNull(receipts.find("account", "host", "id-0"))
        assertNull(receipts.find("account", "other-client", "id-1"))
        assertNull(receipts.find("other-account", "host", "id-1"))
        now = 99
        assertEquals("id-1", receipts.find("account", "host", "id-1")?.clientMessageId)
        now = 100
        assertNull(receipts.find("account", "host", "id-1"))
        assertIs<RoomChatResult.Duplicate>(room.acceptChat(session, "host", "text", "id-1", 20_000))
        assertEquals(3, room.chatHistory.size)
        assertEquals(3L, room.nextChatId)
        assertNull(receipts.find("account", "host", "id-1"))
    }

    @Test
    fun expiredReceiptRetriesKeepOriginalMessageAndUniqueWelcomeHistory() {
        var now = 0L
        val receipts = WatchChatReceipts(clock = { now })
        val room = room(receipts)
        val session = room.participants.getValue("host").session
        val first = assertIs<RoomChatResult.Accepted>(room.acceptChat(session, "host", "original", "same", now))
        now = 300_001L
        assertNull(receipts.find("account", "host", "same"))
        val member = room.memberships.getValue(memberKey("account", "host"))
        member.chatMutedUntilMs = Long.MAX_VALUE
        val pool = Executors.newFixedThreadPool(4)
        try {
            val retries =
                pool
                    .invokeAll(List(8) { Callable { room.acceptChat(session, "host", "changed", "same", now) } })
                    .map { assertIs<RoomChatResult.Duplicate>(it.get()).message }
            assertEquals(List(8) { first.message }, retries)
            assertEquals(1L, room.nextChatId)
            assertEquals(listOf(0L), member.chatSentAtMs.toList())
            assertEquals(0, member.chatRejectedAtMs.size)
            val welcome = room.welcomeMessage("guest", null, null, protocolVersion = 5)
            assertEquals(listOf(first.message), welcome.chatHistory)
            // A history hit must not renew the bounded receipt TTL.
            assertNull(receipts.find("account", "host", "same"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun capacityEvictionStillAcknowledgesTheMessageRetainedInHistory() {
        val receipts = WatchChatReceipts(capacity = 1)
        val room = room(receipts)
        val session = room.participants.getValue("host").session
        val first = assertIs<RoomChatResult.Accepted>(room.acceptChat(session, "host", "first", "first", 0))
        assertIs<RoomChatResult.Accepted>(room.acceptChat(session, "host", "second", "second", 1))
        assertNull(receipts.find("account", "host", "first"))

        val retry = assertIs<RoomChatResult.Duplicate>(room.acceptChat(session, "host", "changed", "first", 2))

        assertEquals(first.message, retry.message)
        assertEquals(2, room.chatHistory.size)
        assertEquals(2L, room.nextChatId)
        assertEquals(
            2,
            room.memberships
                .getValue(memberKey("account", "host"))
                .chatSentAtMs.size,
        )
        assertNull(receipts.find("account", "host", "first"))
    }

    @Test
    fun historyFallbackKeepsDifferentSendersAndLegacyMessagesIndependent() {
        var now = 0L
        val room = room(WatchChatReceipts(clock = { now }))
        val host = room.participants.getValue("host").session
        assertIs<RoomChatResult.Accepted>(room.acceptChat(host, "host", "original", "same", now))
        now = 300_001L
        val guest = unusedSession()
        room.memberships[memberKey("other-account", "guest")] = Membership("guest", "other-account", ByteArray(32))
        room.participants["guest"] = Participant("guest", "Guest", 0, guest, now, "other-account")

        assertIs<RoomChatResult.Accepted>(room.acceptChat(guest, "guest", "other", "same", now))
        repeat(2) {
            assertIs<RoomChatResult.Accepted>(room.acceptChat(host, "host", "legacy", null, now))
        }

        assertEquals(4, room.chatHistory.size)
        assertEquals(4L, room.nextChatId)
    }

    @Test
    fun simultaneousRetriesInsertOnceAndOldSessionCannotSend() {
        val room = room()
        val session = room.participants.getValue("host").session
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results =
                pool
                    .invokeAll(List(8) { Callable { room.acceptChat(session, "host", "text", "same", 0) } })
                    .map { it.get() }
            assertEquals(1, results.count { it is RoomChatResult.Accepted })
            assertEquals(7, results.count { it is RoomChatResult.Duplicate })
            assertEquals(1, room.chatHistory.size)
            assertEquals(
                1,
                room.memberships
                    .getValue(memberKey("account", "host"))
                    .chatSentAtMs.size,
            )
            assertIs<RoomChatResult.StaleSession>(room.acceptChat(unusedSession(), "host", "text", "new", 5_000))
            assertEquals(1L, room.nextChatId)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun recycledClientIdDoesNotAcknowledgeAnotherAccountsHistory() {
        val room = room()
        val originalSession = room.participants.getValue("host").session
        assertIs<RoomChatResult.Accepted>(room.acceptChat(originalSession, "host", "first", "same", 0))
        val replacement = unusedSession()
        room.memberships[memberKey("replacement", "host")] = Membership("host", "replacement", ByteArray(32))
        room.participants["host"] = Participant("host", "Replacement", 0, replacement, 0, "replacement")

        val accepted = assertIs<RoomChatResult.Accepted>(room.acceptChat(replacement, "host", "second", "same", 1))
        assertEquals("second", accepted.message.text)
        assertEquals(2, room.chatHistory.size)
        assertEquals(2, room.chatHistorySenderKeys.size)
        assertIs<RoomChatResult.StaleSession>(room.acceptChat(originalSession, "host", "old", "third", 2))
    }

    private fun room(receipts: WatchChatReceipts = WatchChatReceipts()): Room =
        Room(
            code = "ABC234",
            creatorIp = "local",
            creatorAccountUserId = "account",
            hostId = "host",
            hostCapabilityDigest = ByteArray(32),
            timeline = Timeline("media", 0, 0),
            chatReceipts = receipts,
        ).apply {
            memberships[memberKey("account", "host")] = Membership("host", "account", ByteArray(32))
            participants["host"] = Participant("host", "Host", 0, unusedSession(), 0, "account")
        }

    private fun unusedSession(): WebSocketSession =
        Proxy.newProxyInstance(WebSocketSession::class.java.classLoader, arrayOf(WebSocketSession::class.java)) {
                _,
                method,
                _,
            ->
            error("Unexpected transport call: ${method.name}")
        } as WebSocketSession
}
