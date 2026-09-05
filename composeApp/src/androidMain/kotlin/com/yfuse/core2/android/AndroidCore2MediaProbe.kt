package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YCapabilityProvider
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.dolby.YDolbyVisionCodecFamily
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.dolby.YDolbyVisionRouteDecision
import com.yfuse.core2.dolby.YDolbyVisionRouter
import com.yfuse.core2.dolby.YDolbyVisionStreamEvidence
import com.yfuse.core2.dolby.YMatroskaDolbyVisionMetadataParser
import com.yfuse.core2.dolby.YMatroskaDolbyVisionMetadataResult
import com.yfuse.core2.dolby.YMatroskaTrackCodec
import com.yfuse.core2.dolby.YMatroskaTrackCodecParser
import com.yfuse.core2.dolby.YMatroskaTrackCodecResult
import com.yfuse.core2.quirk.YDeviceIdentity
import com.yfuse.core2.quirk.YDeviceQuirkAction
import com.yfuse.core2.quirk.YDeviceQuirkDatabase
import com.yfuse.core2.quirk.YDeviceQuirkRule
import com.yfuse.core2.quirk.YTextMatch
import com.yfuse.core2.render.YNativeGpuRuntimeProbe
import com.yfuse.core2.strategy.DefaultYPlaybackStrategy
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDecoderPreference
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YOptimizationPreference
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YPlaybackStrategy
import com.yfuse.core2.strategy.YRenderPath
import com.yfuse.core2.strategy.enhancedAudioCodecIsMoreReliable
import java.nio.ByteBuffer

internal enum class YCore2ProbeFailure {
    SourceUnavailable,
    NoPlayableTrack,
    NoVideoTrack,
    UnknownVideoCodec,
}

internal sealed interface YCore2ProbeResult {
    data class Success(
        val playbackRequest: YPlaybackRequest,
        val videoMime: String,
        val audioMime: String?,
        val durationMs: Long,
        val dolbyVisionConfig: YDolbyVisionConfig? = null,
        val dolbyVisionStreamEvidence: YDolbyVisionStreamEvidence? = null,
        /** Dolby NAL units were observed even though the container omitted its profile record. */
        val unconfiguredDolbyVisionSignal: Boolean = false,
    ) : YCore2ProbeResult

    data class Failure(
        val reason: YCore2ProbeFailure,
    ) : YCore2ProbeResult
}

internal data class YCore2RouteDecision(
    val probe: YCore2ProbeResult.Success,
    val plan: YPlaybackPlan,
) {
    val nativeTunnelExecutable: Boolean
        get() =
            plan.route == YPlaybackRoute.NativeTunnel &&
                plan.demuxPath == YDemuxPath.Platform &&
                plan.renderPath == YRenderPath.Tunnel &&
                plan.nativeAudio &&
                !plan.usesHdrFallback

    val audioOnly: Boolean get() = probe.playbackRequest.audioOnly

    val nativeDirectExecutable: Boolean
        get() =
            plan.route == YPlaybackRoute.NativeDirect &&
                plan.demuxPath == YDemuxPath.Platform &&
                plan.renderPath == YRenderPath.SurfaceDirect &&
                plan.nativeAudio &&
                !plan.usesHdrFallback

    val nativeEnhancedExecutable: Boolean
        get() =
            !audioOnly &&
                plan.route == YPlaybackRoute.NativeEnhanced &&
                plan.demuxPath == YDemuxPath.Enhanced &&
                plan.renderPath == YRenderPath.SurfaceDirect &&
                plan.nativeAudio

    // Only NativeDirect executes audio-only media. The enhanced session is built around a video
    // track and a valid Surface, so routing an audio-only source there would fail at open time;
    // leaving it non-executable lets the router fall back instead.
    val ffmpegSoftwareExecutable: Boolean
        get() =
            !audioOnly &&
                plan.route == YPlaybackRoute.SoftwareFallback &&
                plan.demuxPath == YDemuxPath.Enhanced &&
                (
                    plan.decodePath == YDecodePath.Software &&
                        plan.renderPath == YRenderPath.Gpu &&
                        !probe.playbackRequest.video.secureDecodeRequired &&
                        (
                            probe.playbackRequest.video.hdrType == YHdrType.Sdr ||
                                plan.softwareVideoToneMap &&
                                plan.outputHdrType == YHdrType.Sdr
                        ) &&
                        probe.playbackRequest.video.softwareDecodeWithinBounds() ||
                        plan.decodePath != YDecodePath.Software &&
                        plan.renderPath == YRenderPath.SurfaceDirect
                ) &&
                plan.nativeAudio
}

private fun YVideoRequirement.softwareDecodeWithinBounds(): Boolean =
    width in 1..SOFTWARE_VIDEO_MAX_WIDTH &&
        height in 1..SOFTWARE_VIDEO_MAX_HEIGHT &&
        width.toLong() * height.toLong() <= SOFTWARE_VIDEO_MAX_PIXELS &&
        (frameRate <= 0f || frameRate <= SOFTWARE_VIDEO_MAX_FRAME_RATE)

private const val SOFTWARE_VIDEO_MAX_WIDTH = 4096
private const val SOFTWARE_VIDEO_MAX_HEIGHT = 4096
private const val SOFTWARE_VIDEO_MAX_PIXELS = 4096L * 2160L
private const val SOFTWARE_VIDEO_MAX_FRAME_RATE = 60f

/**
 * Bounded metadata truth source for deciding whether one item may enter Core2.
 *
 * The probe returns only normalized capability facts. Source URIs and headers are intentionally
 * excluded from every result so diagnostics can never expose media tokens.
 */
internal class AndroidCore2MediaProbe(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val probeCacheLock = Any()

    /**
     * Successful probes for this evaluator's lifetime, newest last.
     *
     * A probe opens a MediaExtractor on the real source: for remote Matroska that is a TLS
     * handshake, the redirects behind the media URL, the container header, and a second range at
     * the file's tail for its Cues. One cold start ran that repeatedly - route evaluation, a
     * re-evaluation when the planned route was blocked, and again for every rebuild - and each
     * repeat re-read the same bytes to reach the same answer, in front of the first frame.
     *
     * Scope is deliberately one player session. The result describes the bytes at a source, so it
     * cannot go stale within a session, and nothing survives to poison the next one.
     */
    private val probeCache = LinkedHashMap<String, YCore2ProbeResult.Success>()

    fun probe(item: YMediaItem): YCore2ProbeResult {
        val cacheKey = item.probeCacheKey()
        synchronized(probeCacheLock) { probeCache[cacheKey] }?.let { cached -> return cached }
        val result = probeUncached(item)
        // Only successes are retained. A failure here is usually an unreachable source, and
        // remembering that would keep a route unplayable for the rest of the session even once
        // the network recovers.
        if (result is YCore2ProbeResult.Success) {
            synchronized(probeCacheLock) {
                probeCache.remove(cacheKey)
                probeCache[cacheKey] = result
                while (probeCache.size > MAX_CACHED_PROBES) {
                    probeCache.remove(probeCache.keys.first())
                }
            }
        }
        return result
    }

    private fun probeUncached(item: YMediaItem): YCore2ProbeResult {
        val demux = AndroidMediaExtractorDemuxNode(appContext)
        return try {
            demux.open(item.toProbeSource())
            val videoIndex =
                demux.findFirstTrack("video/")
                    ?: return demux.probeAudioOnly(item)
            val videoFormat = demux.trackFormat(videoIndex)
            val videoMime =
                videoFormat.getString(MediaFormat.KEY_MIME)?.lowercase()
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec)
            val container = item.containerHint()
            val dolbyVisionConfig =
                videoFormat.dolbyVisionConfigOrNull(videoMime)
                    ?: demux.matroskaDolbyVisionConfigOrNull(container, videoMime)
            val observedDolbyVisionNals =
                if (
                    dolbyVisionConfig == null &&
                    videoMime == MIME_HEVC &&
                    item.sourceHints?.dolbyVision == true
                ) {
                    demux.probeDolbyVisionNals(videoIndex, videoFormat)
                } else {
                    EMPTY_DOLBY_NAL_EVIDENCE
                }
            val unconfiguredDolbyVisionSignal = observedDolbyVisionNals.rpuPresent
            if (unconfiguredDolbyVisionSignal) {
                AppLog.info(
                    category = "player.core2",
                    event = "dolby_rpu_identity_recovered",
                    message = "YCore confirmed Dolby Vision RPU NAL units through MediaExtractor",
                    attributes =
                        mapOf(
                            "rpuCount" to observedDolbyVisionNals.rpuCount.toString(),
                            "enhancementLayerCount" to
                                observedDolbyVisionNals.enhancementLayerCount.toString(),
                        ),
                )
            }
            val effectiveVideoMime =
                if (dolbyVisionConfig != null || unconfiguredDolbyVisionSignal) {
                    MIME_DOLBY_VISION
                } else {
                    videoMime
                }
            val videoCodec =
                effectiveVideoMime.toCore2VideoCodec(videoFormat, dolbyVisionConfig)
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec)
            val audioIndex = demux.findFirstTrack("audio/")
            val audioFormat = audioIndex?.let(demux::trackFormat)
            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)?.lowercase()
            if (audioIndex == null && (item.sourceHints?.audioTrackCount ?: 0) > 0) {
                demux.reportHiddenPlatformAudio(item, container)
            }

            val video =
                YVideoRequirement(
                    codec = videoCodec,
                    width = videoFormat.intOrZero(MediaFormat.KEY_WIDTH),
                    height = videoFormat.intOrZero(MediaFormat.KEY_HEIGHT),
                    frameRate = videoFormat.frameRateOrZero(),
                    bitDepth = if (dolbyVisionConfig != null) 10 else videoFormat.bitDepth(videoMime),
                    hdrType = videoFormat.hdrType(effectiveVideoMime),
                    dolbyVisionProfile = dolbyVisionConfig?.profile,
                    secureDecodeRequired = item.drmConfiguration != null,
                )
            val audio =
                audioFormat?.let { format ->
                    YAudioRequirement(
                        codec = audioMime?.toYAudioCodec() ?: YAudioCodec.Unknown,
                        channelCount = format.intOrZero(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
                        sampleRate = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1),
                    )
                }
            val durationUs =
                listOfNotNull(videoFormat, audioFormat)
                    .mapNotNull(MediaFormat::durationUsOrNullForProbe)
                    .maxOrNull()
                    ?: 0L
            YCore2ProbeResult.Success(
                playbackRequest =
                    YPlaybackRequest(
                        container = container,
                        video = video,
                        audio = audio,
                        platformDemuxSupported = true,
                        enhancedDemuxSupported = true,
                        fallbackHdrType = dolbyVisionConfig?.compatibleBaseHdr,
                        preferTunnel = true,
                    ),
                videoMime = effectiveVideoMime,
                audioMime = audioMime,
                durationMs = durationUs / 1_000L,
                dolbyVisionConfig = dolbyVisionConfig,
                dolbyVisionStreamEvidence =
                    dolbyVisionConfig?.let { config ->
                        YDolbyVisionStreamEvidence(config, observedDolbyVisionNals)
                    },
                unconfiguredDolbyVisionSignal = unconfiguredDolbyVisionSignal,
            )
        } catch (_: Throwable) {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demux.release()
        }
    }

    /**
     * Probe result for a source with no video track.
     *
     * [YPlaybackRequest.video] carries a neutral placeholder that the audio-only planner never
     * reads; it exists only so the request stays a single non-null shape for every caller.
     */
    private fun AndroidMediaExtractorDemuxNode.probeAudioOnly(item: YMediaItem): YCore2ProbeResult {
        val audioIndex =
            findFirstTrack("audio/")
                ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoPlayableTrack)
        val audioFormat = trackFormat(audioIndex)
        val audioMime = audioFormat.getString(MediaFormat.KEY_MIME)?.lowercase()
        val durationUs = audioFormat.durationUsOrNullForProbe() ?: 0L
        return YCore2ProbeResult.Success(
            playbackRequest =
                YPlaybackRequest(
                    container = item.containerHint(),
                    video = AUDIO_ONLY_VIDEO_PLACEHOLDER,
                    audio =
                        YAudioRequirement(
                            codec = audioMime?.toYAudioCodec() ?: YAudioCodec.Unknown,
                            channelCount =
                                audioFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1),
                            sampleRate =
                                audioFormat.intOrZero(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1),
                        ),
                    audioOnly = true,
                    platformDemuxSupported = true,
                    enhancedDemuxSupported = true,
                    preferTunnel = false,
                ),
            videoMime = "",
            audioMime = audioMime,
            durationMs = durationUs / 1_000L,
        )
    }
}

private val AUDIO_ONLY_VIDEO_PLACEHOLDER =
    YVideoRequirement(
        codec = YVideoCodec.Unknown,
        width = 0,
        height = 0,
        frameRate = 0f,
        bitDepth = 8,
        hdrType = YHdrType.Sdr,
        surfaceOutputRequired = false,
    )

private fun AndroidMediaExtractorDemuxNode.probeDolbyVisionNals(
    videoTrackIndex: Int,
    videoFormat: MediaFormat,
): YDolbyVisionNalEvidence {
    selectTrack(videoTrackIndex)
    val buffer =
        ByteBuffer.allocateDirect(
            videoFormat
                .maxInputSizeOr(DOLBY_SAMPLE_PROBE_DEFAULT_BYTES)
                .coerceIn(DOLBY_SAMPLE_PROBE_MIN_BYTES, DOLBY_SAMPLE_PROBE_MAX_BYTES),
        )
    var rpuCount = 0
    var enhancementLayerCount = 0
    for (ignored in 0 until DOLBY_SAMPLE_PROBE_COUNT) {
        val sample = readSample(buffer) ?: break
        val bytes = ByteArray(sample.data.remaining())
        sample.data.duplicate().get(bytes)
        val evidence =
            DOLBY_SAMPLE_PACKINGS
                .mapNotNull { packing ->
                    runCatching { YBitstream.dolbyVisionEvidence(bytes, packing) }.getOrNull()
                }.maxByOrNull { it.rpuCount + it.enhancementLayerCount }
        rpuCount += evidence?.rpuCount ?: 0
        enhancementLayerCount += evidence?.enhancementLayerCount ?: 0
        if (rpuCount > 0) return YDolbyVisionNalEvidence(rpuCount, enhancementLayerCount)
        if (!advance()) break
    }
    return YDolbyVisionNalEvidence(rpuCount, enhancementLayerCount)
}

private fun AndroidMediaExtractorDemuxNode.matroskaDolbyVisionConfigOrNull(
    container: YContainer,
    videoMime: String,
): YDolbyVisionConfig? {
    if (container != YContainer.Matroska || videoMime !in MATROSKA_DOLBY_BASE_MIME_TYPES) return null
    for (maximumBytes in MATROSKA_METADATA_PROBE_BYTES) {
        val bytes = readSourcePrefix(maximumBytes) ?: return null
        when (val result = YMatroskaDolbyVisionMetadataParser.parse(bytes)) {
            is YMatroskaDolbyVisionMetadataResult.Found -> {
                AppLog.info(
                    category = "player.core2",
                    event = "matroska_dolby_config_recovered",
                    message = "YCore recovered Dolby Vision configuration from Matroska metadata",
                    attributes =
                        mapOf(
                            "profile" to
                                result.metadata.config.profile
                                    .toString(),
                            "codec" to result.metadata.codecId,
                            "mappingType" to result.metadata.blockAddIdType.toString(16),
                        ),
                )
                return result.metadata.config
            }
            YMatroskaDolbyVisionMetadataResult.Absent,
            YMatroskaDolbyVisionMetadataResult.Invalid,
            -> return null
            YMatroskaDolbyVisionMetadataResult.Truncated -> Unit
        }
        if (bytes.size < maximumBytes) return null
    }
    return null
}

/**
 * Names the audio track MediaExtractor hid.
 *
 * The platform extractor drops any Matroska audio CodecID it does not map, silently. The server
 * knows what it declared and the container header knows what it carries; recording both is what
 * turns "did not expose a server-declared audio track" into an actionable codec name.
 */
private fun AndroidMediaExtractorDemuxNode.reportHiddenPlatformAudio(
    item: YMediaItem,
    container: YContainer,
) {
    val hints = item.sourceHints
    val matroskaCodecIds =
        if (container == YContainer.Matroska) matroskaTrackCodecIdsOrNull() else null
    AppLog.warning(
        category = "player.core2",
        event = "platform_audio_track_hidden",
        message = "MediaExtractor exposed no audio track for a source that declares one",
        attributes =
            mapOf(
                "container" to container.name,
                "serverAudioTracks" to (hints?.audioTrackCount ?: 0).toString(),
                "serverAudioCodecs" to hints?.audioCodecs.orEmpty().joinToString(","),
                "matroskaAudioCodecIds" to
                    matroskaCodecIds
                        ?.filter(YMatroskaTrackCodec::audio)
                        ?.joinToString(",", transform = YMatroskaTrackCodec::codecId)
                        .orEmpty(),
                "matroskaTrackCodecIds" to
                    matroskaCodecIds
                        ?.joinToString(",") { track -> "${track.trackType}:${track.codecId}" }
                        .orEmpty(),
            ),
    )
}

private fun AndroidMediaExtractorDemuxNode.matroskaTrackCodecIdsOrNull(): List<YMatroskaTrackCodec>? {
    for (maximumBytes in MATROSKA_METADATA_PROBE_BYTES) {
        val bytes = runCatching { readSourcePrefix(maximumBytes) }.getOrNull() ?: return null
        when (val result = YMatroskaTrackCodecParser.parse(bytes)) {
            is YMatroskaTrackCodecResult.Found -> return result.tracks
            YMatroskaTrackCodecResult.Invalid -> return null
            YMatroskaTrackCodecResult.Truncated -> Unit
        }
        if (bytes.size < maximumBytes) return null
    }
    return null
}

/** Evaluates the best current route against platform and bounded FFmpeg metadata truth. */
internal class AndroidCore2RouteEvaluator(
    context: Context,
    private val decoderPreference: YDecoderPreference = YDecoderPreference.Automatic,
    private val optimizationPreference: YOptimizationPreference = YOptimizationPreference.Balanced,
    private val capabilityProvider: YCapabilityProvider = AndroidYCapabilityProvider(context),
    private val strategy: YPlaybackStrategy = DefaultYPlaybackStrategy(),
    private val enhancedProbe: AndroidEnhancedMediaProbe = AndroidEnhancedMediaProbe(),
    private val quirkDatabase: YDeviceQuirkDatabase = androidCore2QuirkDatabase(),
    private val deviceIdentity: YDeviceIdentity = androidDeviceIdentity(),
    private val codecConfigurationProbe: AndroidCodecConfigurationProbe = AndroidCodecConfigurationProbe(),
    private val codecSampleProbe: AndroidCodecSampleProbe = AndroidCodecSampleProbe(context),
    private val nativeGpuRuntimeProbe: YNativeGpuRuntimeProbe = AndroidYCoreGpuRuntime.probe(context),
) {
    private val platformProbe = AndroidCore2MediaProbe(context)
    private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)

    /** Preserves exact platform/container evidence when capability routing cannot advertise a plan. */
    fun probePlatformForNativeAttempt(item: YMediaItem): YCore2ProbeResult.Success? =
        (platformProbe.probe(item) as? YCore2ProbeResult.Success)
            ?.withConfirmedDolbyVisionSourceHint(item)

    fun evaluate(
        item: YMediaItem,
        preferTunnel: Boolean = true,
        allowAudioPassthrough: Boolean = true,
        forcePowerSaver: Boolean = false,
    ): YCore2RouteDecision? {
        val platform =
            (platformProbe.probe(item) as? YCore2ProbeResult.Success)
                ?.withConfirmedDolbyVisionSourceHint(item)
        val sourceClaimsDolbyVision = item.sourceHints?.dolbyVision == true
        val resolved =
            when {
                platform == null && item.drmConfiguration != null -> null
                platform == null ->
                    (enhancedProbe.probe(item) as? YCore2ProbeResult.Success)
                        ?.takeUnless { it.unconfiguredDolbyVisionSignal }
                item.drmConfiguration != null ->
                    platform.takeUnless {
                        sourceClaimsDolbyVision && platform.dolbyVisionConfig == null
                    }
                platform.requiresEnhancedTruthProbe(item) -> {
                    val requiresDolbyProfileTruth =
                        sourceClaimsDolbyVision ||
                            platform.playbackRequest.video.hdrType == YHdrType.DolbyVision &&
                            platform.dolbyVisionConfig == null
                    val deep =
                        (enhancedProbe.probe(item) as? YCore2ProbeResult.Success)
                            ?.preservingPlatformDemuxCapability(platform)
                    when {
                        deep?.dolbyVisionConfig != null -> deep
                        deep?.unconfiguredDolbyVisionSignal == true -> return null
                        // Server metadata is only a hint. When the bounded FFmpeg/NAL probe sees
                        // ordinary HEVC/HDR and no Dolby signal, trust the local bitstream truth
                        // instead of permanently blocking a mislabeled source.
                        requiresDolbyProfileTruth && deep != null -> deep
                        // The platform identified Dolby Vision but did not expose its configuration.
                        // Do not let a generic HEVC plan bypass the exact-profile Dolby router.
                        requiresDolbyProfileTruth ->
                            platform.takeIf { it.dolbyVisionConfig != null } ?: return null
                        deep != null &&
                            (
                                deep.materiallyOverrides(platform) ||
                                    deep.dolbyVisionStreamEvidence != null
                            ) -> deep
                        else -> platform
                    }
                }
                else -> platform
            } ?: return null
        val requested =
            resolved.playbackRequest.copy(
                enhancedDemuxSupported = resolved.playbackRequest.enhancedDemuxSupported,
                sourceDeclaresAudio = (item.sourceHints?.audioTrackCount ?: 0) > 0,
                preferTunnel = preferTunnel && !resolved.unconfiguredDolbyVisionSignal,
                allowAudioPassthrough = allowAudioPassthrough,
                decoderPreference =
                    if (forcePowerSaver) {
                        YDecoderPreference.HardwarePreferred
                    } else {
                        decoderPreference
                    },
                optimizationPreference =
                    if (forcePowerSaver) {
                        YOptimizationPreference.PowerSaver
                    } else {
                        optimizationPreference
                    },
            )
        val adjustment = quirkDatabase.adjust(deviceIdentity, requested, capabilityProvider.current())
        val request = adjustment.request
        val normalizedProbe = resolved.copy(playbackRequest = request)
        var capabilities = adjustment.capabilities
        val dolbyDecision =
            resolved.dolbyVisionConfig?.let { config ->
                YDolbyVisionRouter.decide(
                    video = request.video,
                    evidence = resolved.dolbyVisionStreamEvidence ?: YDolbyVisionStreamEvidence(config),
                    capabilities = capabilities,
                    gpuProcessingSupported = nativeGpuRuntimeProbe.canAttemptNativeVulkan,
                )
            }
        // Dolby routing is authoritative, not a diagnostic decoration. A profile/display/decoder
        // mismatch must not fall through to the generic HEVC planner and produce a green picture.
        if (dolbyDecision is YDolbyVisionRouteDecision.Unsupported) return null
        var plan = strategy.plan(request, capabilities)
        val unseenRuntimeKey = runtimeVideoCapabilityKey(request, plan)
        if (
            unseenRuntimeKey != null &&
            plan.renderPath != YRenderPath.Tunnel &&
            runtimeCapabilities.evidence(unseenRuntimeKey) == null
        ) {
            val probeResult =
                if (item.drmConfiguration != null) {
                    YCodecConfigurationProbeResult.Inconclusive
                } else if (plan.demuxPath == YDemuxPath.Platform) {
                    codecSampleProbe.probe(item, unseenRuntimeKey.decoderName)
                } else {
                    codecConfigurationProbe.probe(
                        decoderName = unseenRuntimeKey.decoderName,
                        mimeType = normalizedProbe.activeProbeMime(plan),
                        requirement = request.video,
                    )
                }
            when (probeResult) {
                YCodecConfigurationProbeResult.Rendered ->
                    runtimeCapabilities.recordRendered(unseenRuntimeKey)
                YCodecConfigurationProbeResult.Configured ->
                    runtimeCapabilities.recordConfigured(unseenRuntimeKey)
                YCodecConfigurationProbeResult.Rejected -> {
                    runtimeCapabilities.recordRejected(unseenRuntimeKey)
                    capabilities =
                        capabilities.copy(
                            videoDecoders =
                                capabilities.videoDecoders.filterNot {
                                    it.name == unseenRuntimeKey.decoderName
                                },
                        )
                    plan = strategy.plan(request, capabilities)
                }
                YCodecConfigurationProbeResult.Inconclusive -> Unit
            }
        }
        while (true) {
            val runtimeKey = runtimeVideoCapabilityKey(request, plan) ?: break
            if (!runtimeCapabilities.isRejected(runtimeKey)) break
            val remaining = capabilities.videoDecoders.filterNot { it.name == runtimeKey.decoderName }
            if (remaining.size == capabilities.videoDecoders.size) break
            capabilities = capabilities.copy(videoDecoders = remaining)
            plan = strategy.plan(request, capabilities)
        }
        if (adjustment.matchedRuleIds.isNotEmpty()) {
            plan =
                plan.copy(
                    reason =
                        "${plan.reason}; device rules=" +
                            adjustment.matchedRuleIds.sorted().joinToString(","),
                )
        }
        if (dolbyDecision != null) {
            val dolbyLabel =
                if (plan.usesHdrFallback && plan.softwareVideoToneMap) {
                    "DV compatible base tone-mapped to SDR"
                } else {
                    dolbyDecision.diagnosticLabel()
                }
            plan =
                plan.copy(
                    reason = "${plan.reason}; $dolbyLabel",
                )
        }
        if (forcePowerSaver) {
            plan = plan.copy(reason = "${plan.reason}; severe thermal power-saver override")
        }
        return YCore2RouteDecision(normalizedProbe, plan)
    }
}

private fun YCore2ProbeResult.Success.activeProbeMime(plan: YPlaybackPlan): String =
    if (!plan.usesHdrFallback && playbackRequest.video.hdrType == YHdrType.DolbyVision) {
        videoMime
    } else {
        when (playbackRequest.video.codec) {
            YVideoCodec.H264 -> "video/avc"
            YVideoCodec.H265 -> "video/hevc"
            YVideoCodec.Av1 -> "video/av01"
            YVideoCodec.Vp9 -> "video/x-vnd.on2.vp9"
            YVideoCodec.Vc1 -> "video/wvc1"
            YVideoCodec.Mpeg2 -> "video/mpeg2"
            YVideoCodec.ProRes -> "video/prores"
            YVideoCodec.Unknown -> videoMime
        }
    }

private fun YDolbyVisionRouteDecision.diagnosticLabel(): String =
    when (this) {
        is YDolbyVisionRouteDecision.Native ->
            "DV P$profile native, enhancement=$enhancementLayerKind, FEL-claim=$canClaimFelComposition"
        is YDolbyVisionRouteDecision.GpuDecoded ->
            "DV P$profile MediaCodec decoded to Vulkan, enhancement=$enhancementLayerKind"
        is YDolbyVisionRouteDecision.CompatibleBase ->
            "DV compatible-base=$hdrType, GPU=$gpuProcessed"
        is YDolbyVisionRouteDecision.Unsupported -> "DV unsupported=$reason"
    }

private fun androidDeviceIdentity(): YDeviceIdentity =
    YDeviceIdentity(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else Build.HARDWARE.orEmpty(),
        androidApi = Build.VERSION.SDK_INT,
    )

/** Conservative built-in rules. Product/device lab results can append versioned rules here. */
private fun androidCore2QuirkDatabase(): YDeviceQuirkDatabase =
    YDeviceQuirkDatabase(
        rules =
            listOf(
                YDeviceQuirkRule(
                    id = "platform-software-c2-no-tunnel-v1",
                    decoder = YTextMatch.Prefix("c2.android."),
                    actions = setOf(YDeviceQuirkAction.DisableTunnel),
                ),
                YDeviceQuirkRule(
                    id = "platform-software-omx-no-tunnel-v1",
                    decoder = YTextMatch.Prefix("OMX.google."),
                    actions = setOf(YDeviceQuirkAction.DisableTunnel),
                ),
            ),
    )

internal fun YCore2RouteDecision.runtimeCapabilityKey(): YRuntimeVideoCapabilityKey? =
    runtimeVideoCapabilityKey(probe.playbackRequest, plan)

internal fun YCore2ProbeResult.Success.materiallyOverrides(platform: YCore2ProbeResult.Success): Boolean =
    dolbyVisionConfig != null &&
        platform.dolbyVisionConfig == null ||
        playbackRequest.video.hdrType != platform.playbackRequest.video.hdrType ||
        playbackRequest.video.codec != platform.playbackRequest.video.codec ||
        playbackRequest.video.bitDepth > platform.playbackRequest.video.bitDepth ||
        playbackRequest.audio.hasReliableCodecWhen(platform.playbackRequest.audio)

/**
 * Enriches a successful platform probe with FFmpeg metadata without erasing an already proven
 * platform demux path.
 *
 * The enhanced probe describes the capabilities of the probe that produced its metadata, so its
 * request correctly has [YPlaybackRequest.platformDemuxSupported] set to false. When that result is
 * used to refine a separate successful MediaExtractor probe (for example, to identify AC-3 that an
 * OEM extractor exposed as `audio/unknown`), the combined source supports both demuxers. Treating
 * the deep-probe flag as source truth unnecessarily routed otherwise playable MKV files back through
 * FFmpeg and made a metadata improvement change the actual playback transport.
 */
internal fun YCore2ProbeResult.Success.preservingPlatformDemuxCapability(
    platform: YCore2ProbeResult.Success,
): YCore2ProbeResult.Success =
    copy(
        playbackRequest =
            playbackRequest.copy(
                platformDemuxSupported =
                    playbackRequest.platformDemuxSupported ||
                        platform.playbackRequest.platformDemuxSupported,
                platformAudioDemuxSupported =
                    platform.playbackRequest.audio != null &&
                        platform.playbackRequest.platformAudioDemuxSupported,
                enhancedDemuxSupported =
                    playbackRequest.enhancedDemuxSupported ||
                        platform.playbackRequest.enhancedDemuxSupported,
            ),
    )

private fun YAudioRequirement?.hasReliableCodecWhen(platform: YAudioRequirement?): Boolean =
    enhancedAudioCodecIsMoreReliable(
        platformCodec = platform?.codec,
        enhancedCodec = this?.codec,
    )

/**
 * Everything a probe result depends on besides the bytes it reads.
 *
 * [com.yfuse.core2.network.YCacheIdentity] is the credential-free content identity and is
 * preferred, because a playback URI carries a rotating play-session id that would defeat the
 * cache within a single launch. The remaining fields are the item facts the probe branches on.
 */
private fun YMediaItem.probeCacheKey(): String =
    listOf(
        cacheIdentity?.let { identity -> "id:${identity.scope}/${identity.mediaId}/${identity.version}" }
            ?: "uri:$uri",
        mimeType.orEmpty(),
        (drmConfiguration != null).toString(),
        (sourceHints?.dolbyVision == true).toString(),
        (disc != null).toString(),
    ).joinToString("\u0000")

private const val MAX_CACHED_PROBES = 4

private fun YMediaItem.toProbeSource(): YAndroidMediaSource =
    YAndroidMediaSource(
        uri = uri,
        headers = headers,
        credentials = transportCredentials,
        cacheIdentity = cacheIdentity,
        cacheMaximumBytes = cacheMaximumBytes,
    )

/**
 * A server-confirmed P5 tag is sufficient to prevent the platform extractor from silently
 * downgrading the track to generic HEVC. Other profiles still require full local container truth
 * because their enhancement/base-layer shape cannot be inferred from a profile number alone.
 */
internal fun YCore2ProbeResult.Success.withConfirmedDolbyVisionSourceHint(
    item: YMediaItem,
): YCore2ProbeResult.Success {
    val hints = item.sourceHints ?: return this
    if (!hints.dolbyVision || hints.dolbyVisionProfile != 5 || dolbyVisionConfig != null) return this
    val config =
        YDolbyVisionConfig(
            versionMajor = 1,
            versionMinor = 0,
            profile = 5,
            level = 0,
            rpuPresent = hints.dolbyRpuPresent != false,
            enhancementLayerPresent = false,
            baseLayerPresent = hints.dolbyBaseLayerPresent != false,
            baseLayerCompatibilityId = 0,
            metadataCompression = 0,
        )
    return copy(
        playbackRequest =
            playbackRequest.copy(
                video =
                    playbackRequest.video.copy(
                        codec = YVideoCodec.H265,
                        bitDepth = maxOf(playbackRequest.video.bitDepth, 10),
                        hdrType = YHdrType.DolbyVision,
                        dolbyVisionProfile = 5,
                    ),
                fallbackHdrType = null,
            ),
        videoMime = MIME_DOLBY_VISION,
        dolbyVisionConfig = config,
        dolbyVisionStreamEvidence = YDolbyVisionStreamEvidence(config),
    )
}

private fun String.toCore2VideoCodec(
    format: MediaFormat,
    dolbyVisionConfig: YDolbyVisionConfig?,
): YVideoCodec? =
    when (lowercase()) {
        "video/avc" -> YVideoCodec.H264
        "video/hevc" -> YVideoCodec.H265
        "video/av01" -> YVideoCodec.Av1
        "video/x-vnd.on2.vp9" -> YVideoCodec.Vp9
        "video/wvc1", "video/vc1", "video/x-ms-wmv" -> YVideoCodec.Vc1
        "video/mpeg2" -> YVideoCodec.Mpeg2
        "video/dolby-vision" ->
            when (dolbyVisionConfig?.codecFamily) {
                YDolbyVisionCodecFamily.Hevc -> YVideoCodec.H265
                YDolbyVisionCodecFamily.Avc -> YVideoCodec.H264
                YDolbyVisionCodecFamily.Av1 -> YVideoCodec.Av1
                YDolbyVisionCodecFamily.Unknown, null -> format.dolbyVisionCodecFromPlatformProfile()
            }
        else -> null
    }

private fun MediaFormat.dolbyVisionCodecFromPlatformProfile(): YVideoCodec {
    val platformProfile = intOrZero(MediaFormat.KEY_PROFILE)
    return when (platformProfile) {
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110 -> YVideoCodec.Av1
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe -> YVideoCodec.H264
        else -> YVideoCodec.H265
    }
}

/** AOSP MP4 extraction exposes dvcC/dvvC/dvwC as the opaque `csd-2` MediaFormat buffer. */
private fun MediaFormat.dolbyVisionConfigOrNull(mime: String): YDolbyVisionConfig? {
    if (mime != MIME_DOLBY_VISION) return null
    if (containsKey(CSD_2)) {
        val parsed =
            runCatching {
                val source = checkNotNull(getByteBuffer(CSD_2)).duplicate()
                val bytes = ByteArray(source.remaining())
                source.get(bytes)
                YDolbyVisionConfig.parse(bytes)
            }.getOrNull()
        if (parsed != null) return parsed
    }
    // Matroska extractors on several Android builds expose the semantic profile in KEY_PROFILE
    // but omit dvcC/dvvC. P5 is single-layer and has no compatible ordinary-HEVC fallback, so its
    // minimal configuration can be reconstructed without inventing EL/FEL facts.
    val profile = intOrZero(MediaFormat.KEY_PROFILE).toSemanticDolbyVisionProfile()
    if (profile != 5) return null
    return YDolbyVisionConfig(
        versionMajor = 1,
        versionMinor = 0,
        profile = profile,
        level = 0,
        rpuPresent = true,
        enhancementLayerPresent = false,
        baseLayerPresent = true,
        baseLayerCompatibilityId = 0,
        metadataCompression = 0,
    )
}

private fun MediaFormat.intOrZero(key: String): Int =
    if (containsKey(key)) {
        runCatching { getInteger(key) }
            .getOrDefault(0)
    } else {
        0
    }

private fun MediaFormat.frameRateOrZero(): Float {
    if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
    return runCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }
        .recoverCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
        .getOrDefault(0f)
}

private fun MediaFormat.durationUsOrNullForProbe(): Long? =
    if (containsKey(MediaFormat.KEY_DURATION)) runCatching { getLong(MediaFormat.KEY_DURATION) }.getOrNull() else null

private fun MediaFormat.hdrType(mime: String): YHdrType {
    if (mime == "video/dolby-vision") return YHdrType.DolbyVision
    val transfer = intOrZero(MediaFormat.KEY_COLOR_TRANSFER)
    return when (transfer) {
        COLOR_TRANSFER_ST2084 ->
            if (containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) YHdrType.Hdr10Plus else YHdrType.Hdr10
        COLOR_TRANSFER_HLG -> YHdrType.Hlg
        else -> YHdrType.Sdr
    }
}

private fun MediaFormat.bitDepth(mime: String): Int {
    if (mime == "video/dolby-vision") return 10
    val profile = intOrZero(MediaFormat.KEY_PROFILE)
    return when (mime) {
        "video/hevc" ->
            if (
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            ) {
                10
            } else {
                8
            }
        "video/av01" ->
            if (
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            ) {
                10
            } else {
                8
            }
        "video/x-vnd.on2.vp9" ->
            if (
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            ) {
                10
            } else {
                8
            }
        "video/avc" ->
            if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) 10 else 8
        else -> 8
    }
}

internal fun YMediaItem.containerHint(): YContainer {
    val sourceContainer = sourceHints?.container?.lowercase().orEmpty()
    if ("matroska" in sourceContainer || sourceContainer == "mkv") return YContainer.Matroska
    if ("webm" in sourceContainer) return YContainer.WebM
    if ("mpegts" in sourceContainer || "mpeg-ts" in sourceContainer) return YContainer.MpegTs
    if ("mp4" in sourceContainer) return YContainer.Mp4

    val mime = mimeType?.lowercase().orEmpty()
    if ("matroska" in mime) return YContainer.Matroska
    if ("webm" in mime) return YContainer.WebM
    if ("quicktime" in mime) return YContainer.Mov
    if ("mp2t" in mime || "mpegts" in mime) return YContainer.MpegTs
    if ("mp4" in mime) return YContainer.Mp4

    val path = uri.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".mpd") -> YContainer.Mp4
        path.endsWith(".m3u8") -> YContainer.MpegTs
        path.endsWith(".mkv") -> YContainer.Matroska
        path.endsWith(".webm") -> YContainer.WebM
        path.endsWith(".mov") -> YContainer.Mov
        path.endsWith(".m2ts") || path.endsWith(".mts") -> YContainer.M2ts
        path.endsWith(".ts") -> YContainer.MpegTs
        path.endsWith(".iso") -> YContainer.Iso
        path.endsWith(".mp4") || path.endsWith(".m4v") -> YContainer.Mp4
        else -> YContainer.Unknown
    }
}

internal fun MediaFormat.applyDolbyVisionConfiguration(config: YDolbyVisionConfig): MediaFormat =
    apply {
        setString(MediaFormat.KEY_MIME, MIME_DOLBY_VISION)
        config.profile.toAndroidDolbyVisionProfile()?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        if (!containsKey(CSD_2)) {
            setByteBuffer(CSD_2, ByteBuffer.wrap(config.toConfigurationBytes()))
        }
    }

private const val MIME_DOLBY_VISION = "video/dolby-vision"
private const val MIME_HEVC = "video/hevc"
private const val CSD_2 = "csd-2"
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
private val MATROSKA_DOLBY_BASE_MIME_TYPES =
    setOf("video/hevc", "video/avc", "video/av01", MIME_DOLBY_VISION)
private val MATROSKA_METADATA_PROBE_BYTES = intArrayOf(512 * 1024, 4 * 1024 * 1024)
private const val DOLBY_SAMPLE_PROBE_COUNT = 24
private const val DOLBY_SAMPLE_PROBE_MIN_BYTES = 256 * 1024
private const val DOLBY_SAMPLE_PROBE_DEFAULT_BYTES = 2 * 1024 * 1024
private const val DOLBY_SAMPLE_PROBE_MAX_BYTES = 32 * 1024 * 1024
private val DOLBY_SAMPLE_PACKINGS =
    listOf(
        YSamplePacking.AnnexB,
        YSamplePacking.LengthPrefixed(4),
        YSamplePacking.LengthPrefixed(2),
        YSamplePacking.LengthPrefixed(1),
    )
private val EMPTY_DOLBY_NAL_EVIDENCE = YDolbyVisionNalEvidence(0, 0)
