package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol

internal const val MAX_REMOVED_ACCOUNT_IDS_PER_ROOM = 256

private val graphemeRegex = Regex("\\X")

internal fun normalizeName(raw: String?): String =
    raw
        .orEmpty()
        .replace('\r', ' ')
        .replace('\n', ' ')
        .filterNot { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
        .trim()
        .takeGraphemes(WatchProtocol.MAX_NAME_GRAPHEMES)
        .takeGraphemesWithinUtf8Bytes(WatchProtocol.MAX_NAME_BYTES)
        .ifBlank { "影友" }

internal fun normalizeAvatarId(
    raw: Int?,
    clientId: String,
): Int =
    raw?.takeIf { it in 0 until WatchProtocol.AVATAR_COUNT }
        ?: ((clientId.hashCode() and Int.MAX_VALUE) % WatchProtocol.AVATAR_COUNT)

internal fun normalizeChat(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidChat)

internal fun normalizeClientId(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidClientId)

internal fun rememberRemovedAccountUserId(
    removedAccountUserIds: MutableSet<String>,
    accountUserId: String,
    limit: Int = MAX_REMOVED_ACCOUNT_IDS_PER_ROOM,
): Boolean {
    require(limit > 0) { "limit must be positive" }
    if (accountUserId in removedAccountUserIds) return true
    if (removedAccountUserIds.size >= limit) return false
    removedAccountUserIds.add(accountUserId)
    return true
}

internal fun normalizeClientMessageId(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidClientMessageId)

private fun String.takeGraphemes(limit: Int): String =
    graphemeRegex.findAll(this).take(limit).joinToString(separator = "") { it.value }

private fun String.takeGraphemesWithinUtf8Bytes(limit: Int): String {
    var usedBytes = 0
    return buildString {
        for (match in graphemeRegex.findAll(this@takeGraphemesWithinUtf8Bytes)) {
            val bytes = match.value.toByteArray(Charsets.UTF_8).size
            if (usedBytes + bytes > limit) break
            append(match.value)
            usedBytes += bytes
        }
    }
}
