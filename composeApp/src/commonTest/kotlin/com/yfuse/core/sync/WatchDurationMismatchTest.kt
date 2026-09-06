package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchDurationMismatchTest {
    private fun member(
        id: String,
        durationMs: Long?,
        self: Boolean = false,
    ) = WatchParticipant(
        clientId = id,
        name = id,
        avatarId = 1,
        isHost = self,
        isSelf = self,
        durationMs = durationMs,
    )

    private fun stateOf(vararg members: WatchParticipant) = WatchTogetherState(participants = members.toList())

    @Test
    fun no_warning_without_a_local_duration_or_a_material_difference() {
        assertNull(stateOf(member("me", null, self = true), member("a", 1L)).durationMismatchWarning())
        assertNull(
            stateOf(
                member("me", 5_400_000L, self = true),
                member("a", 5_400_000L + 30_000L),
                member("b", null),
            ).durationMismatchWarning(),
        )
    }

    @Test
    fun members_with_a_different_cut_are_named() {
        val warning =
            stateOf(
                member("me", 5_400_000L, self = true),
                member("alice", 5_100_000L),
                member("bob", 5_390_000L),
            ).durationMismatchWarning()
        assertTrue(warning!!.contains("alice"))
        assertFalse(warning.contains("bob"))
    }
}
