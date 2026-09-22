package com.yfuse.core2.android
import com.yfuse.core2.subtitle.*
import com.yfuse.core2.demux.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
internal fun ByteArray.toBitmapSubtitleCues(sample: YCompressedSample): List<YSubtitleCue> =
    toBitmapSubtitleDisplay(sample).cues

internal fun ByteArray.toBitmapSubtitleDisplay(sample: YCompressedSample): YSubtitleDecodeResult.DisplaySet {
    val input = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    require(input.remaining() >= SUBTITLE_PAYLOAD_HEADER_BYTES) { "FFmpeg subtitle payload is truncated" }
    require(input.int == SUBTITLE_PAYLOAD_MAGIC) { "FFmpeg subtitle payload has an invalid signature" }
    val version = input.int
    require(version in 1..SUBTITLE_PAYLOAD_VERSION) { "FFmpeg subtitle payload version is unsupported" }
    val canvasWidth = input.int
    val canvasHeight = input.int
    val startOffsetUs = input.unsignedIntToLong() * MICROS_PER_MILLISECOND
    val endOffsetMs = input.unsignedIntToLong()
    val endOffsetUs = endOffsetMs * MICROS_PER_MILLISECOND
    val rectCount = input.int
    require(rectCount in 0..MAX_SUBTITLE_RECTS) { "FFmpeg subtitle rectangle count is invalid" }
    // v2 carries AVSubtitle.pts relative to the input packet. A PGS END packet can have a
    // different PTS from the earlier presentation-composition segment it completes.
    val ptsOffsetUs =
        if (version >= 2) {
            require(input.remaining() >= Long.SIZE_BYTES) { "FFmpeg subtitle timestamp is truncated" }
            input.long
        } else {
            0L
        }
    val baseUs = Math.addExact(sample.presentationTimeUs, ptsOffsetUs)
    val startUs = Math.addExact(baseUs, startOffsetUs).coerceAtLeast(0L)
    require(startUs < Long.MAX_VALUE) { "FFmpeg subtitle timestamp is invalid" }
    if (rectCount == 0) {
        require(!input.hasRemaining()) { "FFmpeg subtitle payload has trailing data" }
        return YSubtitleDecodeResult.DisplaySet(startUs, emptyList())
    }
    require(canvasWidth in 1..MAX_SUBTITLE_DIMENSION && canvasHeight in 1..MAX_SUBTITLE_DIMENSION) {
        "FFmpeg subtitle dimension is invalid"
    }
    val fallbackDurationUs = sample.durationUs?.takeIf { it > 0L } ?: DEFAULT_BITMAP_SUBTITLE_DURATION_US
    val endUs =
        when {
            endOffsetMs == 0xffff_ffffL -> Long.MAX_VALUE
            endOffsetUs > startOffsetUs -> Math.addExact(baseUs, endOffsetUs)
            else -> Math.addExact(startUs, fallbackDurationUs)
        }
    val cues =
        List(rectCount) { rectIndex ->
            require(input.remaining() >= SUBTITLE_RECT_HEADER_BYTES) { "FFmpeg subtitle rectangle is truncated" }
            val x = input.nonNegativeSubtitleCoordinate()
            val y = input.nonNegativeSubtitleCoordinate()
            val width = input.positiveSubtitleDimension()
            val height = input.positiveSubtitleDimension()
            input.int // authored flags are retained in native diagnostics, not presentation policy
            val pixelCount = input.int
            input.int // reserved
            require(pixelCount == width * height && pixelCount <= MAX_SUBTITLE_PIXELS) {
                "FFmpeg subtitle rectangle pixel count is invalid"
            }
            require(input.remaining() >= pixelCount * Int.SIZE_BYTES) { "FFmpeg subtitle pixels are truncated" }
            require(x + width <= canvasWidth && y + height <= canvasHeight) {
                "FFmpeg subtitle rectangle exceeds its authored canvas"
            }
            val pixels = IntArray(pixelCount) { input.int }
            YSubtitleCue(
                id = "${sample.trackId.value}:${sample.presentationTimeUs}:$rectIndex",
                startUs = startUs,
                endUs = endUs.coerceAtLeast(startUs + 1L),
                payload =
                    YSubtitlePayload.BitmapArgb(
                        width = width,
                        height = height,
                        x = x,
                        y = y,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        pixels = pixels,
                    ),
            )
        }.also {
            require(!input.hasRemaining()) { "FFmpeg subtitle payload has trailing data" }
        }
    return YSubtitleDecodeResult.DisplaySet(startUs, cues)
}

private fun ByteBuffer.positiveSubtitleDimension(): Int =
    int.also { require(it in 1..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle dimension is invalid" } }

private fun ByteBuffer.nonNegativeSubtitleCoordinate(): Int =
    int.also { require(it in 0..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle coordinate is invalid" } }

private fun ByteBuffer.unsignedIntToLong(): Long = int.toLong() and 0xffff_ffffL
private const val SUBTITLE_PAYLOAD_MAGIC = 0x42555359
private const val SUBTITLE_PAYLOAD_VERSION = 2
private const val SUBTITLE_PAYLOAD_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val SUBTITLE_RECT_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val MAX_SUBTITLE_RECTS = 64
private const val MAX_SUBTITLE_DIMENSION = 16_384
private const val MAX_SUBTITLE_PIXELS = 8 * 1024 * 1024
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_BITMAP_SUBTITLE_DURATION_US = 5_000_000L