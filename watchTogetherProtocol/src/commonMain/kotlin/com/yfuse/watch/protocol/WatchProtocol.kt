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
    /**
     * 一起看: whether a member is ready to play. 手机遥控, under
     * [WatchProtocol.CAPABILITY_REMOTE_PAIRING]: false on `remoteJoined` when the television asks
     * before it takes this phone's keys, and the phone waits for `remoteAdmitted`.
     */
    val ready: Boolean? = null,
    val buffering: Boolean? = null,
    val mediaAvailable: Boolean? = null,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val durationMs: Long? = null,
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
    /** 手机遥控: the account session of the television a phone asks to control. */
    val remoteSessionId: String? = null,
    /** 手机遥控: a [RemoteControlKey.wireName]. */
    val remoteKey: String? = null,
    /**
     * 手机遥控, under [WatchProtocol.CAPABILITY_REMOTE_PAIRING]: which phone — its own id for this
     * install, the same on every connection, so a television can remember it. A phone names itself
     * with it (and its [name]) on `remoteJoin`; the relay stamps it on everything the television
     * hears about or from that phone, and the television names it in `remoteRelease`.
     */
    val remoteDeviceId: String? = null,
    /**
     * 用手机登录, under [WatchProtocol.CAPABILITY_REMOTE_SIGN_IN]: the server a phone hands a
     * television that asked for one — see [RemoteSignInServer].
     */
    val signInServer: RemoteSignInServer? = null,
    /**
     * Room snapshots (`welcome`, `roomUpdate`), under [WatchProtocol.CAPABILITY_ROOM_REVISION]: grows
     * with every snapshot the relay takes of the room, in the order it took them. Broadcasts to one
     * member can overtake each other on the way, so a client keeps the newest revision it has seen
     * on a connection and ignores the room state of an older snapshot. Absent means accept.
     */
    val roomRevision: Long? = null,
    /**
     * `error`: true when the same request can succeed later without the client changing anything —
     * a rate limit or a full service — so a client waits and retries instead of giving up the room.
     * An older relay leaves it out; [WatchProtocol.isRetryableError] answers from the code then.
     */
    val retryable: Boolean? = null,
    /**
     * Under [WatchProtocol.CAPABILITY_REAUTHENTICATE]: when the socket's account access lapses, on
     * the relay's clock (compare with [serverAtMs] of the same message). `reauthenticated` carries it.
     */
    val authExpiresAtMs: Long? = null,
    /** Secrets a message carries: never printed, see [WatchWireCredential]. */
    val credential: WatchWireCredential? = null,
)

/**
 * Secrets on the wire, kept apart so that printing a message never prints them.
 *
 * `reauthenticate` carries a fresh account [accessToken] for a socket that is already open, under
 * [WatchProtocol.CAPABILITY_REAUTHENTICATE]. Under [WatchProtocol.CAPABILITY_REMOTE_PAIRING_TOKEN],
 * `remoteAdmitted` hands a phone the [pairingToken] its television's admission was bound to, and the
 * phone presents it on every later `remoteJoin` of that television.
 */
@Serializable
data class WatchWireCredential(
    val accessToken: String? = null,
    val pairingToken: String? = null,
) {
    override fun toString(): String = "WatchWireCredential(redacted)"
}

/**
 * 手机遥控's keys by their wire names. A phone sends one per press; the television replays it as a
 * key event in its own window, so focus moves the way a physical remote would move it.
 */
enum class RemoteControlKey(
    val wireName: String,
) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    Center("center"),
    Back("back"),
    Home("home"),
    PlayPause("playPause"),
    ;

    companion object {
        fun fromWireName(value: String?): RemoteControlKey? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * 用手机登录: one saved Emby or Jellyfin server as a phone hands it to a television of the same
 * account. `remoteSignInOffer` carries what the television shows before anything secret leaves the
 * phone — [kind], [serverName], [baseUrl] and [userName]. `remoteSignInSend` carries the same four
 * with [userId] and [accessToken], the session the phone signed in with, which the television keeps
 * as its own sign-in would have. Never a password: the app keeps none.
 */
@Serializable
data class RemoteSignInServer(
    /** `Emby` or `Jellyfin` ([WatchProtocol.REMOTE_SIGN_IN_KINDS]); Plex keeps its own PIN sign-in. */
    val kind: String,
    val serverName: String,
    /** The server's identity address, the one its saved id derives from. */
    val baseUrl: String,
    val userName: String,
    val userId: String? = null,
    val accessToken: String? = null,
) {
    /** The server without its session: what the television shows, and what the relay compares. */
    val summary: RemoteSignInServer
        get() = copy(userId = null, accessToken = null)

    /** Printed without its session or address, so no log line or crash report can carry them. */
    override fun toString(): String = "RemoteSignInServer(kind=$kind)"
}

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
    val durationMs: Long? = null,
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

    /**
     * 手机遥控: the relay pairs a phone with a television of the same account and forwards keys and
     * text from the phone to the television. The `remote*` message types exist only under it.
     */
    const val CAPABILITY_REMOTE_CONTROL = "remoteControl"

    /**
     * 手机遥控 with named phones: the television hears which phone connected, left or sent each key
     * ([WatchWireMessage.remoteDeviceId]), and can let one go with `remoteRelease` — which is how it
     * asks before a phone it does not know may press anything. A relay without it tells the
     * television only that some phone is on.
     *
     * A television that asks also says so as it hosts, with this capability among the
     * [WatchWireMessage.capabilities] of its `remoteHost`. Each phone that joins it is then answered
     * with [WatchWireMessage.ready] false, and waits: the television's `remoteAdmit` for that phone
     * reaches that phone alone, as `remoteAdmitted`. A television that says nothing — one from before
     * it asked — is joined as before, and a phone on a relay without this never waits at all.
     */
    const val CAPABILITY_REMOTE_PAIRING = "remotePairing"

    /**
     * 用手机登录: a television waiting on its 添加服务器 asks for a server (`remoteSignInAsk`, only
     * from the socket it hosts 手机遥控 on). A phone of the same account puts one before it
     * (`remoteSignInOffer`, without its session), and the television shows that; only once the
     * phone's user confirms does the session follow (`remoteSignInSend`) — once, after which the ask
     * is spent. The television ends the ask with `remoteSignInEnd`, whose
     * [WatchWireMessage.errorCode] the phone hears in `remoteSignInEnded` as how it went. A relay
     * without it refuses each of these as an unknown type, and a television does not offer
     * 用手机登录 on one.
     */
    const val CAPABILITY_REMOTE_SIGN_IN = "remoteSignIn"

    /**
     * Room snapshots carry [WatchWireMessage.roomRevision]. A client that lists this among the
     * [WatchWireMessage.capabilities] of its `hello` also gets `roomUpdate`s without the playlist
     * (and its revision) whenever the playlist has not changed since the last snapshot sent to it.
     * A client that does not list it gets the whole playlist every time, as before.
     */
    const val CAPABILITY_ROOM_REVISION = "roomRevision"

    /**
     * A socket stays open across account token refreshes. A client that lists this among the
     * capabilities of its first message (`hello`, `remoteHost` or `remoteJoin`) hears
     * `reauthenticated` with [WatchWireMessage.authExpiresAtMs], and sends `reauthenticate` with a
     * fresh token of the same account session before that time; each one is answered with
     * `reauthenticated` again, or an `error` whose code starts with `reauth_`. Without a valid one
     * the socket is closed at expiry with `account_auth_expired`, as it always was. If the relay
     * finds the socket's token revoked or replaced it sends `error` `reauth_required` first and
     * waits briefly for a fresh token. A client that says nothing keeps the old behaviour.
     */
    const val CAPABILITY_REAUTHENTICATE = "reauthenticate"

    /**
     * 手机遥控: the relay enforces a television's admission itself, and binds it to a token. A phone
     * that lists this among the capabilities of its `remoteJoin` receives a
     * [WatchWireCredential.pairingToken] with `remoteAdmitted`, and presents it on later joins of
     * that television. A phone that claims an id with a token on record but cannot present it is
     * named to the television as an unknown phone, so the television asks again.
     */
    const val CAPABILITY_REMOTE_PAIRING_TOKEN = "remotePairingToken"

    val SERVER_CAPABILITIES =
        listOf(
            CAPABILITY_REACTIONS,
            CAPABILITY_AUTHENTICATED_RESUME,
            CAPABILITY_HOST_CREDENTIAL,
            CAPABILITY_STRICT_VALIDATION,
            CAPABILITY_ACCOUNT_AUTH,
            CAPABILITY_VERSION_RANGE,
            CAPABILITY_ROOM_PLAYLIST,
            CAPABILITY_REMOTE_CONTROL,
            CAPABILITY_REMOTE_PAIRING,
            CAPABILITY_REMOTE_SIGN_IN,
            CAPABILITY_ROOM_REVISION,
            CAPABILITY_REAUTHENTICATE,
            CAPABILITY_REMOTE_PAIRING_TOKEN,
        )

    /**
     * `error` codes that describe a passing condition rather than a wrong request: pacing, a full
     * service or room, a store that is briefly unavailable. Stable, so clients may switch on them.
     */
    val RETRYABLE_ERROR_CODES =
        setOf(
            "join_rate_limited",
            "room_full",
            "room_service_full",
            "room_ip_limit",
            "chat_rate_limited",
            "chat_muted",
            "sync_rate_limited",
            "playlist_rate_limited",
            "control_rate_limited",
            "reauth_unavailable",
            "reauth_required",
            "reauth_rate_limited",
            "client_id_in_use",
            "remote_not_admitted",
            "remote_service_full",
            "remote_busy",
            "remote_rate_limited",
            "remote_unavailable",
        )

    /** [WatchWireMessage.retryable] when present, else what the code is known to mean. */
    fun isRetryableError(message: WatchWireMessage): Boolean =
        message.retryable ?: (message.errorCode in RETRYABLE_ERROR_CODES)

    fun isRetryableErrorCode(code: String?): Boolean = code in RETRYABLE_ERROR_CODES

    /** An account bearer as `reauthenticate` may carry it: the shape the `Authorization` header allows. */
    const val MAX_ACCESS_TOKEN_BYTES = 512

    /** A pairing token is a capability: 32 random bytes, base64url without padding. */
    fun isValidPairingToken(value: String?): Boolean = isValidCapability(value)

    fun isValidAccessToken(value: String?): Boolean =
        isBoundedOpaqueId(
            value = value,
            maxBytes = MAX_ACCESS_TOKEN_BYTES,
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
    const val MAX_REMOTE_SESSION_ID_BYTES = 128
    const val MAX_REMOTE_DEVICE_ID_BYTES = 128

    /** What a television may say it does as it hosts: a handful of capability names, no more. */
    const val MAX_DECLARED_CAPABILITIES = 8
    const val MAX_DECLARED_CAPABILITY_CHARS = 64

    /**
     * Starts the id the relay makes up for a phone that named none — an app from before
     * [CAPABILITY_REMOTE_PAIRING]. It lasts that one connection, so a television never trusts it
     * for good; a phone may not name itself with it.
     */
    const val REMOTE_EPHEMERAL_DEVICE_PREFIX = "~"

    /**
     * The `errorCode` a television puts on `remoteRelease` for a phone it turned away, as opposed
     * to one it disconnected; the phone hears `remoteDisconnected` with the same code.
     */
    const val REMOTE_REFUSED_CODE = "remote_refused"

    /** A search box's worth: the whole of the phone's field is resent on every change. */
    const val MAX_REMOTE_TEXT_BYTES = 256
    const val MAX_REMOTE_TEXT_GRAPHEMES = 64

    /** What 用手机登录 hands over, by [RemoteSignInServer.kind]; Plex keeps its own PIN sign-in. */
    val REMOTE_SIGN_IN_KINDS = setOf("Emby", "Jellyfin")

    /** As long as a saved server's address may be. */
    const val MAX_REMOTE_SIGN_IN_URL_BYTES = 2_048

    /** A server's display name or a user name: shown on the television, so bounded like a name. */
    const val MAX_REMOTE_SIGN_IN_LABEL_BYTES = 512
    const val MAX_REMOTE_SIGN_IN_LABEL_GRAPHEMES = 128
    const val MAX_REMOTE_SIGN_IN_USER_ID_BYTES = 256

    /** As long as a saved server's session may be. */
    const val MAX_REMOTE_SIGN_IN_TOKEN_BYTES = 4_096

    /** How long a television asks for a server before giving up; the relay forgets an ask this old. */
    const val REMOTE_SIGN_IN_ASK_MS = 5L * 60L * 1_000L

    /** `remoteSignInEnd` from a television that stopped asking before it had anything to save. */
    const val REMOTE_SIGN_IN_CANCELLED_CODE = "remote_sign_in_cancelled"

    /** `remoteSignInEnd` from a television whose server did not take the session it was sent. */
    const val REMOTE_SIGN_IN_FAILED_CODE = "remote_sign_in_failed"

    private val graphemeRegex = Regex("\\X")

    /**
     * `<provider>:<value>` or `<provider>:<value>/s<season>e<episode>`.
     *
     * Clients resolve a key by pasting its value into a request path on their own media
     * server, so the value is a closed alphabet: provider ids (`603`, `tt0133093`, a hex
     * Emby id) never need `/`, `?`, `#`, `\` or `..`, and admitting them would let a room
     * steer a member's client at an arbitrary path with that member's token.
     */
    private val mediaKeyRegex =
        Regex("[A-Za-z][A-Za-z0-9_-]{0,31}:[A-Za-z0-9][A-Za-z0-9._-]*(?:/s[0-9]{1,4}e[0-9]{1,5})?")
    private val capabilityRegex = Regex("[A-Za-z0-9_-]{$CAPABILITY_LENGTH}")
    private val capabilityNameRegex = Regex("[A-Za-z][A-Za-z0-9]{0,${MAX_DECLARED_CAPABILITY_CHARS - 1}}")
    private val playlistEntryIdRegex = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")

    /**
     * 手机遥控, under [CAPABILITY_REMOTE_CONTROL]: a television sends `remoteHost`; a phone sends
     * `remoteJoin` with the television's [WatchWireMessage.remoteSessionId], then `remoteKey` and
     * `remoteText`. The relay answers `remoteHosting` / `remoteJoined`, tells the television
     * `remoteConnected` / `remoteDisconnected` as phones come and go, and tells a phone
     * `remoteDisconnected` when its television leaves. Under [CAPABILITY_REMOTE_PAIRING] the
     * television also sends `remoteRelease` to let one phone go, which then hears
     * `remoteDisconnected` too, and `remoteAdmit` to let one in, which then hears `remoteAdmitted`.
     * Under [CAPABILITY_REMOTE_SIGN_IN] the television sends `remoteSignInAsk` and
     * `remoteSignInEnd`, and a phone `remoteSignInOffer` and `remoteSignInSend`. Such a socket never
     * joins a room.
     */
    val REMOTE_CLIENT_MESSAGE_TYPES =
        setOf(
            "remoteHost",
            "remoteJoin",
            "remoteKey",
            "remoteText",
            "remoteRelease",
            "remoteAdmit",
            "remoteSignInAsk",
            "remoteSignInEnd",
            "remoteSignInOffer",
            "remoteSignInSend",
        )

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
            "reauthenticate",
        ) + REMOTE_CLIENT_MESSAGE_TYPES

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
        if (".." in value) return false
        return mediaKeyRegex.matches(value)
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

    /** An account session id as the handoff inbox lists it; the relay never trusts more than its shape. */
    fun isValidRemoteSessionId(value: String?): Boolean =
        isBoundedOpaqueId(
            value = value,
            maxBytes = MAX_REMOTE_SESSION_ID_BYTES,
        )

    fun isValidRemoteKey(value: String?): Boolean = RemoteControlKey.fromWireName(value) != null

    /** A phone's id as the relay passes it on: opaque and bounded, the phone's own or made up. */
    fun isValidRemoteDeviceId(value: String?): Boolean =
        isBoundedOpaqueId(
            value = value,
            maxBytes = MAX_REMOTE_DEVICE_ID_BYTES,
        )

    /**
     * An id a phone may name itself with, and a television may remember for good: a valid one that
     * is not the relay's stand-in for a phone that named none.
     */
    fun isStableRemoteDeviceId(value: String?): Boolean =
        isValidRemoteDeviceId(value) && !value.orEmpty().startsWith(REMOTE_EPHEMERAL_DEVICE_PREFIX)

    /**
     * What a television may list as it hosts ([CAPABILITY_REMOTE_PAIRING] says it asks before a
     * phone may press anything): a few names of the relay's own shape. One the relay does not know
     * is passed over rather than refused, so a later television can say more to an older relay.
     */
    fun isValidDeclaredCapabilities(value: List<String>?): Boolean =
        value != null &&
            value.size <= MAX_DECLARED_CAPABILITIES &&
            value.all { capabilityNameRegex.matches(it) }

    /**
     * The phone's whole field, so a lost or repeated message cannot garble what the television
     * shows. Empty clears it, and the spaces of a half-typed query are kept as typed.
     */
    fun isValidRemoteText(value: String?): Boolean {
        if (value == null || value.hasControlCharacters()) return false
        if (value.encodeToByteArray().size > MAX_REMOTE_TEXT_BYTES) return false
        return graphemeRegex.findAll(value).count() <= MAX_REMOTE_TEXT_GRAPHEMES
    }

    /** `remoteSignInOffer`'s server: one a television can show, and nothing secret with it. */
    fun isValidRemoteSignInOffer(value: RemoteSignInServer?): Boolean =
        value != null &&
            value.userId == null &&
            value.accessToken == null &&
            isValidRemoteSignInSummary(value)

    /** `remoteSignInSend`'s server: the one offered, with the user and session it was signed in with. */
    fun isValidRemoteSignInCredentials(value: RemoteSignInServer?): Boolean =
        value != null &&
            isValidRemoteSignInSummary(value) &&
            isBoundedOpaqueId(value.userId, MAX_REMOTE_SIGN_IN_USER_ID_BYTES) &&
            isBoundedOpaqueId(value.accessToken, MAX_REMOTE_SIGN_IN_TOKEN_BYTES)

    /**
     * A server's address as a saved server keeps it: http or https, a host, maybe a port and a base
     * path. No user info, query or fragment, and no `..` to walk a path with.
     */
    fun isValidRemoteSignInUrl(value: String?): Boolean {
        if (value.isNullOrEmpty() || value.encodeToByteArray().size > MAX_REMOTE_SIGN_IN_URL_BYTES) return false
        if (value.any { it.isWhitespace() || it in "\\?#" } || value.hasControlCharacters() || ".." in value) {
            return false
        }
        val rest =
            when {
                value.startsWith("https://") -> value.removePrefix("https://")
                value.startsWith("http://") -> value.removePrefix("http://")
                else -> return false
            }
        val authority = rest.substringBefore('/')
        return authority.isNotEmpty() && '@' !in authority
    }

    private fun isValidRemoteSignInSummary(value: RemoteSignInServer): Boolean =
        value.kind in REMOTE_SIGN_IN_KINDS &&
            isValidRemoteSignInLabel(value.serverName) &&
            isValidRemoteSignInLabel(value.userName) &&
            isValidRemoteSignInUrl(value.baseUrl)

    private fun isValidRemoteSignInLabel(value: String): Boolean {
        if (value.isBlank() || value != value.trim() || value.hasControlCharacters()) return false
        if (value.encodeToByteArray().size > MAX_REMOTE_SIGN_IN_LABEL_BYTES) return false
        return graphemeRegex.findAll(value).count() <= MAX_REMOTE_SIGN_IN_LABEL_GRAPHEMES
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
