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
    val preferSoftware: Boolean,
)

/**
 * Resolve audio from the opened demuxer, not from a possibly incomplete preflight probe.
 *
 * MediaExtractor may hide an EAC3 track and the bounded enhanced probe may fail transiently.
 * The resulting video plan can legitimately have audioPath=None/softwareAudioDecode=false.
 * Those values are not evidence that a track discovered at the real open cannot be decoded.
 * Only audio is reconciled here: the selected hardware video/Dolby path is left untouched.
 * The software extension is a candidate, not proof of codec support; configureAudio must
 * still succeed before any output is reported.
 */
internal fun selectEnhancedAudioTrack(
    tracks: List<YDemuxTrack>,
    capabilities: YDeviceCapabilities,
    plan: YPlaybackPlan,
    softwareDecodeAvailable: Boolean,
): YEnhancedAudioSelection? {
    val audioTracks = tracks.filter { it.type == YDemuxTrackType.Audio && it.audio != null }
    // An inconclusive None plan must not silently enable passthrough. PCM also needs the
    // decoder-only capabilities when a device advertises both decoding and passthrough.
    val allowPassthrough = plan.audioPath == YAudioOutputPath.Passthrough && !plan.softwareAudioDecode
    val deviceCapabilities =
        if (allowPassthrough) capabilities else capabilities.copy(audioPassthrough = emptySet())
    for (track in audioTracks) {
        val format = requireNotNull(track.audio)
        val devicePath =
            deviceCapabilities.audioOutputPath(
                YAudioRequirement(
                    codec = format.codec,
                    channelCount = format.channelCount,
                    sampleRate = format.sampleRate,
                ),
            )
        if (devicePath == YAudioOutputPath.None) continue
        val preferSoftware = plan.softwareAudioDecode && softwareDecodeAvailable
        return YEnhancedAudioSelection(
            track = track,
            outputPath = if (preferSoftware) YAudioOutputPath.DecodePcm else devicePath,
            preferSoftware = preferSoftware,
        )
    }
    if (!softwareDecodeAvailable) return null
    return audioTracks.firstOrNull()?.let { track ->
        YEnhancedAudioSelection(
            track = track,
            outputPath = YAudioOutputPath.DecodePcm,
            preferSoftware = true,
        )
    }
}
