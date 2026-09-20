package com.yfuse.core2.android

import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YTrackPreference
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.demux.YVideoTrackFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDemuxAnalysisPolicyTest {
    private val hints = YMediaSourceHints(container = "mkv", videoCodec = "h264", audioTrackCount = 1)
    private val item = YMediaItem("movie", "https://host/movie", sourceHints = hints)

    @Test
    fun matching_track_count_without_the_requested_language_still_requires_full_analysis() {
        val preference =
            item.copy(
                initialTrackSelection = YInitialTrackSelection(audio = YTrackPreference(language = "zh")),
            )
        val short = ordinaryTracks("eng")
        assertFalse(short.hasRequiredStartupTracks(preference))
        assertTrue(ordinaryTracks("zho").hasRequiredStartupTracks(preference))
    }

    @Test
    fun missing_requested_subtitle_expands_analysis_but_explicit_off_does_not() {
        val initial = YInitialTrackSelection(subtitle = YTrackPreference(language = "en"))
        val short = ordinaryTracks("eng")
        assertFalse(short.hasRequiredStartupTracks(item.copy(initialTrackSelection = initial)))
        val subtitle =
            YDemuxTrack(
                YTrackId(7),
                YDemuxTrackType.Subtitle,
                language = "eng",
                subtitle =
                    com.yfuse.core2.demux.YSubtitleTrackFormat(
                        com.yfuse.core2.subtitle.YSubtitleFormat.Srt,
                        "application/x-subrip",
                    ),
            )
        assertTrue(
            short
                .copy(
                    tracks = short.tracks + subtitle,
                ).hasRequiredStartupTracks(item.copy(initialTrackSelection = initial)),
        )
        assertTrue(
            short.hasRequiredStartupTracks(item.copy(initialTrackSelection = initial.copy(subtitlesDisabled = true))),
        )
    }

    @Test
    fun a_missing_target_cannot_restart_analysis_outside_the_remaining_budget() {
        val target =
            item.copy(
                initialTrackSelection = YInitialTrackSelection(audio = YTrackPreference(language = "zh")),
            )
        val short = ordinaryTracks("eng")
        var fullAnalysisRan = false
        assertFalse(short.hasRequiredStartupTracks(target))
        AndroidProbeBudget(1_000L).use { parent ->
            val completed =
                runMetadataProbeStage(parent, AndroidBoundedProbe(), 18_000L, 2_000L, { false }) {
                    fullAnalysisRan = true
                    ordinaryTracks("zho").hasRequiredStartupTracks(target)
                }
            assertFalse(completed)
            assertFalse(fullAnalysisRan)
        }
    }

    private fun ordinaryTracks(language: String) =
        YDemuxOpenResult(
            YContainer.Matroska,
            tracks =
                listOf(
                    YDemuxTrack(
                        YTrackId(0),
                        YDemuxTrackType.Video,
                        video = YVideoTrackFormat(YVideoCodec.H264, "video/avc", 1920, 1080),
                    ),
                    YDemuxTrack(
                        YTrackId(4),
                        YDemuxTrackType.Audio,
                        language = language,
                        audio = YAudioTrackFormat(YAudioCodec.Aac, "audio/mp4a-latm", 2, 48_000),
                    ),
                ),
        )

    @Test
    fun fast_analysis_excludes_unknown_hdr_dolby_and_unreliable_containers() {
        assertTrue(item.allowsShortDemuxAnalysis())
        assertFalse(item.copy(sourceHints = null).allowsShortDemuxAnalysis())
        assertFalse(item.copy(sourceHints = hints.copy(container = "mpegts")).allowsShortDemuxAnalysis())
        assertFalse(item.copy(sourceHints = hints.copy(dolbyVision = true)).allowsShortDemuxAnalysis())
        assertFalse(item.copy(sourceHints = hints.copy(dynamicRange = "HDR10")).allowsShortDemuxAnalysis())
        assertFalse(item.copy(sourceHints = hints.copy(audioTrackCount = 0)).allowsShortDemuxAnalysis())
    }

    @Test
    fun incomplete_track_geometry_or_missing_audio_requires_full_analysis() {
        val video =
            YDemuxTrack(
                YTrackId(0),
                YDemuxTrackType.Video,
                video = YVideoTrackFormat(YVideoCodec.H264, "video/avc", 1920, 1080),
            )
        val audio =
            YDemuxTrack(
                YTrackId(1),
                YDemuxTrackType.Audio,
                audio = YAudioTrackFormat(YAudioCodec.Aac, "audio/mp4a-latm", 2, 48000),
            )
        val complete = YDemuxOpenResult(YContainer.Matroska, tracks = listOf(video, audio))
        assertTrue(complete.hasRequiredStartupTracks(item))
        assertFalse(complete.copy(tracks = listOf(video)).hasRequiredStartupTracks(item))
        assertFalse(complete.hasRequiredStartupTracks(item.copy(sourceHints = hints.copy(audioTrackCount = 2))))
        assertFalse(
            complete
                .copy(
                    tracks = listOf(video.copy(video = video.video!!.copy(width = 0)), audio),
                ).hasRequiredStartupTracks(item),
        )
        assertFalse(
            complete
                .copy(
                    tracks = listOf(video, audio.copy(audio = audio.audio!!.copy(sampleRate = 0))),
                ).hasRequiredStartupTracks(item),
        )
    }
}
