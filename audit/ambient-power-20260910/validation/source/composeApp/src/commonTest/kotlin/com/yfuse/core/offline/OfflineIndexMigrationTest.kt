package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineIndexMigrationTest {
    @Test
    fun stale_legacy_rows_do_not_return_after_imported_database_is_emptied() {
        val legacy = listOf(OfflineMedia("s#e", "s", "e", "Episode"))
        var database = emptyList<OfflineMedia>()
        var imported = false
        assertFailsWith<IllegalStateException> {
            loadOfflineIndexOnce(
                load = { database },
                migrationComplete = { imported },
                readLegacy = { legacy },
                migrateAtomically = {
                    database = it
                    imported = true
                },
                discardLegacy = { error("process stopped before preferences cleanup") },
            )
        }
        assertEquals(legacy, database)
        database = emptyList() // The user deletes every imported download.
        val recovered =
            loadOfflineIndexOnce(
                load = { database },
                migrationComplete = { imported },
                readLegacy = { error("A completed migration must not read the residual key") },
                migrateAtomically = { error("Must not import twice") },
                discardLegacy = {},
            )
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun failed_batch_write_keeps_discovered_episodes_eligible_for_the_next_refresh() {
        var remembered = false
        assertFailsWith<IllegalStateException> {
            commitOfflineAutoDiscovery(
                enqueue = { error("disk full") },
                rememberEpisodes = { remembered = true },
            )
        }
        assertFalse(remembered)
        var queued = false
        commitOfflineAutoDiscovery(
            enqueue = { queued = true },
            rememberEpisodes = {
                assertTrue(queued)
                remembered = true
            },
        )
        assertTrue(remembered)
    }
}
