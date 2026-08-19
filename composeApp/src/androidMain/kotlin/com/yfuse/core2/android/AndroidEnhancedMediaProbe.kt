package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.strategy.YPlaybackRequest

/**
 * Deep metadata probe over the demux-only FFmpeg bridge.
 *
 * It is deliberately invoked only for formats where the platform extractor failed or may hide
 * important bitstream metadata (notably Matroska/TS Dolby Vision). No packet is decoded here.
 */
internal class AndroidEnhancedMediaProbe(
    private val createDemuxer: () -> AndroidFfmpegDemuxer = ::AndroidFfmpegDemuxer,
) {
    fun probe(item: YMediaItem): YCore2ProbeResult? {
        val demuxer = createDemuxer()
        if (!demuxer.available) return null
        return try {
            val result =
                demuxer.open(
                    YDemuxSource(
                        uri = item.uri,
                        headers = item.headers,
                    ),
                )
            val videoTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Video && it.video != null }
                ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoVideoTrack)
            val video = requireNotNull(videoTrack.video)
            val audioTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Audio && it.audio != null }
            val audio = audioTrack?.audio
            YCore2ProbeResult.Success(
                playbackRequest =
                    YPlaybackRequest(
                        container = result.container,
                        video =
                            YVideoRequirement(
                                codec = video.codec,
                                width = video.width,
                                height = video.height,
                                frameRate = video.frameRate,
                                bitDepth = video.bitDepth,
                                hdrType = video.hdrType,
                                dolbyVisionProfile = video.dolbyVisionConfig?.profile,
                            ),
                        audio =
                            audio?.let {
                                YAudioRequirement(
                                    codec = it.codec,
                                    channelCount = it.channelCount.coerceAtLeast(1),
                                    sampleRate = it.sampleRate.coerceAtLeast(1),
                                )
                            },
                        platformDemuxSupported = false,
                        enhancedDemuxSupported = true,
                        fallbackHdrType = video.dolbyVisionConfig?.compatibleBaseHdr,
                        preferTunnel = false,
                    ),
                videoMime = video.mimeType,
                audioMime = audio?.mimeType,
                durationMs = (result.durationUs ?: 0L).coerceAtLeast(0L) / 1_000L,
                dolbyVisionConfig = video.dolbyVisionConfig,
            )
        } catch (_: Throwable) {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demuxer.close()
        }
    }
}

internal fun YCore2ProbeResult.Success.requiresEnhancedTruthProbe(): Boolean {
    val video = playbackRequest.video
    return playbackRequest.container.requiresEnhancedTruthProbe() &&
        video.codec in setOf(
            com.yfuse.core2.capability.YVideoCodec.H265,
            com.yfuse.core2.capability.YVideoCodec.Av1,
        )
}

private fun com.yfuse.core2.capability.YContainer.requiresEnhancedTruthProbe(): Boolean =
    this in setOf(
        com.yfuse.core2.capability.YContainer.Matroska,
        com.yfuse.core2.capability.YContainer.MpegTs,
        com.yfuse.core2.capability.YContainer.M2ts,
    )
