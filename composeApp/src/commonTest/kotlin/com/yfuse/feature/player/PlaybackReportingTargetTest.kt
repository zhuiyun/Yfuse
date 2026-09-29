package com.yfuse.feature.player

import com.yfuse.core.filesource.fileSourceItemId
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackReportingTargetTest {
    @Test
    fun downloaded_file_keeps_its_source_server() {
        val target = playbackReportingTarget(item(url = "file:///downloads/movie.mkv", serverId = "server-b"))

        assertEquals(PlaybackReportingTarget.SavedServer("server-b"), target)
    }

    @Test
    fun local_file_without_source_server_never_uses_the_current_default() {
        assertEquals(
            PlaybackReportingTarget.Disabled,
            playbackReportingTarget(item(url = "file:///downloads/movie.mkv")),
        )
        assertEquals(
            PlaybackReportingTarget.Disabled,
            playbackReportingTarget(item(url = "content://downloads/movie")),
        )
    }

    @Test
    fun remote_legacy_entry_retains_default_server_compatibility() {
        assertEquals(
            PlaybackReportingTarget.DefaultServer,
            playbackReportingTarget(item(url = "https://emby.example/Videos/1/stream")),
        )
    }

    @Test
    fun missing_queue_entry_disables_reporting() {
        assertEquals(PlaybackReportingTarget.Disabled, playbackReportingTarget(null))
    }

    @Test
    fun a_file_source_entry_is_never_reported_to_the_default_server() {
        val id = fileSourceItemId("fs0123456789abcdef01234567", listOf("电影", "Dune.mkv"))

        assertEquals(
            PlaybackReportingTarget.Disabled,
            playbackReportingTarget(item(url = "https://nas.local:5244/dav/Dune.mkv", id = id)),
        )
        assertEquals(
            PlaybackReportingTarget.Disabled,
            playbackReportingTarget(item(url = "smb://nas/Media/Dune.mkv", id = id)),
        )
    }

    private fun item(
        url: String,
        serverId: String? = null,
        id: String = "item-1",
    ) = PlayerMediaItem(
        id = id,
        url = url,
        transcodeUrl = url,
        title = "Movie",
        serverId = serverId,
    )
}
