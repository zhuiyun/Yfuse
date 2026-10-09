package com.yfuse.core2.demux

import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.hdr.YHdrStaticMetadata
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.subtitle.YSubtitleDecodeResult
import com.yfuse.core2.subtitle.YSubtitleFormat
import kotlin.math.roundToInt

/** Stable track id owned by one demux session. */
@JvmInline
value class YTrackId(
    val value: Int,
)

enum class YDemuxTrackType {
    Video,
    Audio,
    Subtitle,
    Data,
}

enum class YSampleFlag {
    Sync,
    CodecConfig,
    EndOfStream,
    Encrypted,
}

/**
 * Codec-private bytes that accompany compressed samples.
 *
 * The payload remains opaque to the demuxer contract. Bitstream adapters own AVC/HVCC parsing so
 * FFmpeg, MediaExtractor and future disc demuxers expose one stable API. Dolby configuration is
 * promoted separately because routing must understand its semantic profile before decode begins.
 */
data class YCodecPrivateData(
    val entries: List<ByteArray> = emptyList(),
)

enum class YColorRange { Unspecified, Limited, Full }

enum class YColorMatrix { Unspecified, Bt601, Bt709, Bt2020, Identity }

enum class YColorPrimaries { Unspecified, Bt709, Bt2020, DisplayP3 }

enum class YChromaLocation { Unspecified, Left, Center, TopLeft, Top, BottomLeft, Bottom }

data class YVideoGeometry(
    val pixelAspectRatioNumerator: Int = 1,
    val pixelAspectRatioDenominator: Int = 1,
    val rotationDegrees: Int = 0,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = 0,
    val cropBottom: Int = 0,
) {
    val normalizedRotationDegrees: Int = ((rotationDegrees % 360) + 360) % 360

    /** Width over height of one stored pixel; see [pixelAspectRatioOf]. */
    val pixelAspectRatio: Double
        get() = pixelAspectRatioOf(pixelAspectRatioNumerator, pixelAspectRatioDenominator)
}

/**
 * Width over height of one stored pixel, from a stated ratio. DVD, HDV and much broadcast video
 * store pixels that are not square: a 16:9 DVD is 720×480 pixels of 32:27. An unknown ratio (0:1)
 * and one no real video uses — beyond 1:4 or 4:1, which only broken metadata states — count as
 * square.
 */
fun pixelAspectRatioOf(
    numerator: Int,
    denominator: Int,
): Double {
    if (numerator <= 0 || denominator <= 0) return 1.0
    return plausiblePixelAspectRatio(numerator.toDouble() / denominator)
}

/** [ratio] when real video could use it, else square; see [pixelAspectRatioOf]. */
fun plausiblePixelAspectRatio(ratio: Double): Double =
    if (ratio.isFinite() && ratio in MIN_PIXEL_ASPECT_RATIO..MAX_PIXEL_ASPECT_RATIO) ratio else 1.0

/**
 * The pixel aspect ratio a container or decoder states, or null when it states none: a `sar`
 * pair (an MP4 `pasp` box, a decoder's reading of the bitstream), else a display size given beside
 * the stored [width]×[height] (Matroska, in pixels or any unit, since only the ratio matters).
 */
fun statedPixelAspectRatio(
    sarWidth: Int?,
    sarHeight: Int?,
    displayWidth: Int?,
    displayHeight: Int?,
    width: Int,
    height: Int,
): Double? {
    if (sarWidth != null && sarHeight != null && sarWidth > 0 && sarHeight > 0) {
        return pixelAspectRatioOf(sarWidth, sarHeight)
    }
    if (displayWidth == null || displayHeight == null || displayWidth <= 0 || displayHeight <= 0) return null
    if (width <= 0 || height <= 0) return null
    return plausiblePixelAspectRatio(displayWidth.toDouble() * height / (displayHeight.toDouble() * width))
}

/**
 * The size a picture is shown at, which the player's surface is laid out from.
 *
 * Pixels that are not square are squared first, the way mpv does: widened for a ratio above one
 * (720×480 at 32:27 is shown 853×480), heightened below it. Then a stream turned a quarter is
 * shown on its side — a phone clip stored 1920×1080 with a 90° flag stands upright at 1080×1920.
 * Reporting the stored size instead squeezed anamorphic DVDs into 3:2 and turned pictures into
 * a landscape box.
 */
fun shownVideoSize(
    width: Int,
    height: Int,
    rotationDegrees: Int,
    pixelAspectRatio: Double = 1.0,
): Pair<Int, Int> {
    var shownWidth = width
    var shownHeight = height
    if (width > 0 && height > 0 && pixelAspectRatio > 0.0) {
        if (pixelAspectRatio > 1.0) {
            shownWidth = (width * pixelAspectRatio).roundToInt()
        } else if (pixelAspectRatio < 1.0) {
            shownHeight = (height / pixelAspectRatio).roundToInt()
        }
    }
    return if (rotationDegrees.mod(180) == 90) shownHeight to shownWidth else shownWidth to shownHeight
}

private const val MIN_PIXEL_ASPECT_RATIO = 0.25
private const val MAX_PIXEL_ASPECT_RATIO = 4.0

data class YVideoTrackFormat(
    val codec: YVideoCodec,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitDepth: Int = 8,
    val hdrType: YHdrType = YHdrType.Sdr,
    /** Null for codecs/containers where NAL packing does not apply. */
    val samplePacking: YSamplePacking? = null,
    val codecPrivateData: YCodecPrivateData = YCodecPrivateData(),
    val dolbyVisionConfig: YDolbyVisionConfig? = null,
    val hdrStaticMetadata: YHdrStaticMetadata? = null,
    val colorRange: YColorRange = YColorRange.Unspecified,
    val colorMatrix: YColorMatrix = YColorMatrix.Unspecified,
    val colorPrimaries: YColorPrimaries = YColorPrimaries.Unspecified,
    val chromaLocation: YChromaLocation = YChromaLocation.Unspecified,
    val geometry: YVideoGeometry = YVideoGeometry(),
)

data class YAudioTrackFormat(
    val codec: YAudioCodec,
    val mimeType: String,
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val codecPrivateData: YCodecPrivateData = YCodecPrivateData(),
)

data class YSubtitleTrackFormat(
    val format: YSubtitleFormat,
    val mimeType: String,
    val codecPrivateData: YCodecPrivateData = YCodecPrivateData(),
)

data class YDemuxTrack(
    val id: YTrackId,
    val type: YDemuxTrackType,
    val language: String? = null,
    val label: String? = null,
    val video: YVideoTrackFormat? = null,
    val audio: YAudioTrackFormat? = null,
    val subtitle: YSubtitleTrackFormat? = null,
) {
    init {
        when (type) {
            YDemuxTrackType.Video -> require(video != null && audio == null && subtitle == null)
            YDemuxTrackType.Audio -> require(audio != null && video == null && subtitle == null)
            YDemuxTrackType.Subtitle -> require(subtitle != null && video == null && audio == null)
            YDemuxTrackType.Data -> require(video == null && audio == null && subtitle == null)
        }
    }
}

/**
 * One encoded access unit. `data` is immutable from the caller's point of view and remains
 * compressed; no demux implementation is allowed to return decoded video frames here.
 */
data class YCompressedSample(
    val trackId: YTrackId,
    val data: ByteArray,
    val presentationTimeUs: Long,
    val decodeTimeUs: Long? = null,
    val durationUs: Long? = null,
    val flags: Set<YSampleFlag> = emptySet(),
) {
    val endOfStream: Boolean get() = YSampleFlag.EndOfStream in flags
    val encrypted: Boolean get() = YSampleFlag.Encrypted in flags
}

data class YDemuxOpenResult(
    val container: YContainer,
    val durationUs: Long? = null,
    val bitRateBitsPerSecond: Long = 0L,
    val tracks: List<YDemuxTrack>,
) {
    init {
        require(tracks.map { it.id }.distinct().size == tracks.size) { "Demux track ids must be unique" }
    }
}

/** Source identity stays outside diagnostics; implementations must not log URI/header values. */
data class YDemuxSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val cacheIdentity: YCacheIdentity? = null,
    val cacheMaximumBytes: Long = 0L,
    val transportCredentials: YTransportCredentials? = null,
    /** Metadata-only open: the demuxer may bound its own stream analysis instead of playing. */
    val probeOnly: Boolean = false,
    /** Short analysis while retaining normal packet buffering for subsequent playback. */
    val startupAnalysis: Boolean = false,
)

/**
 * Common compressed-media contract for platform, FFmpeg and disc demuxers.
 *
 * Implementations may be blocking internally but must be single-owner: one media-source executor
 * calls open/select/read/seek/close serially. This keeps FFmpeg AVFormatContext and platform
 * extractor lifetimes deterministic while allowing codec/render workers to remain non-blocking.
 */
interface YDemuxer {
    val name: String

    fun open(source: YDemuxSource): YDemuxOpenResult

    fun selectTracks(trackIds: Set<YTrackId>)

    /** Returns the next compressed sample from the selected tracks, or null at source EOF. */
    fun readSample(): YCompressedSample?

    /** Seeks to a sync-safe point at or before the requested timestamp unless the backend says otherwise. */
    fun seekTo(positionUs: Long)

    fun close()
}

/** Optional side node for image subtitles; normal video/audio demux remains compressed-only. */
interface YSubtitlePacketDecoder {
    fun supportsSubtitleFormat(format: YSubtitleFormat): Boolean

    fun decodeSubtitle(sample: YCompressedSample): YSubtitleDecodeResult
}
