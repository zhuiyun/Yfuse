package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarLocalStoreTest {
    @Test
    fun snapshot_is_fresh_only_when_schedule_and_resources_are_both_current() {
        val now = 1_000_000L
        val fresh =
            CalendarLocalSnapshot(
                days = emptyList(),
                syncState =
                    CalendarSyncState(
                        scope = "calendar",
                        scheduleSyncedAtEpochMs = now - 1_000L,
                        resourcesSyncedAtEpochMs = now - 1_000L,
                        lastAttemptAtEpochMs = now - 1_000L,
                    ),
            )
        val staleResources =
            fresh.copy(
                syncState = fresh.syncState?.copy(resourcesSyncedAtEpochMs = now - 120_000L),
            )

        assertTrue(localCalendarSnapshotIsFresh(fresh, now))
        assertFalse(localCalendarSnapshotIsFresh(staleResources, now))
    }
}
