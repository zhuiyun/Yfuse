package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.dolby.YDolbyVisionStreamEvidence
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.shouldRequestEnhancedProbe

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
                        cacheIdentity = item.cacheIdentity,
                        cacheMaximumBytes = item.cacheMaximumBytes,
                        transportCredentials = item.transportCredentials,
                    ),
                )
            val videoTrack =
                result.tracks.firstOrNull { it.type == YDemuxTrackType.Video && it.video != null }
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoVideoTrack)
            val video = requireNotNull(videoTrack.video)
            val audioTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Audio && it.audio != null }
            val audio = audioTrack?.audio
            val packing = video.samplePacking
            var rpuCount = 0
            var enhancementLayerCount = 0
            if (packing != null && (video.dolbyVisionConfig != null || item.sourceHints?.dolbyVision == true)) {
                demuxer.selectTracks(setOf(videoTrack.id))
                repeat(DOLBY_PROBE_SAMPLE_LIMIT) {
                    val sample = demuxer.readSample() ?: return@repeat
                    if (sample.trackId == videoTrack.id) {
                        val evidence = YBitstream.dolbyVisionEvidence(sample.data, packing)
                        rpuCount += evidence.rpuCount
                        enhancementLayerCount += evidence.enhancementLayerCount
                    }
                }
            }
            val dolbyEvidence =
                video.dolbyVisionConfig?.let { config ->
                    YDolbyVisionStreamEvidence(
                        config = config,
                        observedNals = YDolbyVisionNalEvidence(rpuCount, enhancementLayerCount),
                    )
                }
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
                dolbyVisionStreamEvidence = dolbyEvidence,
                unconfiguredDolbyVisionSignal =
                    video.dolbyVisionConfig == null && (rpuCount > 0 || enhancementLayerCount > 0),
            )
        } catch (_: Throwable) {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demuxer.close()
        }
    }
}

private const val DOLBY_PROBE_SAMPLE_LIMIT = 24

internal fun YCore2ProbeResult.Success.requiresEnhancedTruthProbe(): Boolean {
    val request = playbackRequest
    // Some platform extractors report video/dolby-vision but omit dvcC/dvvC/dvwC, especially for
    // Matroska and MPEG-TS. Never treat that missing container metadata as an unsupported profile:
    // require YCore's bounded FFmpeg truth probe before the route planner sees the stream.
    if (request.video.hdrType == YHdrType.DolbyVision && dolbyVisionConfig == null) return true
    return shouldRequestEnhancedProbe(
        container = request.container,
        videoCodec = request.video.codec,
        audioCodec = request.audio?.codec,
    )
}

internal fun YCore2ProbeResult.Success.requiresEnhancedTruthProbe(item: YMediaItem): Boolean =
    (item.sourceHints?.dolbyVision == true && dolbyVisionConfig == null) ||
        requiresEnhancedTruthProbe()
