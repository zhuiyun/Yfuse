package com.yfuse.core2.android

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YCapabilityProvider
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
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
    ) : YCore2ProbeResult

    data class Failure(
        val reason: YCore2ProbeFailure,
    ) : YCore2ProbeResult
}

internal data class YCore2RouteDecision(
    val probe: YCore2ProbeResult.Success,
    val plan: YPlaybackPlan,
) {
    /** Phase 1 player can execute only Platform + Hardware + SurfaceDirect today. */
    val nativeDirectExecutable: Boolean
        get() =
            plan.route == YPlaybackRoute.NativeDirect &&
                plan.demuxPath == YDemuxPath.Platform &&
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
            val videoIndex = demux.findFirstTrack("video/")
                ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.NoVideoTrack)
            val videoFormat = demux.trackFormat(videoIndex)
            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)?.lowercase()
                ?: return YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec)
            val videoCodec = videoMime.toCore2VideoCodec()
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
                    // Android's codec profile constants are not the same semantic value as the
                    // Dolby Vision bitstream profile; leave this unknown until the DV parser owns it.
                    dolbyVisionProfile = null,
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
                        // Compatibility fallback is deliberately absent here. P8.1/P8.4 fallback
                        // requires a real Dolby bitstream parser, not a MIME/profile guess.
                        fallbackHdrType = null,
                        preferTunnel = false,
                    ),
                videoMime = videoMime,
                audioMime = audioMime,
                durationMs = durationUs / 1_000L,
            )
        } catch (_: Throwable) {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable)
        } finally {
            demux.release()
        }
    }
}

/** Evaluates the probe against the current hardware/display/audio snapshot. */
internal class AndroidCore2RouteEvaluator(
    context: Context,
    private val capabilityProvider: YCapabilityProvider = AndroidYCapabilityProvider(context),
    private val strategy: YPlaybackStrategy = DefaultYPlaybackStrategy(),
) {
    private val probe = AndroidCore2MediaProbe(context)

    fun evaluate(item: YMediaItem): YCore2RouteDecision? {
        val result = probe.probe(item) as? YCore2ProbeResult.Success ?: return null
        val plan = strategy.plan(result.playbackRequest, capabilityProvider.current())
        return YCore2RouteDecision(result, plan)
    }
}

private fun YMediaItem.toProbeSource(): YAndroidMediaSource =
    YAndroidMediaSource(uri = uri, headers = headers)

private fun String.toCore2VideoCodec(): YVideoCodec? =
    when (lowercase()) {
        "video/avc" -> YVideoCodec.H264
        "video/hevc", "video/dolby-vision" -> YVideoCodec.H265
        "video/av01" -> YVideoCodec.Av1
        "video/x-vnd.on2.vp9" -> YVideoCodec.Vp9
        "video/mpeg2" -> YVideoCodec.Mpeg2
        else -> null
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

private fun YMediaItem.containerHint(): YContainer {
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

private const val COLOR_TRANSFER_ST2084 = 6
private const val COLOR_TRANSFER_HLG = 7
