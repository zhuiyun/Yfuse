package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.Season
import com.yfuse.core.offline.OfflineBatchMode
import com.yfuse.core.offline.offlineSeasonLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineDownloadFeedbackTest {
    @Test
    fun a_film_is_offered_only_itself_and_an_episode_its_season() {
        assertEquals(listOf(OfflineBatchMode.Current), offlineBatchModes(episode = false, seasonEpisodes = 0))
        assertEquals(listOf(OfflineBatchMode.Current), offlineBatchModes(episode = true, seasonEpisodes = 0))
        assertEquals(OfflineBatchMode.entries.toList(), offlineBatchModes(episode = true, seasonEpisodes = 12))
        assertEquals("本片", offlineBatchModeLabel(OfflineBatchMode.Current, episode = false))
        assertEquals("本集", offlineBatchModeLabel(OfflineBatchMode.Current, episode = true))
        assertEquals(
            "第 2 季（10 集）",
            offlineBatchModeLabel(OfflineBatchMode.Season, episode = true, seasonLabel = "第 2 季（10 集）"),
        )
        // Only a caller that cannot say which season still gets the generic word.
        assertEquals("整季", offlineBatchModeLabel(OfflineBatchMode.Season, episode = true))
    }

    @Test
    fun a_whole_season_is_named_with_its_count_and_specials_by_name() {
        assertEquals("第 2 季（10 集）", offlineSeasonLabel(seasonNumber = 2, episodeCount = 10))
        assertEquals("特别篇（3 集）", offlineSeasonLabel(seasonNumber = 0, episodeCount = 3))
        // A count that is not known is left out, never guessed.
        assertEquals("第 2 季", offlineSeasonLabel(seasonNumber = 2, episodeCount = null))
        assertEquals("特别篇", offlineSeasonLabel(seasonNumber = 0, episodeCount = 0))
        // Without a number the server's own name stands, and without that the season is only 本季.
        assertEquals("Season Two（4 集）", offlineSeasonLabel(seasonNumber = null, episodeCount = 4, "Season Two"))
        assertEquals("本季", offlineSeasonLabel(seasonNumber = null, episodeCount = null, seasonName = " "))
    }

    @Test
    fun the_season_named_is_the_one_whose_episodes_are_listed() {
        val seasons = listOf(Season("s1", "第 1 季", 1, null), Season("s0", "特别篇", 0, null))
        // The rail still lists season 1 while season 2 loads: season 1 is what would be taken.
        assertEquals("第 1 季（3 集）", listedSeasonLabel(List(3) { episode("e$it", 1, "s1") }, seasons))
        assertEquals("特别篇（2 集）", listedSeasonLabel(List(2) { episode("x$it", null, "s0") }, seasons))
        assertEquals("Extras（1 集）", listedSeasonLabel(listOf(episode("x", null, "sx")), seasons + extras))
        assertNull(listedSeasonLabel(emptyList(), seasons))
    }

    @Test
    fun the_notice_counts_what_was_queued_and_says_why_it_waits() {
        assertEquals("已加入 12 集下载", notice(queued = 12, episode = true))
        assertEquals(
            "已加入 10 集下载，2 集没有相符的版本，已跳过 · 连上 Wi-Fi 后开始",
            notice(queued = 10, skipped = 2, waitingForWifi = true, episode = true),
        )
        assertEquals("已加入下载 · 连上 Wi-Fi 后开始", notice(queued = 1, waitingForWifi = true, episode = false))
        assertEquals("没有加入下载：其他集里没有与所选版本相符的文件", notice(queued = 0, skipped = 3, episode = true))
    }

    @Test
    fun episodes_already_on_the_device_are_not_counted_as_queued() {
        assertEquals("已加入 4 集下载，8 集已下载过", notice(queued = 4, alreadyDownloaded = 8, episode = true))
        assertEquals("所选的 12 集都已下载", notice(queued = 0, alreadyDownloaded = 12, episode = true))
        assertEquals("已经下载过了", notice(queued = 0, alreadyDownloaded = 1, episode = false))
    }

    private val extras = Season("sx", "Extras", null, null)

    private fun episode(
        id: String,
        seasonNumber: Int?,
        seasonId: String,
    ) = Episode(
        id = id,
        name = id,
        indexNumber = null,
        seasonNumber = seasonNumber,
        seasonId = seasonId,
        overview = null,
        runtimeMinutes = null,
        primaryTag = null,
        playedPercentage = null,
        resumePositionTicks = null,
    )

    private fun notice(
        queued: Int,
        skipped: Int = 0,
        waitingForWifi: Boolean = false,
        alreadyDownloaded: Int = 0,
        episode: Boolean,
    ) = offlineEnqueueMessage(OfflineEnqueueResult(queued, skipped, waitingForWifi, alreadyDownloaded), episode)
}
