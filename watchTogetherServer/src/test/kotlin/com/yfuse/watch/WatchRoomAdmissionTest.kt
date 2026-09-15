package com.yfuse.watch

import io.ktor.websocket.WebSocketSession
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchRoomAdmissionTest {
    @Test
    fun removing_an_account_clears_offline_moderator_roles_before_client_ids_can_be_reused() {
        val room =
            Room(
                code = "ABC234",
                creatorIp = "127.0.0.1",
                creatorAccountUserId = "host-account",
                hostId = "host-phone",
                hostCapabilityDigest = ByteArray(32),
                timeline = Timeline("tmdb:351", 0L, 0L),
                controlMode = ControlMode.Moderators,
            )
        val host = room.addDevice("host-phone", "host-account")
        host.authorizedHostEpoch = room.hostEpoch
        val target = room.addDevice("guest-phone", "guest-account")
        room.addDevice("guest-tv", "guest-account", moderator = true)
        room.addDevice("guest-tablet", "guest-account", online = false, moderator = true)
        val retainedModerator = room.addDevice("other-tablet", "other-account", moderator = true)

        val removed = assertNotNull(room.removeMemberDevices(host, target, maxRemovalRecords = 256))

        assertEquals(setOf("guest-phone", "guest-tv"), removed.map(Participant::id).toSet())
        assertEquals(setOf("host-phone", "other-tablet"), room.participants.keys)
        assertEquals(setOf("host-phone", "other-tablet"), room.memberships.keys)
        assertEquals(setOf("other-tablet"), room.moderatorIds)
        assertTrue(room.isAuthorizedHost(host))
        assertTrue(room.canControl(retainedModerator))
        assertEquals(RoomJoinRejection.Removed, room.admissionFor("new-guest-device", "guest-account"))

        // A fresh account may claim an unbound public id, but never the former member's role.
        assertNull(room.admissionFor("guest-tablet", "fresh-account"))
        val recycledId = room.addDevice("guest-tablet", "fresh-account")
        assertFalse(room.canControl(recycledId))
        assertFalse(room.canEditPlaylist(recycledId))
    }

    private fun Room.admissionFor(
        clientId: String,
        accountUserId: String,
    ): RoomJoinRejection? =
        validateJoin(
            clientId = clientId,
            accountUserId = accountUserId,
            resumeCapability = null,
            hostCapability = null,
            creatingRoom = false,
            maxParticipants = 12,
            maxMemberships = 64,
        )

    private fun Room.addDevice(
        clientId: String,
        accountUserId: String,
        online: Boolean = true,
        moderator: Boolean = false,
    ): Participant {
        val member = newMembership(code, clientId, accountUserId).first
        memberships[clientId] = member
        val participant =
            Participant(
                id = clientId,
                name = clientId,
                avatarId = 0,
                session = unusedSession,
                sessionGeneration = member.sessionGeneration,
                accountUserId = accountUserId,
            )
        if (online) participants[clientId] = participant
        if (moderator) moderatorIds.add(clientId)
        return participant
    }

    /** Room admission does not touch transport; fail immediately if the test starts doing so. */
    private val unusedSession =
        Proxy.newProxyInstance(
            WebSocketSession::class.java.classLoader,
            arrayOf(WebSocketSession::class.java),
        ) { _, method, _ -> error("Unexpected WebSocket operation: ${method.name}") } as WebSocketSession
}
