package com.yfuse.core2.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display
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
        val decoders = queryVideoDecoders()
        return YDeviceCapabilities(
            videoDecoders = decoders,
            displayHdrTypes = queryDisplayHdrTypes(),
            supportsSurfaceDirect = true,
            supportsTunnel = decoders.any { it.tunneledPlayback },
        )
    }

    private fun queryVideoDecoders(): List<YVideoDecoderCapability> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .flatMap { info ->
                info.supportedTypes
                    .asSequence()
                    .mapNotNull { type -> info.toYDecoder(type) }
            }.toList()

    private fun MediaCodecInfo.toYDecoder(type: String): YVideoDecoderCapability? {
        val normalizedType = type.lowercase()
        val codec = normalizedType.toYVideoCodec() ?: return null
        val capabilities = runCatching { getCapabilitiesForType(type) }.getOrNull() ?: return null
        val videoCapabilities = runCatching { capabilities.videoCapabilities }.getOrNull()
        val hdrTypes =
            buildSet {
                add(YHdrType.Sdr)
                if (normalizedType == MIME_DOLBY_VISION) add(YHdrType.DolbyVision)
            }
        return YVideoDecoderCapability(
            name = name,
            codec = codec,
            hdrTypes = hdrTypes,
            rawProfiles = capabilities.profileLevels.mapTo(mutableSetOf()) { it.profile },
            maxWidth = videoCapabilities?.supportedWidths?.upper ?: 0,
            maxHeight = videoCapabilities?.supportedHeights?.upper ?: 0,
            maxFrameRate = videoCapabilities?.supportedFrameRates?.upper ?: 0.0,
            tunneledPlayback =
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
            adaptivePlayback =
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
        )
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

private fun String.toYVideoCodec(): YVideoCodec? =
    when (this) {
        "video/avc" -> YVideoCodec.H264
        "video/hevc", MIME_DOLBY_VISION -> YVideoCodec.H265
        "video/av01" -> YVideoCodec.Av1
        "video/x-vnd.on2.vp9" -> YVideoCodec.Vp9
        "video/mpeg2" -> YVideoCodec.Mpeg2
        else -> null
    }

private const val MIME_DOLBY_VISION = "video/dolby-vision"
