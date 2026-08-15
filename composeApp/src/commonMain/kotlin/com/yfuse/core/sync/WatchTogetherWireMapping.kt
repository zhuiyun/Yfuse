package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireParticipant

internal fun WatchWireParticipant.toDomain(selfClientId: String): WatchParticipant? {
    if (!WatchProtocol.isValidClientId(clientId) ||
        !WatchProtocol.isValidOptionalName(name) ||
        name.isEmpty() ||
        !WatchProtocol.isValidAvatarId(avatarId) ||
        latencyMs != null &&
        latencyMs !in 0L..WatchProtocol.MAX_LATENCY_MS ||
        syncDriftMs != null &&
        syncDriftMs !in -WatchProtocol.MAX_SYNC_DRIFT_MS..WatchProtocol.MAX_SYNC_DRIFT_MS
    ) {
        return null
    }
    return WatchParticipant(
        clientId = clientId,
        name = name,
        avatarId = avatarId,
        isHost = isHost,
        isSelf = clientId == selfClientId,
        statusKnown = statusKnown,
        ready = ready,
        buffering = buffering,
        mediaAvailable = mediaAvailable,
        latencyMs = latencyMs,
        syncDriftMs = syncDriftMs,
        canControl = canControl,
        isModerator = isModerator,
    )
}

internal fun WatchWireChatMessage.toDomain(
    selfClientId: String,
    serverNowMs: Long,
): WatchChatMessage? {
    if (id < 0L ||
        !WatchProtocol.isValidClientId(clientId) ||
        !WatchProtocol.isValidOptionalName(name) ||
        name.isEmpty() ||
        !WatchProtocol.isValidAvatarId(avatarId) ||
        !WatchProtocol.isValidChat(text) ||
        !WatchProtocol.isReasonableServerTime(sentAtMs, serverNowMs) ||
        clientMessageId != null &&
        !WatchProtocol.isValidClientMessageId(clientMessageId)
    ) {
        return null
    }
    return WatchChatMessage(
        id = id,
        clientId = clientId,
        name = name,
        avatarId = avatarId,
        text = text,
        sentAtMs = sentAtMs,
        isMine = clientId == selfClientId,
        clientMessageId = clientMessageId,
        deliveryState = ChatDeliveryState.Sent,
    )
}
