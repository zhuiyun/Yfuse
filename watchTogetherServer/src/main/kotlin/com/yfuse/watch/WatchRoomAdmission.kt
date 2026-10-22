package com.yfuse.watch

/**
 * Admission runs under the room lock, before a connection can replace any participant. A
 * rejection with a [closeReason] ends the socket; [retryLater] ones close as "try again later",
 * since nothing the client sent was wrong.
 */
internal enum class RoomJoinRejection(
    val message: String,
    val code: String,
    val closeReason: String?,
    val retryLater: Boolean = false,
) {
    Removed("你已被房主移出当前房间", "removed_by_host", "removed by host"),

    /** Another account's device is online under the same public id; it may leave. */
    ClientIdInUse("这个设备标识正被其他账号使用，请稍后再试", "client_id_in_use", "client id in use", retryLater = true),
    ResumeInvalid("重连凭据无效", "resume_auth_failed", "credential rejected"),
    HostInvalid("主持人凭据无效", "host_auth_failed", "credential rejected"),

    /**
     * Closed like the others: a socket kept open after a full room could go on probing codes
     * with further `hello`s.
     */
    Full("房间人数已满", "room_full", "room full", retryLater = true),
}

internal fun Room.validateJoin(
    clientId: String,
    accountUserId: String,
    resumeCapability: String?,
    hostCapability: String?,
    creatingRoom: Boolean,
    maxParticipants: Int,
    maxMemberships: Int,
): RoomJoinRejection? {
    val key = memberKey(accountUserId, clientId)
    if (accountUserId in removedAccountUserIds || key in removedMemberKeys) return RoomJoinRejection.Removed
    // The wire names online members by client id, so two accounts cannot be online under one.
    val online = participants[clientId]
    if (online != null && online.accountUserId != accountUserId) return RoomJoinRejection.ClientIdInUse
    val membership = memberships[key]
    if (membership != null &&
        !capabilityMatches(code, clientId, CapabilityKind.Resume, resumeCapability, membership.resumeCapabilityDigest)
    ) {
        return RoomJoinRejection.ResumeInvalid
    }
    if (membership == null && resumeCapability != null) return RoomJoinRejection.ResumeInvalid
    if (clientId == hostId) {
        val initialCreator =
            creatingRoom && membership == null && initialHostCapability != null && creatorAccountUserId == accountUserId
        if (!initialCreator &&
            (
                membership == null ||
                    !capabilityMatches(code, clientId, CapabilityKind.Host, hostCapability, hostCapabilityDigest)
            )
        ) {
            return RoomJoinRejection.HostInvalid
        }
    }
    if (membership == null && memberships.size >= maxMemberships) return RoomJoinRejection.Full
    // Only an online connection replacement reuses a seat. A disconnected member needs a free seat.
    if (clientId !in participants && participants.size >= maxParticipants) return RoomJoinRejection.Full
    return null
}

/** Other-account removal stays account-wide; removing one's own tablet never bans the host. */
internal fun Room.removeMemberDevices(
    actor: Participant,
    target: Participant,
    maxRemovalRecords: Int,
): List<Participant>? {
    val ownDevice = actor.accountUserId == target.accountUserId
    val removedIds = if (ownDevice) removedMemberKeys else removedAccountUserIds
    val removedId = if (ownDevice) target.memberKey else target.accountUserId
    if (removedId !in removedIds && removedIds.size >= maxRemovalRecords) return null
    removedIds.add(removedId)
    val removed =
        participants.values.filter {
            if (ownDevice) it.memberKey == target.memberKey else it.accountUserId == target.accountUserId
        }
    removed.forEach {
        participants.remove(it.id)
        moderatorKeys.remove(it.memberKey)
    }
    memberships.entries.removeAll { (key, member) ->
        val removing = if (ownDevice) key == target.memberKey else member.accountUserId == target.accountUserId
        if (removing) moderatorKeys.remove(key)
        removing
    }
    return removed
}
