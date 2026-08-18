package com.yfuse.core2.demux

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec

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
 * The payload remains opaque to the demuxer contract. Bitstream adapters own AVC/HVCC/Dolby
 * interpretation so FFmpeg, MediaExtractor and future disc demuxers expose one stable API.
 */
data class YCodecPrivateData(
    val entries: List<ByteArray> = emptyList(),
)

data class YVideoTrackFormat(
    val codec: YVideoCodec,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitDepth: Int = 8,
    val hdrType: YHdrType = YHdrType.Sdr,
    val codecPrivateData: YCodecPrivateData = YCodecPrivateData(),
)

data class YAudioTrackFormat(
    val codec: YAudioCodec,
    val mimeType: String,
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val codecPrivateData: YCodecPrivateData = YCodecPrivateData(),
)

data class YDemuxTrack(
    val id: YTrackId,
    val type: YDemuxTrackType,
    val language: String? = null,
    val label: String? = null,
    val video: YVideoTrackFormat? = null,
    val audio: YAudioTrackFormat? = null,
) {
    init {
        when (type) {
            YDemuxTrackType.Video -> require(video != null && audio == null)
            YDemuxTrackType.Audio -> require(audio != null && video == null)
            YDemuxTrackType.Subtitle, YDemuxTrackType.Data -> require(video == null && audio == null)
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
)

/**
 * Common compressed-media contract for platform, FFmpeg and disc demuxers.
 *
 * Implementations may be blocking internally but must be single-owner: one playback session calls
 * open/select/read/seek/close serially from its media worker. This keeps FFmpeg AVFormatContext and
 * platform extractor lifetimes deterministic and avoids lock-heavy hot paths.
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
