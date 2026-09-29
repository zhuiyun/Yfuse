package com.yfuse.feature.extras

import com.yfuse.core.model.MediaTrailer
import com.yfuse.feature.player.PlaybackReportingTarget
import com.yfuse.feature.player.isExternalPlayback
import com.yfuse.feature.player.isTrailerPlayback
import com.yfuse.feature.player.playbackReportingTarget
import com.yfuse.feature.player.trailerPlaybackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailerPlaybackTest {
    private val local =
        MediaTrailer.Local(
            "emby",
            "t1",
            "预告片",
            "http://host/Videos/t1/stream?static=true&api_key=k",
            125_000L,
        )
    private val link = MediaTrailer.Remote("Official Trailer", "https://youtu.be/x", "YouTube")

    @Test
    fun a_trailer_entry_belongs_to_no_server_and_is_marked_as_a_trailer() {
        val item = trailerPlaybackItem(local.streamUrl, "花样年华 · 预告片")

        assertNull(item.serverId)
        assertTrue(item.isExternalPlayback)
        assertTrue(item.isTrailerPlayback)
        assertEquals(local.streamUrl, item.url)
        // Not the server the file came from: only the legacy default-server target, which the
        // player refuses for an outside entry — what isExternalPlayback is there to tell it.
        assertEquals(PlaybackReportingTarget.DefaultServer, playbackReportingTarget(item))
        assertTrue(item.id != trailerPlaybackItem(local.streamUrl, "again").id)
    }

    @Test
    fun a_launched_local_trailer_waits_for_the_player_and_a_link_goes_to_the_system() {
        val opened = mutableListOf<String>()
        val launcher =
            TrailerLauncher { url ->
                opened += url
                true
            }

        launcher.open(local, "花样年华")
        assertEquals("花样年华 · 预告片", launcher.queued?.title)
        launcher.launched()
        assertNull(launcher.queued)

        launcher.open(link, "花样年华")
        assertEquals(listOf("https://youtu.be/x"), opened)
        assertNull(launcher.queued)
        assertNull(launcher.problem)
    }

    @Test
    fun a_link_nothing_can_open_is_said_rather_than_swallowed() {
        val launcher = TrailerLauncher { false }

        launcher.open(link, "花样年华")

        assertEquals("没有可以打开 YouTube 链接的应用", launcher.problem)
        launcher.problemShown()
        assertNull(launcher.problem)
    }

    @Test
    fun trailer_wording_reads_naturally() {
        assertEquals("花样年华 · 预告片", trailerPlayerTitle("花样年华", "预告片"))
        assertEquals("花样年华 正式预告", trailerPlayerTitle("花样年华", "花样年华 正式预告"))
        assertEquals("预告片", trailerPlayerTitle(" ", "预告片"))
        assertEquals("在应用内播放 · 2:05", trailerDescription(local))
        assertEquals("在应用内播放", trailerDescription(local.copy(durationMs = null)))
        assertEquals("在 YouTube 打开", trailerDescription(link))
        assertEquals("在哔哩哔哩打开", trailerDescription(link.copy(site = "哔哩哔哩")))
        assertNull(trailerDurationLabel(400L))
    }
}
