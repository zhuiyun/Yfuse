package com.yfuse.app

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CapsuleFlashTest {
    private fun item(
        id: String,
        status: DownloadStatus,
    ) = OfflineMedia(id = id, serverId = "s", itemId = id, title = "片 $id", status = status)

    @Test
    fun aDownloadSeenRunningAndNowDoneIsAnnouncedByName() {
        val before = mapOf("a" to DownloadStatus.Downloading, "b" to DownloadStatus.Queued)
        val after = listOf(item("a", DownloadStatus.Completed), item("b", DownloadStatus.Downloading))
        assertEquals("已下载 · 片 a", completedDownloadFlash(before, after))
    }

    @Test
    fun severalAtOnceAreCountedAndOldOnesSayNothing() {
        val before =
            mapOf(
                "a" to DownloadStatus.Downloading,
                "b" to DownloadStatus.Paused,
                "c" to DownloadStatus.Completed,
            )
        val after = listOf("a", "b", "c").map { item(it, DownloadStatus.Completed) }
        assertEquals("已下载 2 项", completedDownloadFlash(before, after))
        assertNull(completedDownloadFlash(emptyMap(), listOf(item("d", DownloadStatus.Completed))))
    }

    @Test
    fun anEndedCastNamesTheDeviceWhenItHadOne() {
        assertEquals("投屏已结束 · 客厅电视", castEndedFlash("客厅电视"))
        assertEquals("投屏已结束", castEndedFlash(null))
    }
}
