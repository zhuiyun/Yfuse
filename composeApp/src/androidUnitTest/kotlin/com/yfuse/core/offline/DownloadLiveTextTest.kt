package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadLiveTextTest {
    private fun media(
        id: String,
        status: DownloadStatus,
        downloaded: Long = 0L,
        total: Long = 0L,
    ) = OfflineMedia(
        id = id,
        serverId = "server",
        itemId = id,
        title = "S1 E$id",
        downloadedBytes = downloaded,
        totalBytes = total,
        status = status,
    )

    @Test
    fun a_transfer_without_a_length_shows_no_percentage_from_start_to_finish() {
        val batch = listOf(media("1", DownloadStatus.Downloading, downloaded = 300_000_000L))
        val progress = assertNotNull(downloadLiveProgress(batch))

        assertTrue(progress.sizeUnknown)
        assertEquals("正在读取文件大小", downloadLiveText(progress, summarizeDownloads(batch)))
    }

    @Test
    fun a_measured_transfer_shows_its_own_percentage() {
        val batch = listOf(media("1", DownloadStatus.Downloading, downloaded = 50L, total = 200L))
        val progress = assertNotNull(downloadLiveProgress(batch))

        assertFalse(progress.sizeUnknown)
        assertEquals("25%", downloadLiveText(progress, summarizeDownloads(batch)))
    }

    @Test
    fun a_longer_batch_names_the_episode_whose_size_is_unknown() {
        val batch =
            listOf(
                media("1", DownloadStatus.Downloading, downloaded = 1_024L),
                media("2", DownloadStatus.Queued),
            )
        val progress = assertNotNull(downloadLiveProgress(batch))

        assertEquals("S1 E1 · 正在读取文件大小", downloadLiveText(progress, summarizeDownloads(batch)))
    }

    @Test
    fun one_unmeasured_download_makes_the_whole_bar_indeterminate() {
        val batch =
            listOf(
                media("1", DownloadStatus.Downloading, downloaded = 50L, total = 200L),
                media("2", DownloadStatus.Downloading, downloaded = 1_024L),
            )
        val progress = assertNotNull(downloadLiveProgress(batch))

        assertTrue(progress.sizeUnknown)
        // The episode named in the text does have a size, so its own figure is still true.
        assertEquals("S1 E1 · 25%", downloadLiveText(progress, summarizeDownloads(batch)))
    }

    @Test
    fun a_stopped_batch_falls_back_to_the_queue_summary() {
        val batch = listOf(media("1", DownloadStatus.Paused, downloaded = 50L))
        val progress = assertNotNull(downloadLiveProgress(batch))
        val summary = summarizeDownloads(batch)

        assertFalse(progress.sizeUnknown)
        assertEquals(summary.detail, downloadLiveText(progress, summary))
    }
}
