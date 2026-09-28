package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadOrderingTest {
    @Test
    fun newest_additions_stay_first_after_progress_and_state_changes() {
        val first = media("first", 1)
        val second = media("second", 2)
        val progressing = first.copy(updatedAtEpochMs = 100, downloadedBytes = 50, status = DownloadStatus.Downloading)
        assertEquals(listOf("second", "first"), ids(listOf(progressing, second), DownloadSort.Added))
        assertEquals(
            listOf("second", "first"),
            ids(listOf(second, progressing.copy(status = DownloadStatus.Completed)), DownloadSort.Added),
        )
    }

    @Test
    fun equal_names_and_unknown_sizes_use_added_order_instead_of_refresh_order() {
        val first = media("first", 1).copy(title = "Same", downloadedBytes = 50)
        val second = media("second", 2).copy(title = "same", downloadedBytes = 1)
        for (sort in listOf(DownloadSort.Name, DownloadSort.Size)) {
            assertEquals(listOf("second", "first"), ids(listOf(first, second), sort))
            assertEquals(listOf("second", "first"), ids(listOf(second, first.copy(downloadedBytes = 100)), sort))
        }
    }

    @Test
    fun filtering_keeps_the_same_relative_order() {
        val items = listOf(media("a", 1), media("b", 2).copy(status = DownloadStatus.Completed), media("c", 3))
        val active = filterAndSortDownloads(items, DownloadFilter.Active, DownloadSort.Added)
        assertEquals(listOf("c", "a"), active.map { it.id })
    }

    private fun ids(
        items: List<OfflineMedia>,
        sort: DownloadSort,
    ) = filterAndSortDownloads(items, DownloadFilter.All, sort).map { it.id }

    private fun media(
        id: String,
        order: Long,
    ) = OfflineMedia(id, "s", id, id, addedOrder = order)
}
