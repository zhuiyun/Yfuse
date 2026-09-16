package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSummaryTest {
    private fun item(
        id: String,
        downloaded: Long,
        total: Long,
        status: DownloadStatus = DownloadStatus.Downloading,
    ) = OfflineMedia(id, "server", id, id, downloadedBytes = downloaded, totalBytes = total, status = status)

    @Test fun weights_bytes_and_excludes_completed_history() {
        val result =
            summarizeDownloads(
                listOf(item("a", 50, 100), item("b", 0, 900), item("old", 9000, 9000, DownloadStatus.Completed)),
            )
        assertEquals(5, result.percent)
        assertEquals(2, result.active)
    }

    @Test fun unknown_sizes_are_indeterminate_and_large_sizes_do_not_overflow() {
        assertNull(summarizeDownloads(listOf(item("a", 5, 10), item("b", 0, 0))).percent)
        assertEquals(
            50,
            summarizeDownloads(
                listOf(
                    item("a", Long.MAX_VALUE / 2, Long.MAX_VALUE),
                    item(
                        "b",
                        Long.MAX_VALUE / 2,
                        Long.MAX_VALUE,
                    ),
                ),
            ).percent,
        )
    }

    @Test fun paused_and_failure_remain_visible_without_active_downloads() {
        val summary =
            summarizeDownloads(
                listOf(
                    item("a", 0, 10, DownloadStatus.Paused),
                    item("b", 0, 10, DownloadStatus.Failed).copy(lastFailureKind = DownloadFailureKind.Authentication),
                ),
            )
        assertTrue(summary.visible)
        assertEquals(0, summary.active)
        assertTrue(summary.detail.contains("重新登录"))
        assertFalse(summarizeDownloads(listOf(item("done", 10, 10, DownloadStatus.Completed))).visible)
    }
}
