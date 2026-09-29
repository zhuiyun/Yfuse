package com.yfuse.feature.profile

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadSelectionActionsTest {
    private val paused = mutableListOf<List<String>>()
    private val resumed = mutableListOf<List<String>>()
    private var removed = 0

    private fun actions(vararg selection: OfflineMedia) =
        downloadSelectionActions(
            selection = selection.toList(),
            onPause = { paused += it },
            onResume = { resumed += it },
            onRemove = { removed++ },
        )

    @Test
    fun eachButtonTakesOnlyTheRowsItsSwipeWouldTake() {
        val bar =
            actions(
                media("moving", DownloadStatus.Downloading),
                media("waiting", DownloadStatus.WaitingForWifi),
                media("stopped", DownloadStatus.Paused),
                media("broken", DownloadStatus.Failed),
                media("done", DownloadStatus.Completed),
            )
        assertEquals(listOf("暂停", "继续/重试", "删除"), bar.map { it.label })
        bar.forEach { it.onClick() }

        assertEquals(listOf(listOf("moving", "waiting")), paused)
        assertEquals(listOf(listOf("stopped", "broken")), resumed)
        assertEquals(1, removed)
    }

    @Test
    fun aButtonWithNothingSelectedToActOnStaysButDims() {
        val finished = actions(media("done", DownloadStatus.Completed))
        assertEquals(listOf(false, false, true), finished.map { it.enabled })

        val nothing = actions()
        assertEquals(3, nothing.size)
        assertTrue(nothing.none { it.enabled })
    }

    @Test
    fun onlyTheDeleteReadsAsDestructive() {
        val bar = actions(media("a", DownloadStatus.Queued))
        assertEquals(listOf(false, false, true), bar.map { it.destructive })
        assertEquals("删除所选下载", bar.last().onClickLabel)
    }

    private fun media(
        id: String,
        status: DownloadStatus,
    ) = OfflineMedia(id = id, serverId = "server", itemId = "item-$id", title = id, status = status)
}
