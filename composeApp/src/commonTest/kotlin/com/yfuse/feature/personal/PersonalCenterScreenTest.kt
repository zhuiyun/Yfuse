package com.yfuse.feature.personal

import com.yfuse.core.sync.SyncMutationKind
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalCenterScreenTest {
    @Test
    fun favorite_conflict_reads_as_collected_state_not_raw_booleans() {
        assertEquals(
            "本机：已收藏 · 服务器：未收藏",
            syncConflictValueCopy(SyncMutationKind.Favorite, desired = true, serverValue = false),
        )
    }

    @Test
    fun played_conflict_reads_as_watched_state_not_raw_booleans() {
        assertEquals(
            "本机：未看 · 服务器：已看",
            syncConflictValueCopy(SyncMutationKind.Played, desired = false, serverValue = true),
        )
    }
}
