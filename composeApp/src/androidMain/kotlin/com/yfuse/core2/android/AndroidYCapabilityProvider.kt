package com.yfuse.core2.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YCapabilityProvider
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability

/**
 * Runtime Android capability snapshot. This is intentionally evidence-first: codec MIME/profile
 * claims and display HDR types are recorded separately, so Strategy never assumes a Dolby/HDR
 * output simply because the source metadata says one exists.
 */
internal class AndroidYCapabilityProvider(
    context: Context,
) : YCapabilityProvider {
    private val appContext = context.applicationContext

    override fun current(): YDeviceCapabilities {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val videoDecoders = queryVideoDecoders(codecInfos)
        return YDeviceCapabilities(
            videoDecoders = videoDecoders,
            audioDecoders = queryAudioDecoders(codecInfos),
            displayHdrTypes = queryDisplayHdrTypes(),
            supportsSurfaceDirect = true,
            supportsTunnel = videoDecoders.any { it.tunneledPlayback },
        )
    }

    private fun queryVideoDecoders(codecInfos: Array<MediaCodecInfo>): List<YVideoDecoderCapability> =
        codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .filter(MediaCodecInfo::isHardwareDecoderCompat)
            .flatMap { info ->
                info.supportedTypes
                    .asSequence()
                    .flatMap { type -> info.toYDecoders(type).asSequence() }
            }.toList()

    private fun queryAudioDecoders(codecInfos: Array<MediaCodecInfo>): Set<YAudioCodec> =
        codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .flatMap { info -> info.supportedTypes.asSequence() }
            .mapNotNull { type -> type.lowercase().toYAudioCodec() }
            .toSet()

    private fun MediaCodecInfo.toYDecoders(type: String): List<YVideoDecoderCapability> {
        val normalizedType = type.lowercase()
        val capabilities = runCatching { getCapabilitiesForType(type) }.getOrNull() ?: return emptyList()
        val profileLevels = capabilities.profileLevels.toList()
        val profiles = profileLevels.map { it.profile }
        val videoCapabilities = runCatching { capabilities.videoCapabilities }.getOrNull()
        val tunneled =
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
        val adaptive =
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)

        fun capability(
            codec: YVideoCodec,
            rawProfileSet: Set<Int>,
            dolbyProfiles: Set<Int> = emptySet(),
        ): YVideoDecoderCapability =
            YVideoDecoderCapability(
                name = name,
                codec = codec,
                hdrTypes = decoderHdrTypes(normalizedType, profiles),
                rawProfiles = rawProfileSet,
                dolbyVisionProfiles = dolbyProfiles,
                maxWidth = videoCapabilities?.supportedWidths?.upper ?: 0,
                maxHeight = videoCapabilities?.supportedHeights?.upper ?: 0,
                maxFrameRate = videoCapabilities?.supportedFrameRates?.upper?.toDouble() ?: 0.0,
                maxBitDepth = decoderMaxBitDepth(normalizedType, profiles),
                tunneledPlayback = tunneled,
                adaptivePlayback = adaptive,
            )

        if (normalizedType != MIME_DOLBY_VISION) {
            val codec = normalizedType.toYVideoCodec() ?: return emptyList()
            return listOf(capability(codec = codec, rawProfileSet = profiles.toSet()))
        }

        val semanticByRaw =
            profileLevels.mapNotNull { profileLevel ->
                profileLevel.profile.toSemanticDolbyVisionProfile()?.let { semantic ->
                    profileLevel.profile to semantic
                }
            }
        return semanticByRaw
            .groupBy { (_, semantic) -> semantic.toDolbyVisionCodecFamily() }
            .mapNotNull { (codec, entries) ->
                codec ?: return@mapNotNull null
                capability(
                    codec = codec,
                    rawProfileSet = entries.mapTo(mutableSetOf()) { it.first },
                    dolbyProfiles = entries.mapTo(mutableSetOf()) { it.second },
                )
            }
    }

    private fun queryDisplayHdrTypes(): Set<YHdrType> {
        val display =
            appContext
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return setOf(YHdrType.Sdr)
        val platformTypes = runCatching { display.hdrCapabilities.supportedHdrTypes }.getOrDefault(intArrayOf())
        return buildSet {
            add(YHdrType.Sdr)
            platformTypes.forEach { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> add(YHdrType.DolbyVision)
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> add(YHdrType.Hdr10)
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> add(YHdrType.Hdr10Plus)
                    Display.HdrCapabilities.HDR_TYPE_HLG -> add(YHdrType.Hlg)
                }
            }
        }
    }
}

private fun MediaCodecInfo.isHardwareDecoderCompat(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isHardwareAccelerated
    val normalized = name.lowercase()
    return !normalized.startsWith("omx.google.") &&
        !normalized.startsWith("c2.android.") &&
        !normalized.startsWith("c2.google.") &&
        !normalized.contains("software") &&
        !normalized.contains("sw.decoder")
}

/** Android raw profile bits -> semantic Dolby Vision bitstream profile numbers. */
internal fun Int.toSemanticDolbyVisionProfile(): Int? =
    when (this) {
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer -> 0
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen -> 1
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer -> 2
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen -> 3
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr -> 4
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn -> 5
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth -> 6
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb -> 7
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt -> 8
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe -> 9
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110 -> 10
        else -> null
    }

private fun Int.toDolbyVisionCodecFamily(): YVideoCodec? =
    when (this) {
        0, 1, 9 -> YVideoCodec.H264
        2, 3, 4, 5, 6, 7, 8 -> YVideoCodec.H265
        10 -> YVideoCodec.Av1
        else -> null
    }

private fun decoderHdrTypes(
    mimeType: String,
    profiles: List<Int>,
): Set<YHdrType> =
    buildSet {
        add(YHdrType.Sdr)
        when (mimeType) {
            MIME_DOLBY_VISION -> add(YHdrType.DolbyVision)
            "video/hevc" -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                ) {
                    add(YHdrType.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                ) {
                    add(YHdrType.Hdr10)
                }
                if (MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus in profiles) {
                    add(YHdrType.Hdr10Plus)
                }
            }
            "video/x-vnd.on2.vp9" -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                    }
                ) {
                    add(YHdrType.Hdr10)
                    add(YHdrType.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                            it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                    }
                ) {
                    add(YHdrType.Hdr10Plus)
                }
            }
            "video/av01" -> {
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    }
                ) {
                    add(YHdrType.Hlg)
                }
                if (
                    profiles.any {
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                            it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    }
                ) {
                    add(YHdrType.Hdr10)
                }
                if (MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus in profiles) {
                    add(YHdrType.Hdr10Plus)
                }
            }
        }
    }

private fun decoderMaxBitDepth(
    mimeType: String,
    profiles: List<Int>,
): Int =
    when (mimeType) {
        MIME_DOLBY_VISION -> 10
        "video/hevc" ->
            if (
                profiles.any {
                    it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
            ) {
                10
            } else {
                8
            }
        "video/x-vnd.on2.vp9" ->
            if (
                profiles.any {
                    it == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile3 ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus ||
                        it == MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
                }
            ) {
                10
            } else {
                8
            }
        "video/av01" ->
            if (
                profiles.any {
                    it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 ||
                        it == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                }
            ) {
                10
            } else {
                8
            }
        "video/avc" ->
            if (MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 in profiles) 10 else 8
        else -> 8
    }

private fun String.toYVideoCodec(): YVideoCodec? =
    when (this) {
        "video/avc" -> YVideoCodec.H264
        "video/hevc" -> YVideoCodec.H265
        "video/av01" -> YVideoCodec.Av1
        "video/x-vnd.on2.vp9" -> YVideoCodec.Vp9
        "video/mpeg2" -> YVideoCodec.Mpeg2
        else -> null
    }

internal fun String.toYAudioCodec(): YAudioCodec? =
    when (lowercase()) {
        "audio/mp4a-latm", "audio/aac", "audio/aac-adts" -> YAudioCodec.Aac
        "audio/ac3" -> YAudioCodec.Ac3
        "audio/eac3", "audio/eac3-joc" -> YAudioCodec.Eac3
        "audio/flac" -> YAudioCodec.Flac
        "audio/opus" -> YAudioCodec.Opus
        "audio/true-hd" -> YAudioCodec.TrueHd
        "audio/vnd.dts" -> YAudioCodec.Dts
        "audio/vnd.dts.hd" -> YAudioCodec.DtsHd
        else -> null
    }

private const val MIME_DOLBY_VISION = "video/dolby-vision"
