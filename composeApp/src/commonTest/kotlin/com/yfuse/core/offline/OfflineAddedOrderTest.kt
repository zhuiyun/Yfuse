package com.yfuse.core.offline

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineAddedOrderTest {
    @Test
    fun progress_pause_completion_and_retry_never_reorder_parallel_tasks() {
        var items = ensureOfflineAddedOrder(listOf(media("a", 10), media("b", 20), media("c", 30)))
        val order = items.map { it.id }
        repeat(100) { tick ->
            val id = order[tick % order.size]
            items =
                ensureOfflineAddedOrder(
                    items.map { item ->
                        if (item.id != id) {
                            item
                        } else {
                            item.copy(
                                downloadedBytes = tick.toLong() * 512_000L,
                                updatedAtEpochMs = 1_000L + tick,
                                status = DownloadStatus.entries[tick % DownloadStatus.entries.size],
                            )
                        }
                    },
                )
            assertEquals(order, items.map { it.id })
        }
    }

    @Test
    fun legacy_order_is_frozen_and_survives_serialization_and_different_database_order() {
        val legacy = listOf(media("c", 30), media("a", 20), media("b", 10))
        val migrated = ensureOfflineAddedOrder(legacy)
        assertEquals(listOf("c", "a", "b"), migrated.map { it.id })
        assertTrue(migrated.all { it.addedOrder > 0 })
        val disk = Json.encodeToString(migrated)
        val loaded = Json.decodeFromString<List<OfflineMedia>>(disk).reversed()
        assertEquals(migrated, ensureOfflineAddedOrder(loaded))
    }

    @Test
    fun new_batch_goes_first_in_episode_order_without_moving_existing_tasks() {
        val current = ensureOfflineAddedOrder(listOf(media("old-a", 20), media("old-b", 10)))
        val next = ensureOfflineAddedOrder(current + listOf(media("episode-2", 30), media("episode-10", 30)))
        assertEquals(listOf("episode-2", "episode-10", "old-a", "old-b"), next.map { it.id })
        assertEquals(current, next.takeLast(2))
        assertEquals(next, ensureOfflineAddedOrder(next.reversed()))
    }

    @Test
    fun old_payload_without_added_order_can_still_be_read() {
        val legacy =
            Json.decodeFromString<OfflineMedia>(
                """{"id":"a","serverId":"s","itemId":"a","title":"A","updatedAtEpochMs":42}""",
            )
        assertEquals(0L, legacy.addedOrder)
        assertEquals(42L, legacy.updatedAtEpochMs)
        assertTrue(ensureOfflineAddedOrder(listOf(legacy)).single().addedOrder > 0)
    }

    private fun media(
        id: String,
        updated: Long,
    ) = OfflineMedia(id, "s", id, id, updatedAtEpochMs = updated)
}
