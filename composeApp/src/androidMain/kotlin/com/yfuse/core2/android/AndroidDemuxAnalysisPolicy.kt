package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.matchingPreference
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxTrackType

/** Selects a demuxer, never a decoder: the enhanced open still validates the real tracks. */
internal fun YMediaItem.prefersEnhancedStartupDemux(): Boolean {
    if (!allowsShortDemuxAnalysis()) return false
    if (uri.substringBefore(':').lowercase() !in setOf("http", "https")) return false
    val hints = sourceHints ?: return false
    if (hints.container?.lowercase() !in setOf("mkv", "matroska")) return false
    // Some vendor Matroska extractors omit E-AC-3 entirely. Reopening the same extractor
    // after its probe expires wastes the startup budget before FFmpeg can see the audio.
    return (hints.audioCodecs + listOfNotNull(hints.audioCodec)).any {
        it.lowercase() in setOf("eac3", "e-ac-3", "e-ac3", "ec-3", "eac3_joc")
    }
}

internal fun YMediaItem.allowsShortDemuxAnalysis(): Boolean {
    val hints = sourceHints ?: return false
    return disc == null &&
        drmConfiguration == null &&
        !hints.dolbyVision &&
        hints.dynamicRange.isNullOrBlank().let { it || hints.dynamicRange.equals("SDR", true) } &&
        hints.container?.lowercase() in setOf("mkv", "matroska", "mp4", "m4v") &&
        hints.videoCodec?.lowercase() in setOf("h264", "avc", "hevc", "h265") &&
        hints.audioTrackCount > 0
}

internal fun YDemuxOpenResult.hasRequiredStartupTracks(item: YMediaItem): Boolean =
    tracks.any {
        it.type == YDemuxTrackType.Video &&
            it.video?.let { video -> video.codec != YVideoCodec.Unknown && video.width > 0 && video.height > 0 } == true
    } &&
        tracks.count {
            it.type == YDemuxTrackType.Audio &&
                it.audio?.let { audio ->
                    audio.codec != YAudioCodec.Unknown &&
                        audio.sampleRate > 0 &&
                        audio.channelCount > 0
                } ==
                true
        } >=
        (item.sourceHints?.audioTrackCount ?: 1) &&
        hasRequestedStartupTracks(item)

private fun YDemuxOpenResult.hasRequestedStartupTracks(item: YMediaItem): Boolean {
    val initial = item.initialTrackSelection ?: return true
    if (initial.audio != null) {
        val selected = tracks.preferredAudioTrack(initial.audio)?.audio ?: return false
        if (selected.codec == YAudioCodec.Unknown ||
            selected.sampleRate <= 0 ||
            selected.channelCount <= 0
        ) {
            return false
        }
    }
    if (initial.subtitle != null && !initial.subtitlesDisabled) {
        if (tracks.preferredSubtitleTrack(initial.subtitle) != null) return true
        // A requested sidecar is already a complete source; extending container analysis cannot discover it.
        return item.allExternalSubtitles
            .mapIndexed { index, subtitle ->
                YTrack(
                    index.toString(),
                    YTrackType.Subtitle,
                    subtitle.language ?: "Subtitle ${index + 1}",
                    subtitle.language,
                    subtitle.format?.name,
                )
            }.matchingPreference(initial.subtitle) != null
    }
    return true
}
