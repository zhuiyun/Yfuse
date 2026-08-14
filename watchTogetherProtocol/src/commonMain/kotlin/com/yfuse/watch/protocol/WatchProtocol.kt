package com.yfuse.watch.protocol

import kotlinx.serialization.Serializable

/**
 * The single wire contract used by both the Android client and the relay.
 *
 * Version 6 keeps the version 5 wire shape so the server can roll out first and negotiate with
 * installed v5 clients. Version 5 introduced an authenticated Yfuse account for every watch socket.
 * Version 4 deliberately broke compatibility with the old client-id-only reconnect flow:
 * [clientId] is public profile data, while [resumeCapability] and [hostCapability] are private,
 * room-scoped bearer capabilities that must never be copied into participant/chat payloads.
 */
@Serializable
data class WatchWireMessage(
    val type: String,
    val protocolVersion: Int? = null,
    val capabilities: List<String>? = null,
    val clientId: String? = null,
    val name: String? = null,
    val avatarId: Int? = null,
    val roomCode: String? = null,
    val resumeCapability: String? = null,
    val hostCapability: String? = null,
    val isHost: Boolean? = null,
    val canControl: Boolean? = null,
    val controlMode: String? = null,
    val moderator: Boolean? = null,
    val participantCount: Int? = null,
    val participants: List<WatchWireParticipant>? = null,
    val ready: Boolean? = null,
    val buffering: Boolean? = null,
    val mediaAvailable: Boolean? = null,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val mediaKey: String? = null,
    val positionMs: Long? = null,
    val paused: Boolean? = null,
    val rate: Float? = null,
    val seq: Long? = null,
    val anchorAtMs: Long? = null,
    val serverAtMs: Long? = null,
    val clientSentAtMs: Long? = null,
    val targetClientId: String? = null,
    val text: String? = null,
    val reaction: String? = null,
    val clientMessageId: String? = null,
    val chat: WatchWireChatMessage? = null,
    val chatHistory: List<WatchWireChatMessage>? = null,
    val playlist: List<WatchWirePlaylistEntry>? = null,
    val playlistRevision: Long? = null,
    val playlistEntry: WatchWirePlaylistEntry? = null,
    val playlistEntryId: String? = null,
    val playlistIndex: Int? = null,
    val message: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class WatchWireParticipant(
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val isHost: Boolean,
    val statusKnown: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val mediaAvailable: Boolean = true,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val canControl: Boolean = false,
    val isModerator: Boolean = false,
)

@Serializable
data class WatchWireChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
    val clientMessageId: String? = null,
)

/**
 * A room-scoped reference to media already known to Yfuse. Deliberately excludes URLs,
 * authorization tokens, and provider credentials so room snapshots are safe to broadcast.
 */
@Serializable
data class WatchWirePlaylistEntry(
    val id: String,
    val mediaKey: String,
    val title: String,
)

object WatchProtocol {
    const val VERSION = 6

    /**
     * Version 6 is deliberately wire-compatible with authenticated version 5. Version 4 predates
     * mandatory account bearers and must not be admitted as a nominally compatible downgrade.
     */
    const val MIN_SUPPORTED_VERSION = 5

    const val CAPABILITY_REACTIONS = "reactions"
    const val CAPABILITY_AUTHENTICATED_RESUME = "authenticatedResume"
    const val CAPABILITY_HOST_CREDENTIAL = "hostCapability"
    const val CAPABILITY_STRICT_VALIDATION = "strictWireValidation"
    const val CAPABILITY_ACCOUNT_AUTH = "accountAuth"
    const val CAPABILITY_VERSION_RANGE = "protocolVersionRange"
    const val CAPABILITY_ROOM_PLAYLIST = "roomPlaylist"

    val SERVER_CAPABILITIES =
        listOf(
            CAPABILITY_REACTIONS,
            CAPABILITY_AUTHENTICATED_RESUME,
            CAPABILITY_HOST_CREDENTIAL,
            CAPABILITY_STRICT_VALIDATION,
            CAPABILITY_ACCOUNT_AUTH,
            CAPABILITY_VERSION_RANGE,
            CAPABILITY_ROOM_PLAYLIST,
        )

    fun isSupportedVersion(version: Int?): Boolean = version != null && version in MIN_SUPPORTED_VERSION..VERSION

    const val ROOM_CODE_LENGTH = 6
    const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val AVATAR_COUNT = 8
    const val MAX_CLIENT_ID_BYTES = 128
    const val MAX_NAME_GRAPHEMES = 24
    const val MAX_NAME_BYTES = 128
    const val MAX_MEDIA_KEY_BYTES = 512
    const val MAX_CHAT_GRAPHEMES = 30
    const val MAX_CHAT_BYTES = 768
    const val MAX_CLIENT_MESSAGE_ID_BYTES = 128
    const val MAX_PLAYLIST_ENTRIES = 64
    const val MAX_PLAYLIST_ENTRY_ID_BYTES = 64
    const val MAX_PLAYLIST_TITLE_BYTES = 192
    const val MAX_PLAYLIST_TITLE_GRAPHEMES = 80
    const val CAPABILITY_LENGTH = 43
    const val MAX_TIMELINE_POSITION_MS = 30L * 24L * 60L * 60L * 1_000L
    const val MIN_PLAYBACK_RATE = 0.25f
    const val MAX_PLAYBACK_RATE = 4f
    const val MAX_LATENCY_MS = 10_000L
    const val MAX_SYNC_DRIFT_MS = 30_000L
    const val MIN_REASONABLE_EPOCH_MS = 1_577_836_800_000L // 2020-01-01 UTC
    const val MAX_FUTURE_CLOCK_SKEW_MS = 5L * 60L * 1_000L

    private val graphemeRegex = Regex("\\X")
    private val providerPrefixRegex = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    private val capabilityRegex = Regex("[A-Za-z0-9_-]{$CAPABILITY_LENGTH}")
    private val playlistEntryIdRegex = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

    val CLIENT_MESSAGE_TYPES =
        setOf(
            "hello",
            "sync",
            "requestControl",
            "grantControl",
            "denyControl",
            "setControlMode",
            "setModerator",
            "kickParticipant",
            "updateProfile",
            "playbackStatus",
            "chat",
            "reaction",
            "playlistAdd",
            "playlistUpdate",
            "playlistRemove",
            "playlistReorder",
            "ping",
        )

    fun isValidRoomCode(value: String?): Boolean =
        value != null &&
            value.length == ROOM_CODE_LENGTH &&
            value.all { it in ROOM_CODE_ALPHABET }

    fun isValidClientId(value: String?): Boolean =
        isBoundedOpaqueId(
            value = value,
            maxBytes = MAX_CLIENT_ID_BYTES,
        )

    fun isValidClientMessageId(value: String?): Boolean =
        isBoundedOpaqueId(
            value = value,
            maxBytes = MAX_CLIENT_MESSAGE_ID_BYTES,
        )

    fun isValidCapability(value: String?): Boolean = value != null && capabilityRegex.matches(value)

    /** Null/blank means "use the default profile name"; supplied content must be safe. */
    fun isValidOptionalName(value: String?): Boolean {
        if (value == null || value.isEmpty()) return true
        if (value.isBlank()) return false
        if (value != value.trim() || value.hasControlCharacters()) return false
        if (value.encodeToByteArray().size > MAX_NAME_BYTES) return false
        return graphemeRegex.findAll(value).count() <= MAX_NAME_GRAPHEMES
    }

    fun isValidAvatarId(value: Int?): Boolean = value == null || value in 0 until AVATAR_COUNT

    fun isValidMediaKey(value: String?): Boolean {
        if (value.isNullOrEmpty() || value != value.trim()) return false
        if (value.encodeToByteArray().size > MAX_MEDIA_KEY_BYTES) return false
        if (value.any { it.isWhitespace() } || value.hasControlCharacters()) return false
        val separator = value.indexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return false
        return providerPrefixRegex.matches(value.substring(0, separator))
    }

    fun isValidPlaylistEntryId(value: String?): Boolean =
        value != null &&
            value.encodeToByteArray().size <= MAX_PLAYLIST_ENTRY_ID_BYTES &&
            playlistEntryIdRegex.matches(value)

    fun isValidPlaylistTitle(value: String?): Boolean {
        if (value.isNullOrEmpty() || value.isBlank() || value != value.trim()) return false
        if (value.hasControlCharacters()) return false
        if (value.encodeToByteArray().size > MAX_PLAYLIST_TITLE_BYTES) return false
        return graphemeRegex.findAll(value).count() <= MAX_PLAYLIST_TITLE_GRAPHEMES
    }

    fun isValidPlaylistEntry(value: WatchWirePlaylistEntry?): Boolean =
        value != null &&
            isValidPlaylistEntryId(value.id) &&
            isValidMediaKey(value.mediaKey) &&
            isValidPlaylistTitle(value.title)

    fun isValidPlaylist(value: List<WatchWirePlaylistEntry>?): Boolean {
        if (value == null || value.size > MAX_PLAYLIST_ENTRIES) return false
        if (value.any { !isValidPlaylistEntry(it) }) return false
        return value.mapTo(hashSetOf()) { it.id }.size == value.size
    }

    fun isValidPlaylistRevision(value: Long?): Boolean = value != null && value >= 0L

    fun isValidTimeline(
        positionMs: Long?,
        paused: Boolean?,
        rate: Float?,
    ): Boolean =
        positionMs != null &&
            positionMs in 0L..MAX_TIMELINE_POSITION_MS &&
            paused != null &&
            rate != null &&
            rate.isFinite() &&
            rate in MIN_PLAYBACK_RATE..MAX_PLAYBACK_RATE

    fun isValidSequence(value: Long?): Boolean = value != null && value >= 0L

    fun isReasonableServerTime(
        value: Long?,
        nowEpochMs: Long,
    ): Boolean =
        value != null &&
            value >= MIN_REASONABLE_EPOCH_MS &&
            value <= nowEpochMs + MAX_FUTURE_CLOCK_SKEW_MS &&
            value >= nowEpochMs - MAX_TIMELINE_POSITION_MS

    fun isValidChat(value: String?): Boolean {
        if (value.isNullOrEmpty() || value != value.trim()) return false
        if (value.hasControlCharacters()) return false
        if (value.encodeToByteArray().size > MAX_CHAT_BYTES) return false
        return graphemeRegex.findAll(value).count() <= MAX_CHAT_GRAPHEMES
    }

    private fun isBoundedOpaqueId(
        value: String?,
        maxBytes: Int,
    ): Boolean {
        if (value.isNullOrEmpty() || value != value.trim()) return false
        if (value.encodeToByteArray().size > maxBytes) return false
        return value.none { it.code in 0x00..0x20 || it.code in 0x7F..0x9F }
    }

    private fun String.hasControlCharacters(): Boolean = any { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
}
