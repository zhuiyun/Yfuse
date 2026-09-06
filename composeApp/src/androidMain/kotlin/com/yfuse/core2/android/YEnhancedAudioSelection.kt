package com.yfuse.core2.android

import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.strategy.YPlaybackPlan

internal data class YEnhancedAudioSelection(
    val track: YDemuxTrack,
    val outputPath: YAudioOutputPath,
    val softwareDecode: Boolean,
)

/** Reconcile the provisional plan with tracks discovered by the actual playback open. */
internal fun selectEnhancedAudio(
    tracks: List<YDemuxTrack>,
    plan: YPlaybackPlan,
    capabilities: YDeviceCapabilities,
    allowAudioPassthrough: Boolean,
    softwareDecodeAvailable: Boolean,
): YEnhancedAudioSelection? {
    // A missing probe audio track produces audioPath=None, which is not permission to passthrough.
    val audioCapabilities =
        if (allowAudioPassthrough && plan.audioPath != YAudioOutputPath.DecodePcm) {
            capabilities
        } else {
            capabilities.copy(audioPassthrough = emptySet())
        }
    val audioTracks = tracks.filter { it.type == YDemuxTrackType.Audio && it.audio != null }
    audioTracks.forEach { track ->
        val format = requireNotNull(track.audio)
        val path =
            audioCapabilities.audioOutputPath(
                YAudioRequirement(format.codec, format.channelCount, format.sampleRate),
            )
        if (path != YAudioOutputPath.None) {
            val software = plan.softwareAudioDecode && softwareDecodeAvailable
            return YEnhancedAudioSelection(
                track = track,
                outputPath = if (software) YAudioOutputPath.DecodePcm else path,
                softwareDecode = software,
            )
        }
    }
    // Probe failure must not disable owned FFmpeg audio decoding when demux later finds EAC3.
    // Only the audio path changes: Dolby video decoding and rendering keep their verified plan.
    return audioTracks.firstOrNull()?.takeIf { softwareDecodeAvailable }?.let { track ->
        YEnhancedAudioSelection(track, YAudioOutputPath.DecodePcm, softwareDecode = true)
    }
}
