package com.yfuse.core.offline

import com.yfuse.core.sync.playback.PlaybackStateRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineResumePositionTest {
    private val download =
        OfflineMedia(
            id = "download-1",
            serverId = "server-a",
            itemId = "item-1",
            title = "沙丘",
            localPath = "/downloads/item-1.mkv",
            status = DownloadStatus.Completed,
        )

    @Test
    fun a_title_never_played_starts_at_the_beginning() {
        assertEquals(0L, offlineStartPositionMs(download, emptyList()))
    }

    @Test
    fun a_title_left_part_way_resumes_where_it_was_left() {
        assertEquals(
            42 * MINUTE,
            offlineStartPositionMs(download, listOf(record(positionMs = 42 * MINUTE))),
        )
    }

    @Test
    fun the_latest_record_wins_when_online_and_offline_viewing_both_left_one() {
        val online = record(mediaKey = "tmdb:438631", positionMs = 20 * MINUTE, lastPlayedAt = 1_000L)
        val offline = record(mediaKey = "item-1", positionMs = 55 * MINUTE, lastPlayedAt = 2_000L)

        assertEquals(55 * MINUTE, offlineStartPositionMs(download, listOf(online, offline)))
        assertEquals(
            20 * MINUTE,
            offlineStartPositionMs(download, listOf(online.copy(lastPlayedAtEpochMs = 3_000L), offline)),
        )
    }

    @Test
    fun a_finished_title_or_one_into_its_credits_starts_over() {
        assertEquals(0L, offlineStartPositionMs(download, listOf(record(positionMs = 60 * MINUTE, played = true))))
        assertEquals(0L, offlineStartPositionMs(download, listOf(record(positionMs = 97 * MINUTE))))
    }

    @Test
    fun progress_on_another_server_or_item_is_not_this_download() {
        val states =
            listOf(
                record(positionMs = 30 * MINUTE, serverId = "server-b"),
                record(positionMs = 30 * MINUTE, serverItemId = "item-2"),
            )

        assertEquals(0L, offlineStartPositionMs(download, states))
    }

    private fun record(
        positionMs: Long,
        mediaKey: String = "emby:item-1",
        played: Boolean = false,
        lastPlayedAt: Long = 1_000L,
        serverId: String = "server-a",
        serverItemId: String = "item-1",
    ) = PlaybackStateRecord(
        mediaKey = mediaKey,
        positionMs = positionMs,
        durationMs = 100 * MINUTE,
        played = played,
        lastPlayedAtEpochMs = lastPlayedAt,
        deviceId = "device",
        serverId = serverId,
        serverItemId = serverItemId,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
