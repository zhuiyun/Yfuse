package com.yfuse.core2.android

import com.yfuse.core2.subtitle.YSubtitleTimeline
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Synthetic decoded bitmap display sets. Mapping and timeline logic are extracted verbatim
// from the current production sources by run-repro.ps1, without an Android/FFmpeg runtime.
private fun payload(endDisplayMs: Int, rectCount: Int = 1): ByteArray =
    ByteBuffer.allocate((7 + rectCount * 8) * Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            putInt(0x42555359)
            putInt(1)
            putInt(1920)
            putInt(1080)
            putInt(0)
            putInt(endDisplayMs)
            putInt(rectCount)
            repeat(rectCount) {
                putInt(100)
                putInt(900)
                putInt(1)
                putInt(1)
                putInt(0)
                putInt(1)
                putInt(0)
                putInt(0xffffffff.toInt())
            }
        }.array()

fun main() {
    val normal = payload(1_000).toBitmapSubtitleCues(YCompressedSample(YTrackId(3), 1_000_000L))
    check(YSubtitleTimeline(normal).activeAt(1_999_999L).size == 1)
    check(YSubtitleTimeline(normal).activeAt(2_000_000L).isEmpty())

    // FFmpeg PGS uses UINT32_MAX for a display set whose lifetime ends at the next set.
    val starts = listOf(1_000_000L, 3_000_000L, 5_000_000L)
    val displayed = starts.flatMap { start ->
        payload(-1).toBitmapSubtitleCues(
            YCompressedSample(YTrackId(3), start, durationUs = 2_000_000L),
        )
    }
    val timeline = YSubtitleTimeline(displayed)
    val visibleAtSixSeconds = timeline.activeAt(6_000_000L).size
    val visibleAtOneHour = timeline.activeAt(3_600_000_000L).size
    check(visibleAtSixSeconds == 3)
    check(visibleAtOneHour == 3)
    check(displayed.first().endUs == 4_294_968_295_000L)

    // JNI currently drops zero-rectangle sets before the mapper. This additionally checks
    // that forwarding one unchanged would be rejected by the current Kotlin protocol.
    val emptyDisplayResult = runCatching {
        payload(-1, rectCount = 0).toBitmapSubtitleCues(YCompressedSample(YTrackId(3), 7_000_000L))
    }
    check(emptyDisplayResult.exceptionOrNull() is IllegalArgumentException)
    check(emptyDisplayResult.exceptionOrNull()?.message == "FFmpeg subtitle rectangle count is invalid")

    println(
        """{"finite_end_expires":true,"pgs_display_count":3,"visible_at_6s":$visibleAtSixSeconds,"visible_at_1h":$visibleAtOneHour,"first_cue_end_us":${displayed.first().endUs},"uint32_max_duration_days":${4_294_967_295.0 / 1000.0 / 86400.0},"zero_rectangle_payload_rejected":true,"stacking_reproduced":true}""",
    )
}
