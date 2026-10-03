package com.yfuse.core.offline

import com.yfuse.core.model.Episode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 追更 for a 短剧 that puts up a whole batch at once. */
class AutoDownloadPlanTest {
    @Test
    fun a_batch_starts_from_its_first_episode_and_the_rest_wait() {
        val plan =
            planAutoDownload(
                episodes = (1..80).map { episode(it) },
                knownEpisodeIds = emptySet(),
                ruleItems = emptyList(),
                itemLimit = 3,
            )

        assertEquals(listOf("e1", "e2", "e3"), plan.download.map(Episode::id))
        assertEquals(emptyList(), plan.remove)
        // Episodes 4-80 are not remembered as seen, so a later refresh still fetches them.
        assertEquals(listOf("e1", "e2", "e3"), plan.seen)
    }

    @Test
    fun a_watched_download_makes_room_for_the_next_episode() {
        val plan =
            planAutoDownload(
                episodes = (1..80).map { episode(it, played = it == 1) },
                knownEpisodeIds = setOf("e1", "e2", "e3"),
                ruleItems =
                    listOf(
                        download("e1", updatedAt = 1L),
                        download("e2", updatedAt = 2L),
                        download("e3", updatedAt = 3L),
                    ),
                itemLimit = 3,
            )

        assertEquals(listOf("e4"), plan.download.map(Episode::id))
        assertEquals(listOf("offline-e1"), plan.remove)
        assertEquals(listOf("e4", "e1"), plan.seen)
    }

    @Test
    fun a_download_waiting_to_be_watched_is_never_deleted_for_a_newer_episode() {
        val plan =
            planAutoDownload(
                episodes = (1..80).map { episode(it) },
                knownEpisodeIds = setOf("e1", "e2", "e3"),
                ruleItems =
                    listOf(
                        download("e1", updatedAt = 1L),
                        download("e2", updatedAt = 2L, status = DownloadStatus.Downloading),
                        download("e3", updatedAt = 3L, status = DownloadStatus.Paused),
                    ),
                itemLimit = 3,
            )

        assertTrue(plan.download.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun failed_downloads_and_ones_gone_from_the_server_give_up_their_place() {
        val plan =
            planAutoDownload(
                episodes = (2..10).map { episode(it) },
                knownEpisodeIds = setOf("e1", "e2"),
                ruleItems =
                    listOf(
                        download("e1", updatedAt = 1L),
                        download("e2", updatedAt = 2L, status = DownloadStatus.Failed),
                    ),
                itemLimit = 2,
            )

        assertEquals(listOf("e3", "e4"), plan.download.map(Episode::id))
        assertEquals(listOf("offline-e2", "offline-e1"), plan.remove)
    }

    @Test
    fun an_episode_the_server_does_not_have_yet_is_left_for_later() {
        val plan =
            planAutoDownload(
                episodes = listOf(episode(1).copy(missing = true), episode(2)),
                knownEpisodeIds = emptySet(),
                ruleItems = emptyList(),
                itemLimit = 3,
            )

        assertEquals(listOf("e2"), plan.download.map(Episode::id))
        assertEquals(listOf("e2"), plan.seen)
    }

    private fun episode(
        index: Int,
        played: Boolean = false,
    ) = Episode(
        id = "e$index",
        name = "第 $index 集",
        indexNumber = index,
        seasonNumber = 1,
        seasonId = "season-1",
        overview = null,
        runtimeMinutes = 2,
        primaryTag = null,
        playedPercentage = null,
        played = played,
        resumePositionTicks = null,
    )

    private fun download(
        itemId: String,
        updatedAt: Long,
        status: DownloadStatus = DownloadStatus.Completed,
    ) = OfflineMedia(
        id = "offline-$itemId",
        serverId = "server",
        itemId = itemId,
        title = itemId,
        seriesId = "series",
        seasonId = "season-1",
        automaticallyDownloaded = true,
        status = status,
        updatedAtEpochMs = updatedAt,
    )
}
