package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadRemovalCopyTest {
    @Test
    fun one_download_names_the_title_and_the_space_it_frees() {
        val (title, message) =
            downloadRemovalCopy(listOf(media("a", "沙丘", downloaded = 1536L * 1024L * 1024L)))

        assertEquals("删除下载？", title)
        assertEquals("“沙丘”的离线文件（1.5 GB）会从这台设备删除，不能撤销。", message)
    }

    @Test
    fun a_selection_states_how_many_go_and_what_they_hold_together() {
        val (title, message) =
            downloadRemovalCopy(
                listOf(
                    media("a", "第 1 集", downloaded = 300L * 1024L * 1024L),
                    media("b", "第 2 集", downloaded = 200L * 1024L * 1024L, status = DownloadStatus.Paused),
                    media("c", "第 3 集", downloaded = 0L, status = DownloadStatus.Queued),
                ),
            )

        assertEquals("删除 3 项下载？", title)
        assertEquals("选中的 3 项下载共占用 500 MB，删除后需要重新下载，不能撤销。", message)
    }

    @Test
    fun nothing_on_disk_leaves_the_space_out_rather_than_promising_zero() {
        assertEquals(
            "“待下载”的下载任务会被移除，不能撤销。",
            downloadRemovalCopy(listOf(media("a", "待下载", downloaded = 0L, status = DownloadStatus.Queued))).second,
        )
        assertEquals(
            "选中的 2 项下载任务会被移除，不能撤销。",
            downloadRemovalCopy(
                listOf(
                    media("a", "一", downloaded = 0L, status = DownloadStatus.Queued),
                    media("b", "二", downloaded = 0L, status = DownloadStatus.WaitingForWifi),
                ),
            ).second,
        )
    }

    private fun media(
        id: String,
        title: String,
        downloaded: Long,
        status: DownloadStatus = DownloadStatus.Completed,
    ) = OfflineMedia(
        id = id,
        serverId = "server",
        itemId = "item-$id",
        title = title,
        downloadedBytes = downloaded,
        totalBytes = downloaded,
        status = status,
    )
}
