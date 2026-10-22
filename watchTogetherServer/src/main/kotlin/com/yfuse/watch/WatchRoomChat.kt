package com.yfuse.watch

import com.yfuse.watch.protocol.WatchWireChatMessage
import io.ktor.websocket.WebSocketSession

private const val MAX_CHAT_HISTORY = 50

/** Bounded retry receipts, independent of the small history sent to newly joined members. */
internal class WatchChatReceipts(
    private val capacity: Int = 512,
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Key(
        val accountUserId: String,
        val clientId: String,
        val messageId: String,
    )

    private data class Receipt(
        val message: WatchWireChatMessage,
        val recordedAtMs: Long,
    )

    private val receipts = linkedMapOf<Key, Receipt>()

    init {
        require(capacity > 0 && ttlMs > 0L)
    }

    // All access is under the owning room's lock. Retries do not extend receipt lifetime.
    fun find(
        accountUserId: String,
        clientId: String,
        messageId: String?,
    ): WatchWireChatMessage? {
        prune(clock())
        return messageId?.let { receipts[Key(accountUserId, clientId, it)]?.message }
    }

    fun record(
        accountUserId: String,
        message: WatchWireChatMessage,
    ) {
        val messageId = message.clientMessageId ?: return
        val now = clock()
        prune(now)
        receipts[Key(accountUserId, message.clientId, messageId)] = Receipt(message, now)
        while (receipts.size > capacity) receipts.remove(receipts.keys.first())
    }

    private fun prune(nowMs: Long) {
        val iterator = receipts.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value.recordedAtMs < ttlMs) break
            iterator.remove()
        }
    }
}

internal sealed interface RoomChatResult {
    data class Accepted(
        val message: WatchWireChatMessage,
    ) : RoomChatResult

    data class Duplicate(
        val message: WatchWireChatMessage,
    ) : RoomChatResult

    data class Refused(
        val admission: ChatAdmission,
    ) : RoomChatResult

    data object StaleSession : RoomChatResult
}

/** Session identity, duplicate detection, pacing and insertion form one room transaction. */
internal fun Room.acceptChat(
    session: WebSocketSession,
    clientId: String,
    text: String,
    clientMessageId: String?,
    nowMs: Long,
): RoomChatResult =
    synchronized(this) {
        val sender =
            participants[clientId]?.takeIf { it.session === session }
                ?: return@synchronized RoomChatResult.StaleSession
        val member = memberships[sender.memberKey] ?: return@synchronized RoomChatResult.StaleSession
        chatReceipts.find(member.accountUserId, clientId, clientMessageId)?.let {
            return@synchronized RoomChatResult.Duplicate(it)
        }
        // A receipt can expire or be evicted while its message is still visible in history.
        // Keep that wire identity stable without extending the bounded receipt's lifetime.
        if (clientMessageId != null) {
            chatHistory
                .firstOrNull {
                    it.clientId == clientId &&
                        it.clientMessageId == clientMessageId &&
                        chatHistorySenderKeys[it.id] == sender.memberKey
                }?.let { return@synchronized RoomChatResult.Duplicate(it) }
        }
        val admission =
            member.admitChat(
                nowMs = nowMs,
                maxPerWindow = 3,
                windowMs = 3_000L,
                muteAfterRejections = 5,
                rejectionWindowMs = 30_000L,
                muteMs = 60_000L,
            )
        if (admission != ChatAdmission.Allowed) return@synchronized RoomChatResult.Refused(admission)
        val message =
            WatchWireChatMessage(
                id = ++nextChatId,
                clientId = clientId,
                name = sender.name,
                avatarId = sender.avatarId,
                text = text,
                sentAtMs = nowMs,
                clientMessageId = clientMessageId,
            )
        chatHistory.addLast(message)
        chatHistorySenderKeys[message.id] = sender.memberKey
        while (chatHistory.size > MAX_CHAT_HISTORY) {
            chatHistorySenderKeys.remove(chatHistory.removeFirst().id)
        }
        chatReceipts.record(member.accountUserId, message)
        RoomChatResult.Accepted(message)
    }
