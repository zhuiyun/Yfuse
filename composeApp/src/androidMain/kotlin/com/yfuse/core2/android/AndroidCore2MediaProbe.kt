package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
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
import com.yfuse.core2.quirk.YDeviceIdentity
import com.yfuse.core2.quirk.YDeviceQuirkAction
import com.yfuse.core2.quirk.YDeviceQuirkDatabase
import com.yfuse.core2.quirk.YDeviceQuirkRule
import com.yfuse.core2.quirk.YTextMatch
import com.yfuse.core2.strategy.DefaultYPlaybackStrategy
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YPlaybackStrategy
import com.yfuse.core2.strategy.YRenderPath

internal enum class YCore2ProbeFailure {
    SourceUnavailable,
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

    val nativeDirectExecutable: Boolean
        get() =
            plan.route == YPlaybackRoute.NativeDirect &&
                plan.demuxPath == YDemuxPath.Platform &&
                plan.renderPath == YRenderPath.SurfaceDirect &&
                plan.nativeAudio &&
                !plan.usesHdrFallback

    val nativeEnhancedExecutable: Boolean
        get() =
            plan.route == YPlaybackRoute.NativeEnhanced &&
                plan.demuxPath == YDemuxPath.Enhanced &&
                plan.renderPath == YRenderPath.SurfaceDirect &&
                plan.nativeAudio
}

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

    fun probe(item: YMediaItem): YCore2ProbeResult {
        val demux = AndroidMediaExtractorDemuxNode(appContext)
        return try {
            demux.open(item.toProbeSource())
            val videoIndex =
                demux.findFirstTrack("video/")
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoVideoTrack)
            val videoFormat = demux.trackFormat(videoIndex)
            val videoMime =
                videoFormat.getString(MediaFormat.KEY_MIME)?.lowercase()
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec)
            val dolbyVisionConfig = videoFormat.dolbyVisionConfigOrNull(videoMime)
            val videoCodec =
                videoMime.toCore2VideoCodec(videoFormat, dolbyVisionConfig)
                    ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec)
            val audioIndex = demux.findFirstTrack("audio/")
            val audioFormat = audioIndex?.let(demux::trackFormat)
            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)?.lowercase()

            val video =
                YVideoRequirement(
                    codec = videoCodec,
                    width = videoFormat.intOrZero(MediaFormat.KEY_WIDTH),
                    height = videoFormat.intOrZero(MediaFormat.KEY_HEIGHT),
                    frameRate = videoFormat.frameRateOrZero(),
                    bitDepth = videoFormat.bitDepth(videoMime),
                    hdrType = videoFormat.hdrType(videoMime),
                    dolbyVisionProfile = dolbyVisionConfig?.profile,
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
                        container = item.containerHint(),
                        video = video,
                        audio = audio,
                        platformDemuxSupported = true,
                        enhancedDemuxSupported = true,
                        fallbackHdrType = dolbyVisionConfig?.compatibleBaseHdr,
                        preferTunnel = true,
                    ),
                videoMime = videoMime,
                audioMime = audioMime,
                durationMs = durationUs / 1_000L,
                dolbyVisionConfig = dolbyVisionConfig,
            )
        } catch (_: Throwable) {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demux.release()
        }
    }
}

/** Evaluates the best current route against platform and bounded FFmpeg metadata truth. */
internal class AndroidCore2RouteEvaluator(
    context: Context,
    private val capabilityProvider: YCapabilityProvider = AndroidYCapabilityProvider(context),
    private val strategy: YPlaybackStrategy = DefaultYPlaybackStrategy(),
    private val enhancedProbe: AndroidEnhancedMediaProbe = AndroidEnhancedMediaProbe(),
    private val quirkDatabase: YDeviceQuirkDatabase = androidCore2QuirkDatabase(),
    private val deviceIdentity: YDeviceIdentity = androidDeviceIdentity(),
    private val codecConfigurationProbe: AndroidCodecConfigurationProbe = AndroidCodecConfigurationProbe(),
    private val codecSampleProbe: AndroidCodecSampleProbe = AndroidCodecSampleProbe(context),
) {
    private val platformProbe = AndroidCore2MediaProbe(context)
    private val runtimeCapabilities = AndroidRuntimeCapabilityRegistry(context)

    fun evaluate(
        item: YMediaItem,
        preferTunnel: Boolean = true,
        allowAudioPassthrough: Boolean = true,
    ): YCore2RouteDecision? {
        val platform = platformProbe.probe(item) as? YCore2ProbeResult.Success
        val resolved =
            when {
                platform == null -> enhancedProbe.probe(item) as? YCore2ProbeResult.Success
                platform.requiresEnhancedTruthProbe() -> {
                    val deep = enhancedProbe.probe(item) as? YCore2ProbeResult.Success
                    if (
                        deep != null &&
                        (deep.materiallyOverrides(platform) || deep.dolbyVisionStreamEvidence != null)
                    ) {
                        deep
                    } else {
                        platform
                    }
                }
                else -> platform
            } ?: return null
        val requested =
            resolved.playbackRequest.copy(
                preferTunnel = preferTunnel,
                allowAudioPassthrough = allowAudioPassthrough,
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
                )
            }
        var plan = strategy.plan(request, capabilities)
        val unseenRuntimeKey = runtimeVideoCapabilityKey(request, plan)
        if (
            unseenRuntimeKey != null &&
            plan.renderPath != YRenderPath.Tunnel &&
            runtimeCapabilities.evidence(unseenRuntimeKey) == null
        ) {
            val probeResult =
                if (plan.demuxPath == YDemuxPath.Platform) {
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
            plan =
                plan.copy(
                    reason = "${plan.reason}; ${dolbyDecision.diagnosticLabel()}",
                )
        }
        return YCore2RouteDecision(normalizedProbe, plan)
    }
}

private fun YCore2ProbeResult.Success.activeProbeMime(plan: YPlaybackPlan): String =
    if (plan.outputHdrType == YHdrType.DolbyVision) {
        videoMime
    } else {
        when (playbackRequest.video.codec) {
            YVideoCodec.H264 -> "video/avc"
            YVideoCodec.H265 -> "video/hevc"
            YVideoCodec.Av1 -> "video/av01"
            YVideoCodec.Vp9 -> "video/x-vnd.on2.vp9"
            YVideoCodec.Mpeg2 -> "video/mpeg2"
            YVideoCodec.ProRes -> "video/prores"
            YVideoCodec.Unknown -> videoMime
        }
    }

private fun YDolbyVisionRouteDecision.diagnosticLabel(): String =
    when (this) {
        is YDolbyVisionRouteDecision.Native ->
            "DV P$profile native, enhancement=$enhancementLayerKind, FEL-claim=$canClaimFelComposition"
        is YDolbyVisionRouteDecision.CompatibleBase -> "DV compatible-base=$hdrType"
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

private fun YCore2ProbeResult.Success.materiallyOverrides(platform: YCore2ProbeResult.Success): Boolean =
    dolbyVisionConfig != null &&
        platform.dolbyVisionConfig == null ||
        playbackRequest.video.hdrType != platform.playbackRequest.video.hdrType ||
        playbackRequest.video.codec != platform.playbackRequest.video.codec ||
        playbackRequest.video.bitDepth > platform.playbackRequest.video.bitDepth

private fun YMediaItem.toProbeSource(): YAndroidMediaSource = YAndroidMediaSource(uri = uri, headers = headers)

private fun String.toCore2VideoCodec(
    format: MediaFormat,
    dolbyVisionConfig: YDolbyVisionConfig?,
): YVideoCodec? =
    when (lowercase()) {
        "video/avc" -> YVideoCodec.H264
        "video/hevc" -> YVideoCodec.H265
        "video/av01" -> YVideoCodec.Av1
        "video/x-vnd.on2.vp9" -> YVideoCodec.Vp9
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
    if (mime != "video/dolby-vision" || !containsKey(CSD_2)) return null
    val source = runCatching { getByteBuffer(CSD_2) }.getOrNull() ?: return null
    val copy = source.duplicate()
    val bytes = ByteArray(copy.remaining())
    copy.get(bytes)
    return runCatching { YDolbyVisionConfig.parse(bytes) }.getOrNull()
}

private fun MediaFormat.intOrZero(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

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
    val mime = mimeType?.lowercase().orEmpty()
    if ("matroska" in mime) return YContainer.Matroska
    if ("webm" in mime) return YContainer.WebM
    if ("quicktime" in mime) return YContainer.Mov
    if ("mp2t" in mime || "mpegts" in mime) return YContainer.MpegTs
    if ("mp4" in mime) return YContainer.Mp4

    val path = uri.substringBefore('?').substringBefore('#').lowercase()
    return when {
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

private const val CSD_2 = "csd-2"
private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
