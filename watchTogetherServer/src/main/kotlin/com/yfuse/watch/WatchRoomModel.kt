package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.websocket.WebSocketSession
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class Timeline(
    val mediaKey: String,
    val anchorPositionMs: Long,
    val anchorAtServerMs: Long,
    val rate: Float = 1f,
    val paused: Boolean = true,
    val seq: Long = 0L,
)

/**
 * A room member's identity: the account and the device together. A client id alone is public —
 * every member sees the others' in the participant list — so keying memberships, moderators and
 * removals by it let one account hold another's id in a room before it arrived.
 */
internal fun memberKey(
    accountUserId: String,
    clientId: String,
): String = "$accountUserId\u0000$clientId"

internal class Participant(
    val id: String,
    var name: String,
    var avatarId: Int,
    val session: WebSocketSession,
    val sessionGeneration: Long,
    val accountUserId: String,
    var authorizedHostEpoch: Long? = null,
    /** The client listed [WatchProtocol.CAPABILITY_ROOM_REVISION]: it may get updates without the playlist. */
    val roomDeltas: Boolean = false,
    /** The playlist revision last sent to this member in a snapshot; -1 before any. */
    var playlistRevisionSent: Long = -1L,
    var statusKnown: Boolean = false,
    var ready: Boolean = false,
    var buffering: Boolean = false,
    var mediaAvailable: Boolean = true,
    var latencyMs: Long? = null,
    var syncDriftMs: Long? = null,
    /** Local media length reported by the member, so the host can spot a mismatched cut. */
    var durationMs: Long? = null,
) {
    val memberKey: String get() = memberKey(accountUserId, id)
}

/** Long-lived room membership. The digest is never sent or logged. */
internal class Membership(
    val clientId: String,
    val accountUserId: String,
    var resumeCapabilityDigest: ByteArray,
    var sessionGeneration: Long = 0L,
    /** When this device first entered the room; decides who may inherit an absent host's seat. */
    val admittedAtMs: Long = System.currentTimeMillis(),
) {
    val key: String get() = memberKey(accountUserId, clientId)

    /** Whether the member was already in the room when the host left at [hostLeftAtMs]. */
    fun predates(hostLeftAtMs: Long?): Boolean = hostLeftAtMs == null || admittedAtMs <= hostLeftAtMs

    /** Chat pacing lives on the membership, so a reconnect does not hand out a fresh window. */
    val chatSentAtMs: ArrayDeque<Long> = ArrayDeque()
    val chatRejectedAtMs: ArrayDeque<Long> = ArrayDeque()
    var chatMutedUntilMs: Long = 0L
}

internal sealed interface ChatAdmission {
    data object Allowed : ChatAdmission

    data object RateLimited : ChatAdmission

    /** Repeated pacing violations pause the member's chat for a while. */
    data class Muted(
        val untilMs: Long,
    ) : ChatAdmission
}

/**
 * Admits or refuses one chat message from this membership. Must be called under the room lock.
 * A member who keeps hitting the pace limit is muted for [muteMs]; that decision is the
 * server's, not the sender's client, so a modified client gains nothing by retrying.
 */
internal fun Membership.admitChat(
    nowMs: Long,
    maxPerWindow: Int,
    windowMs: Long,
    muteAfterRejections: Int,
    rejectionWindowMs: Long,
    muteMs: Long,
): ChatAdmission {
    if (nowMs < chatMutedUntilMs) return ChatAdmission.Muted(chatMutedUntilMs)
    while (chatSentAtMs.isNotEmpty() && nowMs - chatSentAtMs.first() >= windowMs) chatSentAtMs.removeFirst()
    if (chatSentAtMs.size < maxPerWindow) {
        chatSentAtMs.addLast(nowMs)
        return ChatAdmission.Allowed
    }
    while (chatRejectedAtMs.isNotEmpty() && nowMs - chatRejectedAtMs.first() >= rejectionWindowMs) {
        chatRejectedAtMs.removeFirst()
    }
    chatRejectedAtMs.addLast(nowMs)
    if (chatRejectedAtMs.size >= muteAfterRejections) {
        chatRejectedAtMs.clear()
        chatMutedUntilMs = nowMs + muteMs
        return ChatAdmission.Muted(chatMutedUntilMs)
    }
    return ChatAdmission.RateLimited
}

internal enum class ControlMode(
    val wireValue: String,
) {
    HostOnly("hostOnly"),
    Everyone("everyone"),
    Moderators("moderators"),
    ;

    companion object {
        fun fromWire(value: String?): ControlMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Mutable room aggregate. Callers synchronize on the room before reading or writing it. */
internal class Room(
    val code: String,
    val creatorIp: String,
    /** Account that created the room; bounds how many live rooms one account can hold. */
    val creatorAccountUserId: String,
    var hostId: String,
    var hostCapabilityDigest: ByteArray,
    var initialHostCapability: String? = null,
    var hostEpoch: Long = 1L,
    var timeline: Timeline,
    var controlMode: ControlMode = ControlMode.HostOnly,
    /** Member keys ([memberKey]); a role belongs to an account's device, not to a public id. */
    val moderatorKeys: MutableSet<String> = linkedSetOf(),
    val removedAccountUserIds: MutableSet<String> = linkedSetOf(),
    /** Member keys of single devices the host removed from its own account. */
    val removedMemberKeys: MutableSet<String> = linkedSetOf(),
    /** Keyed by [memberKey]: the same public client id under another account is another member. */
    val memberships: LinkedHashMap<String, Membership> = linkedMapOf(),
    /** Online members by client id, which is how the wire names them; unique while online. */
    val participants: LinkedHashMap<String, Participant> = linkedMapOf(),
    val chatHistory: ArrayDeque<WatchWireChatMessage> = ArrayDeque(),
    var nextChatId: Long = 0L,
    val playlist: MutableList<WatchWirePlaylistEntry> = mutableListOf(),
    var playlistRevision: Long = 0L,
    var emptySinceMs: Long? = null,
    var hostAbsentSinceMs: Long? = null,
    /** Set while a coalesced presence-only room update is waiting to be sent. */
    var presenceBroadcastPending: Boolean = false,
    /** Grows with every snapshot taken of the room; see [WatchWireMessage.roomRevision]. */
    var roomRevision: Long = 0L,
) {
    /** A room update is wanted; see [RoomUpdateBroadcaster]. */
    var updateDirty: Boolean = false
    var updateFlushing: Boolean = false
    var updateFlushScheduled: Boolean = false
    var lastUpdateFlushAtMs: Long? = null
    val outbound: RoomOutboundBudget = RoomOutboundBudget()

    /** A paced-out `sync` is waiting to go out as one trailing timeline broadcast. */
    var trailingSyncPending: Boolean = false

    fun nextRoomRevision(): Long {
        roomRevision = if (roomRevision == Long.MAX_VALUE) roomRevision else roomRevision + 1L
        return roomRevision
    }

    fun isAuthorizedHost(participant: Participant): Boolean =
        participant.id == hostId && participant.authorizedHostEpoch == hostEpoch

    fun isModerator(participant: Participant): Boolean = participant.memberKey in moderatorKeys

    fun canControl(participant: Participant): Boolean =
        when (controlMode) {
            ControlMode.HostOnly -> isAuthorizedHost(participant)
            ControlMode.Everyone -> participants[participant.id] === participant
            ControlMode.Moderators -> isAuthorizedHost(participant) || isModerator(participant)
        }

    fun canEditPlaylist(participant: Participant): Boolean = isAuthorizedHost(participant) || isModerator(participant)

    fun transferHostTo(participant: Participant): String {
        val capability = newCapability()
        hostId = participant.id
        hostEpoch++
        hostCapabilityDigest = capabilityDigest(code, participant.id, CapabilityKind.Host, capability)
        participant.authorizedHostEpoch = hostEpoch
        hostAbsentSinceMs = null
        return capability
    }

    fun hostGraceExpired(
        graceMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val since = hostAbsentSinceMs ?: return true
        return nowMs - since >= graceMs
    }
}

internal enum class CapabilityKind(
    val domain: String,
) {
    Resume("resume-v1"),
    Host("host-v1"),
}

private val capabilityRandom = SecureRandom()

internal fun newCapability(): String =
    ByteArray(32)
        .also(capabilityRandom::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

internal fun capabilityDigest(
    roomCode: String,
    clientId: String,
    kind: CapabilityKind,
    capability: String,
): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(
        "${kind.domain}\u0000$roomCode\u0000$clientId\u0000$capability".toByteArray(Charsets.UTF_8),
    )

internal fun capabilityMatches(
    roomCode: String,
    clientId: String,
    kind: CapabilityKind,
    candidate: String?,
    expectedDigest: ByteArray,
): Boolean {
    if (!WatchProtocol.isValidCapability(candidate)) return false
    return MessageDigest.isEqual(
        expectedDigest,
        capabilityDigest(roomCode, clientId, kind, candidate!!),
    )
}

internal fun newMembership(
    roomCode: String,
    clientId: String,
    accountUserId: String,
): Pair<Membership, String> {
    val capability = newCapability()
    return Membership(
        clientId = clientId,
        accountUserId = accountUserId,
        resumeCapabilityDigest =
            capabilityDigest(roomCode, clientId, CapabilityKind.Resume, capability),
    ) to capability
}
