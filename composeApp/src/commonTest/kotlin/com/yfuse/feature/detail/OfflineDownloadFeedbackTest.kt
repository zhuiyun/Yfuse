package com.yfuse.feature.detail

import com.yfuse.core.offline.OfflineBatchMode
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineDownloadFeedbackTest {
    @Test
    fun a_film_is_offered_only_itself_and_an_episode_its_season() {
        assertEquals(listOf(OfflineBatchMode.Current), offlineBatchModes(episode = false, seasonEpisodes = 0))
        assertEquals(listOf(OfflineBatchMode.Current), offlineBatchModes(episode = true, seasonEpisodes = 0))
        assertEquals(OfflineBatchMode.entries.toList(), offlineBatchModes(episode = true, seasonEpisodes = 12))
        assertEquals("本片", offlineBatchModeLabel(OfflineBatchMode.Current, episode = false))
        assertEquals("本集", offlineBatchModeLabel(OfflineBatchMode.Current, episode = true))
        assertEquals("整季", offlineBatchModeLabel(OfflineBatchMode.Season, episode = true))
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

    private fun notice(
        queued: Int,
        skipped: Int = 0,
        waitingForWifi: Boolean = false,
        alreadyDownloaded: Int = 0,
        episode: Boolean,
    ) = offlineEnqueueMessage(OfflineEnqueueResult(queued, skipped, waitingForWifi, alreadyDownloaded), episode)
}
