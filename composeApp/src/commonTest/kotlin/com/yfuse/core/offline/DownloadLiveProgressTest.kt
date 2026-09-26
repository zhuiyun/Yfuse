package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadLiveProgressTest {
    private fun item(
        id: String,
        status: DownloadStatus,
        downloaded: Long = 0,
        total: Long = 100,
    ) = OfflineMedia(id, "server", id, "S1 E$id", downloadedBytes = downloaded, totalBytes = total, status = status)

    @Test
    fun episodes_fill_the_bar_in_the_order_it_fills() {
        val progress =
            downloadLiveProgress(
                listOf(
                    item("3", DownloadStatus.Queued),
                    item("1", DownloadStatus.Completed, 100),
                    item("2", DownloadStatus.Downloading, 40),
                    item("4", DownloadStatus.Failed),
                ),
            )!!
        assertEquals(
            listOf(
                DownloadLiveState.Done,
                DownloadLiveState.Downloading,
                DownloadLiveState.Waiting,
                DownloadLiveState.Failed,
            ),
            progress.segments.map { it.state },
        )
        assertTrue(progress.segments.all { it.length == DOWNLOAD_LIVE_UNITS })
        assertEquals(DOWNLOAD_LIVE_UNITS + 40, progress.progress)
        assertEquals(4 * DOWNLOAD_LIVE_UNITS, progress.max)
        assertEquals(1, progress.done)
        assertEquals(4, progress.total)
        assertEquals(35, progress.percent)
        assertEquals(listOf("2"), progress.downloading.map { it.id })
        assertFalse(progress.paused)
    }

    @Test
    fun a_long_season_folds_into_one_stretch_per_state() {
        val batch =
            List(9) { item("d$it", DownloadStatus.Completed, 100) } +
                item("now", DownloadStatus.Downloading, 50) +
                List(14) { item("w$it", DownloadStatus.Queued) }
        val progress = downloadLiveProgress(batch)!!
        assertEquals(
            listOf(
                DownloadLiveSegment(9 * DOWNLOAD_LIVE_UNITS, DownloadLiveState.Done),
                DownloadLiveSegment(DOWNLOAD_LIVE_UNITS, DownloadLiveState.Downloading),
                DownloadLiveSegment(14 * DOWNLOAD_LIVE_UNITS, DownloadLiveState.Waiting),
            ),
            progress.segments,
        )
        assertTrue(progress.segments.size <= DOWNLOAD_LIVE_MAX_SEGMENTS)
        assertEquals(9 * DOWNLOAD_LIVE_UNITS + 50, progress.progress)
    }

    @Test
    fun unknown_sizes_count_by_episode_and_a_stopped_queue_reads_as_paused() {
        val progress =
            downloadLiveProgress(
                listOf(
                    item("1", DownloadStatus.Completed, 100),
                    item("2", DownloadStatus.Paused, 0, 0),
                ),
            )!!
        assertEquals(50, progress.percent)
        assertTrue(progress.paused)
        assertNull(downloadLiveProgress(emptyList()))
    }

    @Test
    fun the_batch_keeps_finished_episodes_until_the_queue_is_idle() {
        val first =
            nextDownloadLiveBatch(
                emptyList(),
                listOf(item("1", DownloadStatus.Downloading), item("2", DownloadStatus.Queued)),
            )
        assertEquals(listOf("1", "2"), first)
        val finishing =
            nextDownloadLiveBatch(
                first,
                listOf(
                    item("0", DownloadStatus.Completed),
                    item("1", DownloadStatus.Completed),
                    item("2", DownloadStatus.Downloading),
                ),
            )
        assertEquals(listOf("1", "2"), finishing)
        val added =
            nextDownloadLiveBatch(
                finishing,
                listOf(item("3", DownloadStatus.Queued), item("2", DownloadStatus.Downloading)),
            )
        // 1 was removed from the queue altogether; 3 joins the batch at the end.
        assertEquals(listOf("2", "3"), added)
        assertEquals(
            emptyList(),
            nextDownloadLiveBatch(
                added,
                listOf(item("2", DownloadStatus.Completed), item("3", DownloadStatus.Completed)),
            ),
        )
    }
}
