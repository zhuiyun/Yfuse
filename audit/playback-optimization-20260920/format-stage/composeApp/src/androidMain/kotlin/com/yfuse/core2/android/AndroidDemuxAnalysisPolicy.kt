package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxTrackType

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
        (item.sourceHints?.audioTrackCount ?: 1)
