package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadNotificationRateLimitTest {
    @Test
    fun parallel_progress_is_limited_but_pause_and_completion_are_immediate() {
        val limit = DownloadNotificationRateLimit()
        val tasks = listOf(OfflineMedia("a", "s", "a", "A", status = DownloadStatus.Downloading))
        assertTrue(limit.shouldPost(tasks, 0))
        for (time in 1L..999L) {
            assertFalse(limit.shouldPost(tasks.map { it.copy(downloadedBytes = time) }, time))
        }
        assertTrue(limit.shouldPost(tasks, 1_000))
        assertTrue(limit.shouldPost(tasks.map { it.copy(status = DownloadStatus.Paused) }, 1_001))
        assertTrue(limit.shouldPost(tasks.map { it.copy(status = DownloadStatus.Completed) }, 1_002))
    }
}
