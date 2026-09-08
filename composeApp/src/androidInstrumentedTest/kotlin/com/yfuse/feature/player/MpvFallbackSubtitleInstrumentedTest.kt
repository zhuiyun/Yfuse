package com.yfuse.feature.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.android.EXTERNAL_SUBTITLE_TRACK_ID
import com.yfuse.core2.android.GeneratedAvcTestMedia
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.legacy.AndroidMpvCore2FallbackFactory
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real production fallback catalog plus real HTTP; no video clock is needed to deliver subtitles. */
@RunWith(AndroidJUnit4::class)
class MpvFallbackSubtitleInstrumentedTest {
    @Test
    fun paused_player_emits_loaded_subtitles_without_a_video_state_tick() =
        withPausedPlayer { _, player, origin ->
            coroutineScope {
                val loaded =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000L) { player.state.first { it.subtitleCues.isNotEmpty() } }
                    }
                origin.respond(body = subtitle)
                val state = loaded.await()
                assertEquals("Paused caption", (state.subtitleCues.single().payload as YSubtitlePayload.Text).plainText)
                assertFalse(player.playbackRequested)
                assertFalse(state.playing)
            }
        }

    @Test
    fun rapid_off_external_off_selection_is_preserved_when_the_load_finishes() =
        withPausedPlayer { host, player, origin ->
            coroutineScope {
                host.onMain {
                    player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
                    player.selectTrack(YTrackType.Subtitle, EXTERNAL_SUBTITLE_TRACK_ID)
                    player.selectTrack(YTrackType.Subtitle, EngineTrack.OFF)
                }
                val loadedWhileOff =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000L) {
                            player.state.first { state ->
                                state.subtitleTracks.any {
                                    it.id == EXTERNAL_SUBTITLE_TRACK_ID &&
                                        it.codec == "application/x-subrip"
                                }
                            }
                        }
                    }
                origin.respond(body = subtitle)
                val state = loadedWhileOff.await()
                assertTrue(state.subtitleCues.isEmpty())
                assertFalse(state.subtitleTracks.single { it.id == EXTERNAL_SUBTITLE_TRACK_ID }.selected)
                val selected =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000L) { player.state.first { it.subtitleCues.isNotEmpty() } }
                    }
                host.onMain { player.selectTrack(YTrackType.Subtitle, EXTERNAL_SUBTITLE_TRACK_ID) }
                assertTrue(
                    selected
                        .await()
                        .subtitleTracks
                        .single { it.id == EXTERNAL_SUBTITLE_TRACK_ID }
                        .selected,
                )
                assertFalse(player.playbackRequested)
            }
        }

    @Test
    fun failed_http_subtitle_removes_the_pending_track_without_failing_or_resuming_video() =
        withPausedPlayer { _, player, origin ->
            coroutineScope {
                assertTrue(
                    player.state.value.subtitleTracks
                        .any { it.id == EXTERNAL_SUBTITLE_TRACK_ID },
                )
                val failed =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000L) {
                            player.state.first { state ->
                                state.subtitleTracks.none {
                                    it.id ==
                                        EXTERNAL_SUBTITLE_TRACK_ID
                                }
                            }
                        }
                    }
                origin.respond(status = 500, body = "Fixture subtitle failure")
                val state = failed.await()
                assertTrue(state.subtitleCues.isEmpty())
                assertNull(state.error)
                assertFalse(player.playbackRequested)
                assertFalse(state.playing)
            }
        }

    private fun withPausedPlayer(
        verify: suspend (PlaybackReleaseTestHost, YPlayer, PlaybackHeldResponseOrigin) -> Unit,
    ) = runBlocking {
        requireFullReleaseTestPackage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = GeneratedAvcTestMedia.create(context.cacheDir)
        try {
            PlaybackHeldResponseOrigin().use { origin ->
                PlaybackReleaseTestHost.open().use { host ->
                    val item =
                        YMediaItem(
                            id = "paused-subtitle-test",
                            uri = Uri.fromFile(media).toString(),
                            externalSubtitle =
                                YExternalSubtitleSource(
                                    origin.url,
                                    format = YSubtitleFormat.Srt,
                                    default = true,
                                ),
                        )
                    val player =
                        host.onMain {
                            checkNotNull(
                                AndroidMpvCore2FallbackFactory(context).create(
                                    item,
                                    YPlayerOpenRequest(listOf(item), autoPlay = false, autoNext = false),
                                    YPlaybackPlan(
                                        route = YPlaybackRoute.GpuEnhanced,
                                        demuxPath = YDemuxPath.Enhanced,
                                        decodePath = YDecodePath.Hardware,
                                        renderPath = YRenderPath.Gpu,
                                        outputHdrType = YHdrType.Sdr,
                                        reason = "Paused fallback subtitle instrumentation",
                                    ),
                                    startSpeed = 1f,
                                ),
                            )
                        }
                    try {
                        // No Surface is attached to this player: every observed update must come
                        // from the asynchronous catalog, never from an MPV playback timer.
                        withTimeout(5_000L) { origin.requested.await() }
                        assertFalse(player.playbackRequested)
                        verify(host, player, origin)
                    } finally {
                        host.onMain { player.release() }
                    }
                }
            }
        } finally {
            check(media.delete() || !media.exists()) { "Generated fallback subtitle fixture was not removed" }
        }
    }

    private val subtitle = "1\n00:00:00,000 --> 00:00:05,000\nPaused caption\n"
}
