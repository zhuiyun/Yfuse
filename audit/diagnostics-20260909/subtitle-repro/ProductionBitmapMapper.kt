package com.yfuse.core2.android
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import java.nio.ByteBuffer
import java.nio.ByteOrder
// Data-only scaffolding for the mapper's three sample fields; no playback/decoder logic.
data class YTrackId(val value: Int)
data class YCompressedSample(val trackId: YTrackId, val presentationTimeUs: Long, val durationUs: Long? = null)
internal fun ByteArray.toBitmapSubtitleCues(sample: YCompressedSample): List<YSubtitleCue> {
    val input = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    require(input.remaining() >= SUBTITLE_PAYLOAD_HEADER_BYTES) { "FFmpeg subtitle payload is truncated" }
    require(input.int == SUBTITLE_PAYLOAD_MAGIC) { "FFmpeg subtitle payload has an invalid signature" }
    require(input.int == SUBTITLE_PAYLOAD_VERSION) { "FFmpeg subtitle payload version is unsupported" }
    val canvasWidth = input.positiveSubtitleDimension()
    val canvasHeight = input.positiveSubtitleDimension()
    val startOffsetUs = input.unsignedIntToLong() * MICROS_PER_MILLISECOND
    val endOffsetUs = input.unsignedIntToLong() * MICROS_PER_MILLISECOND
    val rectCount = input.int
    require(rectCount in 1..MAX_SUBTITLE_RECTS) { "FFmpeg subtitle rectangle count is invalid" }
    val startUs = sample.presentationTimeUs.coerceAtLeast(0L) + startOffsetUs
    val fallbackDurationUs = sample.durationUs?.takeIf { it > 0L } ?: DEFAULT_BITMAP_SUBTITLE_DURATION_US
    val endUs =
        if (endOffsetUs > startOffsetUs) {
            sample.presentationTimeUs.coerceAtLeast(0L) + endOffsetUs
        } else {
            startUs + fallbackDurationUs
        }
    return List(rectCount) { rectIndex ->
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
}

private fun ByteBuffer.positiveSubtitleDimension(): Int =
    int.also { require(it in 1..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle dimension is invalid" } }

private fun ByteBuffer.nonNegativeSubtitleCoordinate(): Int =
    int.also { require(it in 0..MAX_SUBTITLE_DIMENSION) { "FFmpeg subtitle coordinate is invalid" } }

private fun ByteBuffer.unsignedIntToLong(): Long = int.toLong() and 0xffff_ffffL
private const val SUBTITLE_PAYLOAD_MAGIC = 0x42555359
private const val SUBTITLE_PAYLOAD_VERSION = 1
private const val SUBTITLE_PAYLOAD_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val SUBTITLE_RECT_HEADER_BYTES = 7 * Int.SIZE_BYTES
private const val MAX_SUBTITLE_RECTS = 64
private const val MAX_SUBTITLE_DIMENSION = 16_384
private const val MAX_SUBTITLE_PIXELS = 8 * 1024 * 1024
private const val MICROS_PER_MILLISECOND = 1_000L
private const val DEFAULT_BITMAP_SUBTITLE_DURATION_US = 5_000_000L
