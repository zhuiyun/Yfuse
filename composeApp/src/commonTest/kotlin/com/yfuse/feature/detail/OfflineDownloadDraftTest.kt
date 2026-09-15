package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SubtitleTrackInfo
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.OfflineDownloadQuality
import com.yfuse.core.offline.buildOfflineDownloadRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDownloadDraftTest {
    @Test
    fun switching_versions_clears_an_index_that_means_a_different_language() {
        val a = version("a", "en")
        val b = version("b", "zh")
        val draft = OfflineDownloadDraft(versionId = "a", subtitleIndex = 4)
        assertEquals("en", draft.originalSelection(listOf(a, b)).subtitleLanguage)
        assertNull(draft.selectVersion("b").originalSelection(listOf(a, b)).subtitleStreamIndex)
        assertNull(draft.copy(subtitleIndex = 99).originalSelection(listOf(a, b)).subtitleStreamIndex)
    }

    @Test
    fun television_unwatched_batch_keeps_original_quality_and_follow_rule() {
        val version = version("web", "zh")
        val selection =
            OfflineDownloadDraft(
                "web",
                OfflineBatchMode.Unwatched,
                4,
                true,
            ).originalSelection(listOf(version))
        val requests =
            buildOfflineDownloadRequests(
                serverId = "server",
                currentItemId = "first",
                currentTitle = "First",
                currentRuntimeMinutes = 24,
                currentVersions = listOf(version),
                seasonEpisodes = listOf(episode("first", version).copy(played = true), episode("second", version)),
                selection = selection,
                currentSeriesId = "series",
                currentSeasonId = "season",
            )
        assertEquals(listOf("second"), requests.map { it.itemId })
        assertEquals(OfflineDownloadQuality.Original, requests.single().quality)
        assertEquals(4, requests.single().subtitleStreamIndex)
        assertTrue(requests.single().autoDownloadNewEpisodes)
        assertEquals(setOf("first", "second"), requests.single().knownEpisodeIds)
    }

    private fun version(
        id: String,
        language: String,
    ) = MediaVersion(
        id,
        "WEB",
        "mkv",
        1_000L,
        8_000_000,
        "h264",
        1080,
        "SDR",
        subtitleTracks = listOf(SubtitleTrackInfo(4, "srt", language)),
    )

    private fun episode(
        id: String,
        version: MediaVersion,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = 1,
        seasonNumber = 1,
        seasonId = "season",
        overview = null,
        runtimeMinutes = 24,
        primaryTag = null,
        playedPercentage = null,
        resumePositionTicks = null,
        versions = listOf(version),
    )
}
