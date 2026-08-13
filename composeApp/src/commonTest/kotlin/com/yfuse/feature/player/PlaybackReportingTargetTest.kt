package com.yfuse.feature.player

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

    private fun item(
        url: String,
        serverId: String? = null,
    ) = PlayerMediaItem(
        id = "item-1",
        url = url,
        transcodeUrl = url,
        title = "Movie",
        serverId = serverId,
    )
}
