package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadSwipeTest {
    @Test
    fun aRightSwipePausesWhatMovesOrWaitsAndResumesWhatStopped() {
        assertEquals(DownloadSwipe.Pause, downloadSwipe(DownloadStatus.Downloading))
        assertEquals(DownloadSwipe.Pause, downloadSwipe(DownloadStatus.Queued))
        assertEquals(DownloadSwipe.Pause, downloadSwipe(DownloadStatus.WaitingForWifi))
        assertEquals(DownloadSwipe.Resume, downloadSwipe(DownloadStatus.Paused))
        assertEquals(DownloadSwipe.Retry, downloadSwipe(DownloadStatus.Failed))
    }

    @Test
    fun aFinishedDownloadHasNothingToPause() {
        assertNull(downloadSwipe(DownloadStatus.Completed))
    }

    @Test
    fun theSwipeSaysWhatTheRowWouldSay() {
        assertEquals(listOf("暂停", "继续", "重试"), DownloadSwipe.entries.map { it.label })
    }
}
