package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
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
    private val probeCacheLock = Any()

    /**
     * Successful deep probes for this evaluator's lifetime, newest last.
     *
     * The reasoning matches [AndroidCore2MediaProbe]: this opens the real source over the network
     * to read bitstream metadata, route evaluation can run several times during one cold start,
     * and every repeat re-reads the same bytes for the same answer while the first frame waits.
     */
    private val probeCache = LinkedHashMap<String, YCore2ProbeResult.Success>()

    fun probe(item: YMediaItem): YCore2ProbeResult? {
        val cacheKey = item.enhancedProbeCacheKey()
        synchronized(probeCacheLock) { probeCache[cacheKey] }?.let { cached -> return cached }
        val result = probeUncached(item)
        // Successes only: a failure here is usually an unreachable source, and caching it would
        // keep the route unplayable for the rest of the session after the network recovers.
        if (result is YCore2ProbeResult.Success) {
            synchronized(probeCacheLock) {
                probeCache.remove(cacheKey)
                probeCache[cacheKey] = result
                while (probeCache.size > MAX_CACHED_ENHANCED_PROBES) {
                    probeCache.remove(probeCache.keys.first())
                }
            }
        }
        return result
    }

    private fun probeUncached(item: YMediaItem): YCore2ProbeResult? {
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
            val audioTrack = result.tracks.firstOrNull { it.type == YDemuxTrackType.Audio && it.audio != null }
            val audio = audioTrack?.audio
            if (videoTrack == null) {
                // Audio-only media reaches the enhanced probe whenever the platform extractor could
                // not open the container at all, so it has to be answered here too.
                val audioOnly = audio ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoPlayableTrack)
                return YCore2ProbeResult.Success(
                    playbackRequest =
                        YPlaybackRequest(
                            container = result.container,
                            video = ENHANCED_AUDIO_ONLY_VIDEO_PLACEHOLDER,
                            audio =
                                YAudioRequirement(
                                    codec = audioOnly.codec,
                                    channelCount = audioOnly.channelCount.coerceAtLeast(1),
                                    sampleRate = audioOnly.sampleRate.coerceAtLeast(1),
                                ),
                            audioOnly = true,
                            platformDemuxSupported = false,
                            enhancedDemuxSupported = true,
                            preferTunnel = false,
                        ),
                    videoMime = "",
                    audioMime = audioOnly.mimeType,
                    durationMs = (result.durationUs ?: 0L).coerceAtLeast(0L) / 1_000L,
                )
            }
            val video = requireNotNull(videoTrack.video)
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
        } catch (failure: Throwable) {
            // A silent probe failure hides the reason the enhanced route was never able to help.
            // Only typed, URL-free fields are recorded; Throwable.message can carry the source URL.
            val typed = failure as? YPlaybackException
            AppLog.warning(
                category = "player.core2",
                event = "enhanced_probe_failed",
                message = "YCore FFmpeg truth probe could not open the source",
                attributes =
                    mapOf(
                        "category" to (typed?.category?.name ?: failure::class.simpleName.orEmpty()),
                        "stage" to (typed?.stage?.name ?: "Unknown"),
                        "detail" to typed?.safeDetail.orEmpty(),
                        "sourceScheme" to item.uri.substringBefore(':').lowercase(),
                    ),
            )
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demuxer.close()
        }
    }
}

private val ENHANCED_AUDIO_ONLY_VIDEO_PLACEHOLDER =
    YVideoRequirement(
        codec = YVideoCodec.Unknown,
        width = 0,
        height = 0,
        frameRate = 0f,
        bitDepth = 8,
        hdrType = YHdrType.Sdr,
        surfaceOutputRequired = false,
    )

private const val DOLBY_PROBE_SAMPLE_LIMIT = 24

internal fun YCore2ProbeResult.Success.requiresEnhancedTruthProbe(): Boolean {
    val request = playbackRequest
    // A successful video probe is not proof that MediaExtractor exposed every elementary stream.
    // When it reports no audio at all, ask FFmpeg once before treating the source as video-only.
    if (request.audio == null) return true
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

/**
 * Everything a deep probe result depends on besides the bytes it reads.
 *
 * [com.yfuse.core2.network.YCacheIdentity] is preferred over the URI for the same reason as in
 * [AndroidCore2MediaProbe]: a playback URI carries a rotating play-session id that would defeat
 * the cache within a single launch.
 */
private fun YMediaItem.enhancedProbeCacheKey(): String =
    listOf(
        cacheIdentity?.let { identity -> "id:${identity.scope}/${identity.mediaId}/${identity.version}" }
            ?: "uri:$uri",
        mimeType.orEmpty(),
        (sourceHints?.dolbyVision == true).toString(),
    ).joinToString("\u0000")

private const val MAX_CACHED_ENHANCED_PROBES = 4
