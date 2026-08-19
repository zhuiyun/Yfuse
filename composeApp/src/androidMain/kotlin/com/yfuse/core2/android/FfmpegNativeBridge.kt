package com.yfuse.core2.android

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
            check(handle != 0L) { "YCore FFmpeg demux session was not created" }
        }
    }

    fun close(handle: Long) {
        if (handle != 0L && available) nativeClose(handle)
    }

    fun trackCount(handle: Long): Int = nativeTrackCount(handle)

    fun containerName(handle: Long): String? = nativeContainerName(handle)

    fun durationUs(handle: Long): Long? = nativeDurationUs(handle).timestampOrNull()

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

    fun selectTracks(
        handle: Long,
        indexes: IntArray,
    ) = nativeSelectTracks(handle, indexes)

    fun readPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray = checkNotNull(nativeReadPacket(handle, target)) { "FFmpeg packet result is unavailable" }

    fun seek(
        handle: Long,
        positionUs: Long,
    ) = nativeSeek(handle, positionUs.coerceAtLeast(0L))

    private external fun nativeOpen(
        uri: String,
        headerNames: Array<String>,
        headerValues: Array<String>,
    ): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeTrackCount(handle: Long): Int

    private external fun nativeContainerName(handle: Long): String?

    private external fun nativeDurationUs(handle: Long): Long

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

    private external fun nativeSelectTracks(
        handle: Long,
        indexes: IntArray,
    )

    private external fun nativeReadPacket(
        handle: Long,
        target: ByteBuffer,
    ): LongArray?

    private external fun nativeSeek(
        handle: Long,
        positionUs: Long,
    )
}

internal fun Long.timestampOrNull(): Long? = takeUnless { it == Long.MIN_VALUE }

internal const val FFMPEG_PACKET_EOF = 0L
internal const val FFMPEG_PACKET_DATA = 1L
internal const val FFMPEG_PACKET_GROW_BUFFER = -1L
internal const val FFMPEG_SAMPLE_SYNC = 1L shl 0
internal const val FFMPEG_SAMPLE_ENCRYPTED = 1L shl 1
internal const val FFMPEG_TRACK_VIDEO = 1
internal const val FFMPEG_TRACK_AUDIO = 2
internal const val FFMPEG_TRACK_SUBTITLE = 3
internal const val FFMPEG_TRACK_DATA = 4
internal const val FFMPEG_HDR_PQ = 1L
internal const val FFMPEG_HDR_HLG = 2L
internal const val FFMPEG_PACKING_ANNEX_B = 1L
internal const val FFMPEG_PACKING_LENGTH_PREFIXED = 2L
private const val LIBRARY_NAME = "ycore_demux"
