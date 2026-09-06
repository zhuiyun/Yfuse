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

internal class Participant(
    val id: String,
    var name: String,
    var avatarId: Int,
    val session: WebSocketSession,
    val sessionGeneration: Long,
    val accountUserId: String,
    var authorizedHostEpoch: Long? = null,
    var statusKnown: Boolean = false,
    var ready: Boolean = false,
    var buffering: Boolean = false,
    var mediaAvailable: Boolean = true,
    var latencyMs: Long? = null,
    var syncDriftMs: Long? = null,
    /** Local media length reported by the member, so the host can spot a mismatched cut. */
    var durationMs: Long? = null,
)

/** Long-lived room membership. The digest is never sent or logged. */
internal class Membership(
    val clientId: String,
    val accountUserId: String,
    var resumeCapabilityDigest: ByteArray,
    var sessionGeneration: Long = 0L,
)

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
    var hostId: String,
    var hostCapabilityDigest: ByteArray,
    var initialHostCapability: String? = null,
    var hostEpoch: Long = 1L,
    var timeline: Timeline,
    var controlMode: ControlMode = ControlMode.HostOnly,
    val moderatorIds: MutableSet<String> = linkedSetOf(),
    val removedAccountUserIds: MutableSet<String> = linkedSetOf(),
    val memberships: LinkedHashMap<String, Membership> = linkedMapOf(),
    val participants: LinkedHashMap<String, Participant> = linkedMapOf(),
    val chatHistory: ArrayDeque<WatchWireChatMessage> = ArrayDeque(),
    var nextChatId: Long = 0L,
    val playlist: MutableList<WatchWirePlaylistEntry> = mutableListOf(),
    var playlistRevision: Long = 0L,
    var emptySinceMs: Long? = null,
    var hostAbsentSinceMs: Long? = null,
    /** Set while a coalesced presence-only room update is waiting to be sent. */
    var presenceBroadcastPending: Boolean = false,
) {
    fun isAuthorizedHost(participant: Participant): Boolean =
        participant.id == hostId && participant.authorizedHostEpoch == hostEpoch

    fun canControl(participant: Participant): Boolean =
        when (controlMode) {
            ControlMode.HostOnly -> isAuthorizedHost(participant)
            ControlMode.Everyone -> participants[participant.id] === participant
            ControlMode.Moderators -> isAuthorizedHost(participant) || participant.id in moderatorIds
        }

    fun canEditPlaylist(participant: Participant): Boolean =
        isAuthorizedHost(participant) || participant.id in moderatorIds

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
