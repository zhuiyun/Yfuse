package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import java.nio.ByteBuffer

/**
 * Thin JNI surface over the pinned FFmpeg demux libraries bundled with Yfuse's native AAR.
 *
 * Loading is optional: ordinary Legacy/NativeDirect playback remains usable when the custom native
 * AAR is absent. Callers must check [available] before opening an enhanced-demux session.
 */
internal object FfmpegNativeBridge {
    val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary(LIBRARY_NAME)
            true
        }.getOrDefault(false)
    }

    val softwareDecodeAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        available && runCatching { nativeSoftwareDecoderApiVersion() >= SOFTWARE_DECODER_API_VERSION }.getOrDefault(false)
    }

    fun open(
        uri: String,
        headers: Map<String, String>,
    ): Long {
        check(available) { "YCore FFmpeg demux bridge is not installed" }
        val entries = headers.entries.toList()
        return nativeOpen(
            uri,
            entries.map { it.key }.toTypedArray(),
            entries.map { it.value }.toTypedArray(),
        ).also { handle ->
            if (handle < 0L) throwFfmpegFailure(handle, YPlaybackFailureStage.SourceOpen)
            check(handle != 0L) { "YCore FFmpeg demux session was not created" }
        }
    }

    fun close(handle: Long) {
        if (handle != 0L && available) nativeClose(handle)
    }

    fun trackCount(handle: Long): Int = nativeTrackCount(handle)

    fun containerName(handle: Long): String? = nativeContainerName(handle)

    fun durationUs(handle: Long): Long? = nativeDurationUs(handle).timestampOrNull()

    fun bitRateBitsPerSecond(handle: Long): Long = nativeBitRateBitsPerSecond(handle).coerceAtLeast(0L)

    fun trackType(
        handle: Long,
        index: Int,
    ): Int = nativeTrackType(handle, index)

    fun trackCodecName(
        handle: Long,
        index: Int,
    ): String? = nativeTrackCodecName(handle, index)

    fun trackVideoInfo(
        handle: Long,
        index: Int,
    ): LongArray = checkNotNull(nativeTrackVideoInfo(handle, index)) { "FFmpeg video metadata is unavailable" }

    fun trackAudioInfo(
        handle: Long,
        index: Int,
    ): LongArray = checkNotNull(nativeTrackAudioInfo(handle, index)) { "FFmpeg audio metadata is unavailable" }

    fun trackLanguage(
        handle: Long,
        index: Int,
    ): String? = nativeTrackLanguage(handle, index)

    fun trackTitle(
        handle: Long,
        index: Int,
    ): String? = nativeTrackTitle(handle, index)

    fun trackExtradata(
        handle: Long,
        index: Int,
    ): ByteArray? = nativeTrackExtradata(handle, index)

    fun trackDolbyConfig(
        handle: Long,
        index: Int,
    ): IntArray? = nativeTrackDolbyConfig(handle, index)

    fun trackHdrStaticInfo(
        handle: Long,
        index: Int,
    ): IntArray? = nativeTrackHdrStaticInfo(handle, index)

    fun selectTracks(
        handle: Long,
        indexes: IntArray,
    ) = nativeSelectTracks(handle, indexes)

    fun readPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReadPacket(handle, target)) { "FFmpeg packet result is unavailable" }
            .also { result ->
                result.firstOrNull()?.takeIf { it <= FFMPEG_FAILURE_AUTHORIZATION }?.let { status ->
                    throwFfmpegFailure(status, YPlaybackFailureStage.Demux)
                }
            }

    fun decodeSubtitle(
        handle: Long,
        trackIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        durationUs: Long?,
    ): ByteArray? =
        nativeDecodeSubtitle(
            handle,
            trackIndex,
            data,
            presentationTimeUs,
            durationUs ?: Long.MIN_VALUE,
        )

    fun configureSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    ) {
        check(softwareDecodeAvailable) { "YCore FFmpeg software decoder is not installed" }
        nativeConfigureSoftwareDecoder(handle, trackIndex)
    }

    fun sendSoftwarePacket(
        handle: Long,
        trackIndex: Int,
        data: ByteArray?,
        presentationTimeUs: Long?,
        decodeTimeUs: Long?,
    ): Boolean =
        nativeSendSoftwarePacket(
            handle,
            trackIndex,
            data,
            presentationTimeUs ?: Long.MIN_VALUE,
            decodeTimeUs ?: presentationTimeUs ?: Long.MIN_VALUE,
        ) == SOFTWARE_PACKET_ACCEPTED

    fun receiveSoftwareVideoFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReceiveSoftwareVideoFrame(handle, trackIndex, target)) {
            "FFmpeg software video result is unavailable"
        }

    fun receiveSoftwareAudioFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray =
        checkNotNull(nativeReceiveSoftwareAudioFrame(handle, trackIndex, target)) {
            "FFmpeg software audio result is unavailable"
        }

    fun flushSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    ) = nativeFlushSoftwareDecoder(handle, trackIndex)

    fun seek(
        handle: Long,
        positionUs: Long,
    ) {
        val status = nativeSeek(handle, positionUs.coerceAtLeast(0L))
        if (status < 0) throwFfmpegFailure(status.toLong(), YPlaybackFailureStage.Seek)
    }

    private external fun nativeOpen(
        uri: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
    ): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeTrackCount(handle: Long): Int

    private external fun nativeContainerName(handle: Long): String?

    private external fun nativeDurationUs(handle: Long): Long

    private external fun nativeBitRateBitsPerSecond(handle: Long): Long

    private external fun nativeTrackType(
        handle: Long,
        index: Int,
    ): Int

    private external fun nativeTrackCodecName(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackVideoInfo(
        handle: Long,
        index: Int,
    ): LongArray?

    private external fun nativeTrackAudioInfo(
        handle: Long,
        index: Int,
    ): LongArray?

    private external fun nativeTrackLanguage(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackTitle(
        handle: Long,
        index: Int,
    ): String?

    private external fun nativeTrackExtradata(
        handle: Long,
        index: Int,
    ): ByteArray?

    private external fun nativeTrackDolbyConfig(
        handle: Long,
        index: Int,
    ): IntArray?

    private external fun nativeTrackHdrStaticInfo(
        handle: Long,
        index: Int,
    ): IntArray?

    private external fun nativeSelectTracks(
        handle: Long,
        indexes: IntArray,
    )

    private external fun nativeReadPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeDecodeSubtitle(
        handle: Long,
        trackIndex: Int,
        data: ByteArray,
        presentationTimeUs: Long,
        durationUs: Long,
    ): ByteArray?

    private external fun nativeSoftwareDecoderApiVersion(): Int

    private external fun nativeConfigureSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    )

    private external fun nativeSendSoftwarePacket(
        handle: Long,
        trackIndex: Int,
        data: ByteArray?,
        presentationTimeUs: Long,
        decodeTimeUs: Long,
    ): Int

    private external fun nativeReceiveSoftwareVideoFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeReceiveSoftwareAudioFrame(
        handle: Long,
        trackIndex: Int,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeFlushSoftwareDecoder(
        handle: Long,
        trackIndex: Int,
    )

    private external fun nativeSeek(
        handle: Long,
        positionUs: Long,
    ): Int
}

private fun throwFfmpegFailure(
    status: Long,
    stage: YPlaybackFailureStage,
): Nothing =
    throw YPlaybackException(
        category = ffmpegFailureCategory(status),
        stage = stage,
        safeDetail = "FFmpeg ${stage.name} returned a classified failure",
    )

internal fun ffmpegFailureCategory(status: Long): YPlaybackFailureCategory =
    when (status) {
        FFMPEG_FAILURE_AUTHORIZATION -> YPlaybackFailureCategory.Authorization
        FFMPEG_FAILURE_NETWORK -> YPlaybackFailureCategory.Network
        else -> YPlaybackFailureCategory.Container
    }

internal fun Long.timestampOrNull(): Long? = takeUnless { it == Long.MIN_VALUE }

internal const val FFMPEG_PACKET_EOF = 0L
internal const val FFMPEG_PACKET_DATA = 1L
internal const val FFMPEG_PACKET_GROW_BUFFER = -1L
internal const val FFMPEG_FAILURE_AUTHORIZATION = -2L
internal const val FFMPEG_FAILURE_NETWORK = -3L
internal const val FFMPEG_FAILURE_CONTAINER = -4L
internal const val FFMPEG_SAMPLE_SYNC = 1L shl 0
internal const val FFMPEG_SAMPLE_ENCRYPTED = 1L shl 1
internal const val FFMPEG_TRACK_VIDEO = 1
internal const val FFMPEG_TRACK_AUDIO = 2
internal const val FFMPEG_TRACK_SUBTITLE = 3
internal const val FFMPEG_TRACK_DATA = 4
internal const val FFMPEG_HDR_PQ = 1L
internal const val FFMPEG_HDR_HLG = 2L
internal const val FFMPEG_HDR10_PLUS = 3L
internal const val FFMPEG_PACKING_ANNEX_B = 1L
internal const val FFMPEG_PACKING_LENGTH_PREFIXED = 2L
private const val LIBRARY_NAME = "ycore_demux"
private const val SOFTWARE_DECODER_API_VERSION = 1
private const val SOFTWARE_PACKET_ACCEPTED = 0
