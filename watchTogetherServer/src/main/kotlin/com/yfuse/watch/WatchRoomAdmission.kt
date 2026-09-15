package com.yfuse.watch

/** Admission runs under the room lock, before a connection can replace any participant. */
internal enum class RoomJoinRejection(
    val message: String,
    val code: String,
    val closeReason: String?,
) {
    Removed("你已被房主移出当前房间", "removed_by_host", "removed by host"),
    IdentityConflict("客户端身份已绑定其他账号", "account_membership_conflict", "account membership conflict"),
    ResumeInvalid("重连凭据无效", "resume_auth_failed", "credential rejected"),
    HostInvalid("主持人凭据无效", "host_auth_failed", "credential rejected"),
    Full("房间人数已满", "room_full", null),
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
    if (accountUserId in removedAccountUserIds || clientId in removedClientIds) return RoomJoinRejection.Removed
    val membership = memberships[clientId]
    if (membership != null && membership.accountUserId != accountUserId) return RoomJoinRejection.IdentityConflict
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
    val removedIds = if (ownDevice) removedClientIds else removedAccountUserIds
    val removedId = if (ownDevice) target.id else target.accountUserId
    if (removedId !in removedIds && removedIds.size >= maxRemovalRecords) return null
    removedIds.add(removedId)
    val removed =
        participants.values.filter {
            if (ownDevice) it.id == target.id else it.accountUserId == target.accountUserId
        }
    removed.forEach {
        participants.remove(it.id)
        moderatorIds.remove(it.id)
    }
    memberships.entries.removeAll { (_, member) ->
        val removing = if (ownDevice) member.clientId == target.id else member.accountUserId == target.accountUserId
        if (removing) moderatorIds.remove(member.clientId)
        removing
    }
    return removed
}
