package com.yfuse.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** HDR signals that must survive decoding and the Android display pipeline. */
enum class PlaybackHdrFormat {
    Hdr10,
    Hdr10Plus,
    Hlg,
    DolbyVision,
}

/** Video codecs understood by playback negotiation rather than one concrete backend. */
enum class PlaybackVideoCodec(
    val embyNames: Set<String>,
) {
    H264(setOf("h264")),
    Hevc(setOf("hevc", "h265")),
    Vp8(setOf("vp8")),
    Vp9(setOf("vp9")),
    Av1(setOf("av1")),
    Mpeg2(setOf("mpeg2video")),
    Mpeg4(setOf("mpeg4")),
    Vc1(setOf("vc1")),
    ProRes(setOf("prores")),
    DolbyVision(emptySet()),
}

/** The concrete source facts needed to reject a decoder that only supports a smaller mode. */
data class PlaybackVideoRequirements(
    val codec: PlaybackVideoCodec?,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    val bitrateBitsPerSecond: Int? = null,
    val bitDepth: Int? = null,
    val level: Double? = null,
    val hdrFormat: PlaybackHdrFormat? = null,
)

enum class PlaybackVideoSupportKind {
    Supported,
    Unsupported,
    Unknown,
}

/** Result of checking one source against a real decoder rather than a codec-name allow-list. */
data class PlaybackVideoSupport(
    val kind: PlaybackVideoSupportKind,
    val detail: String,
) {
    val isUnsupported: Boolean get() = kind == PlaybackVideoSupportKind.Unsupported

    companion object {
        fun supported(detail: String) = PlaybackVideoSupport(PlaybackVideoSupportKind.Supported, detail)
        fun unsupported(detail: String) = PlaybackVideoSupport(PlaybackVideoSupportKind.Unsupported, detail)
        fun unknown(detail: String) = PlaybackVideoSupport(PlaybackVideoSupportKind.Unknown, detail)
    }
}

/** A codec may be decoded to PCM or preserved as an encoded bitstream. */
enum class PlaybackAudioCodec(
    val embyNames: Set<String>,
) {
    Aac(setOf("aac")),
    Mp3(setOf("mp3")),
    Ac3(setOf("ac3")),
    Eac3(setOf("eac3")),
    Eac3Joc(setOf("eac3")),
    TrueHd(setOf("truehd")),
    Dts(setOf("dts", "dca")),
    DtsHd(setOf("dts", "dca")),
    Ac4(setOf("ac4")),
    Flac(setOf("flac")),
    Opus(setOf("opus")),
    Vorbis(setOf("vorbis")),
    Pcm(setOf("pcm")),
}

enum class PlaybackAudioRoute {
    BuiltIn,
    Hdmi,
    Usb,
    Bluetooth,
    Other,
}

/**
 * One point-in-time view of the device and its current output route.
 *
 * Decoder support, display support, and encoded audio routing deliberately remain separate:
 * finding a Dolby decoder does not mean the current screen can display Dolby Vision, and finding
 * a TrueHD decoder does not prove that an attached receiver is receiving an Atmos bitstream.
 */
data class PlaybackDeviceCapabilities(
    val hdrFormats: Set<PlaybackHdrFormat>,
    val videoDecoders: Set<PlaybackVideoCodec>,
    val hdrDecoders: Map<PlaybackVideoCodec, Set<PlaybackHdrFormat>>,
    val audioDecoders: Set<PlaybackAudioCodec>,
    val directAudioFormats: Set<PlaybackAudioCodec>,
    /** Android CodecProfileLevel constants, not the 5/7/8 profile numbers shown to users. */
    val dolbyVisionCodecProfiles: Set<Int>,
    val dolbyVisionBaseCodecs: Set<PlaybackVideoCodec>,
    val audioRoutes: Set<PlaybackAudioRoute>,
    val maxAudioChannels: Int,
) {
    val supportsDolbyVisionOutput: Boolean
        get() =
            supportsHdrOutput(
                format = PlaybackHdrFormat.DolbyVision,
                codec = PlaybackVideoCodec.DolbyVision,
            ) && dolbyVisionBaseCodecs.isNotEmpty()

    val directPlayableAudio: Set<PlaybackAudioCodec>
        get() = audioDecoders + directAudioFormats

    fun supportsHdrOutput(
        format: PlaybackHdrFormat,
        codec: PlaybackVideoCodec? = null,
    ): Boolean {
        if (format !in hdrFormats) return false
        return if (codec == null) {
            hdrDecoders.values.any { format in it }
        } else {
            format in hdrDecoders[codec].orEmpty()
        }
    }

    /** Conservative common fallback; Android providers refine size/rate/bitrate with MediaCodec. */
    fun videoSupport(requirements: PlaybackVideoRequirements): PlaybackVideoSupport {
        val codec = requirements.codec ?: return PlaybackVideoSupport.unknown("片源没有提供视频编码")
        if (codec !in videoDecoders) {
            return PlaybackVideoSupport.unsupported("设备没有 ${codec.name} 解码器")
        }
        val hdr = requirements.hdrFormat
        if (hdr != null && hdr !in hdrDecoders[codec].orEmpty()) {
            return PlaybackVideoSupport.unsupported("${codec.name} 解码器未声明 ${hdr.name} 配置")
        }
        return PlaybackVideoSupport.supported("${codec.name} 解码能力已声明")
    }

    companion object {
        /** Safe when platform discovery is unavailable, including isolated common tests. */
        fun conservative(): PlaybackDeviceCapabilities =
            PlaybackDeviceCapabilities(
                hdrFormats = emptySet(),
                videoDecoders = setOf(PlaybackVideoCodec.H264),
                hdrDecoders = emptyMap(),
                audioDecoders = setOf(PlaybackAudioCodec.Aac, PlaybackAudioCodec.Mp3, PlaybackAudioCodec.Pcm),
                directAudioFormats = emptySet(),
                dolbyVisionCodecProfiles = emptySet(),
                dolbyVisionBaseCodecs = emptySet(),
                audioRoutes = setOf(PlaybackAudioRoute.BuiltIn),
                maxAudioChannels = 2,
            )
    }
}

fun interface PlaybackDeviceCapabilitiesProvider {
    /** Re-probes because HDMI/USB/Bluetooth routing may have changed since the previous request. */
    fun current(): PlaybackDeviceCapabilities

    /** Exact source check. Android overrides this with VideoCapabilities.areSizeAndRateSupported. */
    fun videoSupport(requirements: PlaybackVideoRequirements): PlaybackVideoSupport =
        current().videoSupport(requirements)

    /** Emits after HDMI/audio/display changes invalidate the current capability snapshot. */
    fun revisions(): Flow<Long> = emptyFlow()
}

internal expect fun createPlaybackDeviceCapabilitiesProvider(): PlaybackDeviceCapabilitiesProvider
