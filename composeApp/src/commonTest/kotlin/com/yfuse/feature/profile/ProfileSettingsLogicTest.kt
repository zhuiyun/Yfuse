package com.yfuse.feature.profile

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileSettingsLogicTest {

    @Test
    fun root_playback_summary_uses_user_goals_instead_of_backend_names() {
        assertEquals("兼容优先 · 硬件优先", playbackSettingsSummary(PlayerEngine.Exo, DecoderMode.Hardware))
        assertEquals("格式优先 · 自动选择", playbackSettingsSummary(PlayerEngine.Mpv, DecoderMode.Auto))
        assertEquals("原生内核 · 软件兼容", playbackSettingsSummary(PlayerEngine.Mdk, DecoderMode.Software))

        PlayerEngine.selectable.forEach { engine ->
            val summary = playbackSettingsSummary(engine, DecoderMode.Auto)
            assertFalse(summary.contains(engine.label, ignoreCase = true))
        }
    }

    @Test
    fun every_real_engine_and_decoder_has_clear_non_empty_copy() {
        (PlayerEngine.selectable.map { it.playbackOptionCopy() } +
            DecoderMode.entries.map { it.playbackOptionCopy() }).forEach { copy ->
            assertTrue(copy.label.isNotBlank())
            assertTrue(copy.summary.isNotBlank())
            assertTrue(copy.description.isNotBlank())
        }
        assertTrue(PlayerEngine.Mpv.playbackOptionCopy().description.contains("libmpv"))
        assertTrue(DecoderMode.Software.playbackOptionCopy().description.contains("软件解码"))
    }

    @Test
    fun download_filters_and_sorts_keep_the_expected_real_tasks() {
        val queued = media("queued", "Beta", DownloadStatus.Queued, total = 20, updated = 1)
        val paused = media("paused", "alpha", DownloadStatus.Paused, total = 10, updated = 3)
        val done = media("done", "Gamma", DownloadStatus.Completed, total = 30, updated = 2)
        val failed = media("failed", "Delta", DownloadStatus.Failed, downloaded = 40, updated = 4)
        val items = listOf(queued, paused, done, failed)

        assertEquals(
            listOf("paused", "queued"),
            filterAndSortDownloads(items, DownloadFilter.Active, DownloadSort.Updated).map { it.id },
        )
        assertEquals(
            listOf("done"),
            filterAndSortDownloads(items, DownloadFilter.Completed, DownloadSort.Name).map { it.id },
        )
        assertEquals(
            listOf("failed"),
            filterAndSortDownloads(items, DownloadFilter.Failed, DownloadSort.Size).map { it.id },
        )
        assertEquals(
            listOf("failed", "done", "queued", "paused"),
            filterAndSortDownloads(items, DownloadFilter.All, DownloadSort.Size).map { it.id },
        )
    }

    @Test
    fun download_byte_labels_keep_unit_boundaries_readable() {
        assertEquals("1023 B", formatDownloadBytes(1023))
        assertEquals("1 KB", formatDownloadBytes(1024))
        assertEquals("1 MB", formatDownloadBytes(1024L * 1024L))
        assertEquals("1.5 GB", formatDownloadBytes(1536L * 1024L * 1024L))
    }

    private fun media(
        id: String,
        title: String,
        status: DownloadStatus,
        total: Long = 0,
        downloaded: Long = 0,
        updated: Long,
    ) = OfflineMedia(
        id = id,
        serverId = "server",
        itemId = "item-$id",
        title = title,
        totalBytes = total,
        downloadedBytes = downloaded,
        status = status,
        updatedAtEpochMs = updated,
    )
}
