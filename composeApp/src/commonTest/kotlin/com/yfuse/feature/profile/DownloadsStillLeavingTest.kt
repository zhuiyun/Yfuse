package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadsStillLeavingTest {
    @Test
    fun rows_the_manager_is_still_deleting_stay_hidden_after_the_toast() {
        // Mid-delete: the first row is marked 已暂停 while its files go, the second has not been
        // reached yet, and the third was resumed from the notification before the delete got to it.
        val leaving = mapOf("a" to null, "b" to "网络中断", "c" to "网络中断")
        val items =
            listOf(
                media("a", DownloadStatus.Paused),
                media("b", DownloadStatus.Failed, error = "网络中断"),
                media("c", DownloadStatus.Queued),
                media("d", DownloadStatus.Completed),
            )

        assertEquals(leaving, downloadsStillLeaving(leaving, items))
    }

    @Test
    fun a_row_is_forgotten_once_it_has_left_the_index() {
        val leaving = mapOf("a" to null, "b" to null)

        assertEquals(
            mapOf("b" to null),
            downloadsStillLeaving(leaving, listOf(media("b", DownloadStatus.Paused))),
        )
        assertTrue(downloadsStillLeaving(leaving, emptyList()).isEmpty())
    }

    @Test
    fun a_row_back_with_an_error_it_did_not_have_is_a_failed_delete_and_shows_again() {
        val leaving = mapOf("a" to null, "b" to "网络中断")
        val items =
            listOf(
                media("a", DownloadStatus.Paused, error = "无法删除全部离线文件，请重试"),
                media("b", DownloadStatus.Paused, error = "无法删除全部离线文件，请重试"),
            )

        assertTrue(downloadsStillLeaving(leaving, items).isEmpty())
    }

    private fun media(
        id: String,
        status: DownloadStatus,
        error: String? = null,
    ) = OfflineMedia(
        id = id,
        serverId = "server",
        itemId = "item-$id",
        title = id,
        status = status,
        error = error,
    )
}
