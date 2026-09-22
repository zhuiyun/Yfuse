package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineEnqueueBatchTest {
    @Test
    fun a_season_preserves_unrelated_rows_and_gives_every_episode_one_revision() {
        val retained = OfflineMedia("other#movie", "other", "movie", "Keep", downloadRevision = 12)
        val requests = (1..30).map { OfflineDownloadRequest("server", "episode$it", "Episode $it") }
        val batch = planOfflineEnqueueBatch(listOf(retained), requests, "content://selected/tree", 100)

        assertEquals(31, batch.items.size)
        assertEquals(retained, batch.items.first())
        assertEquals(30, batch.changed.size)
        assertTrue(batch.changed.all { it.item.downloadRevision == 1L && it.item.status == DownloadStatus.Queued })
        assertTrue(batch.changed.all { it.item.storageTreeUri == "content://selected/tree" })
    }

    @Test
    fun duplicate_ids_use_last_selection_without_incrementing_revision_twice() {
        val old = OfflineMedia("s#e", "s", "e", "Episode", downloadRevision = 3)
        val batch =
            planOfflineEnqueueBatch(
                listOf(old),
                listOf(
                    OfflineDownloadRequest("s", "e", "Episode", mediaSourceId = "first"),
                    OfflineDownloadRequest("s", "e", "Episode", mediaSourceId = "selected"),
                ),
                null,
                100,
            )

        assertEquals(1, batch.changed.size)
        assertEquals(4L, batch.items.single().downloadRevision)
        assertEquals("selected", batch.items.single().mediaSourceId)
        assertEquals(old, batch.changed.single().previous)
    }

    @Test
    fun requeue_keeps_verified_partial_but_a_variant_change_resets_its_bytes_and_storage() {
        val old =
            OfflineMedia(
                "s#e",
                "s",
                "e",
                "Episode",
                mediaSourceId = "original",
                storageTreeUri = "content://old/tree",
                downloadRevision = 7,
                downloadedBytes = 500,
                resumeValidator = "etag",
            )
        val same =
            planOfflineEnqueueBatch(
                listOf(old),
                listOf(OfflineDownloadRequest("s", "e", "Episode", mediaSourceId = "original")),
                "content://new/tree",
                100,
            )
        assertFalse(same.changed.single().sourceChanged)
        assertEquals(500L, same.items.single().downloadedBytes)
        assertEquals("etag", same.items.single().resumeValidator)
        assertEquals("content://old/tree", same.items.single().storageTreeUri)

        val replacement =
            planOfflineEnqueueBatch(
                listOf(old),
                listOf(OfflineDownloadRequest("s", "e", "Episode", mediaSourceId = "different")),
                "content://new/tree",
                100,
            )
        assertTrue(replacement.changed.single().sourceChanged)
        assertEquals(0L, replacement.items.single().downloadedBytes)
        assertEquals("content://new/tree", replacement.items.single().storageTreeUri)
        assertEquals(8L, replacement.items.single().downloadRevision)
    }
}
