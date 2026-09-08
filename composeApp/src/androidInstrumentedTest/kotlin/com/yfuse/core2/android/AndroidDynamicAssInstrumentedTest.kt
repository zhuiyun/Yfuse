package com.yfuse.core2.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitlePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDynamicAssInstrumentedTest {
    @Test
    fun packet_and_full_script_share_styles_and_animate_on_the_requested_clock() {
        assertTrue(
            "The packaged native library must expose dynamic ASS API 2: ${FfmpegNativeBridge.loadFailureDescription}",
            FfmpegNativeBridge.dynamicAssRendererAvailable,
        )
        val markup = "{\\an7\\move(20,40,220,40,0,2000)\\fad(800,800)\\p1}m 0 0 l 80 0 80 30 0 30"
        val packet = "0,0,Default,,0,0,0,,$markup".encodeToByteArray()
        val script = HEADER + "Dialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,$markup\n"
        val embedded =
            FfmpegNativeBridge.createAssRenderer(
                YAssSubtitleSource(HEADER.encodeToByteArray(), false),
                640,
                360,
            )
        val external =
            FfmpegNativeBridge.createAssRenderer(
                YAssSubtitleSource(script.encodeToByteArray(), true),
                640,
                360,
            )
        try {
            val faded = draw(embedded, 200_000, packet)
            val middle = draw(embedded, 1_000_000, packet)
            val moved = draw(embedded, 1_800_000, packet)
            assertTrue("Fade must change opacity after the packet start", opacity(faded) < opacity(middle))
            assertTrue("Move must advance within the same subtitle packet", faded.x < middle.x && middle.x < moved.x)
            val fromScript = draw(external, 1_000_000, null)
            assertTrue(middle.x == fromScript.x && middle.y == fromScript.y)
            assertArrayEquals(
                "External scripts and embedded packets use the same authored styles",
                middle.pixels,
                fromScript.pixels,
            )
            val styled =
                FfmpegNativeBridge.createAssRenderer(
                    YAssSubtitleSource(script.encodeToByteArray(), true),
                    640,
                    360,
                    styleOverrides = listOf("PrimaryColour=&H000000ff", "OutlineColour=&H00ff0000", "Outline=6"),
                )
            try {
                val custom = draw(styled, 1_000_000, null)
                assertTrue("User text colour must reach native ASS", custom.pixels.any { ((it ushr 16) and 255) > 200 })
                assertTrue("User outline colour must reach native ASS", custom.pixels.any { (it and 255) > 200 })
                assertTrue("User outline width must reach native ASS", custom.width > middle.width)
            } finally {
                FfmpegNativeBridge.closeAssRenderer(styled)
            }
            val seekBack = draw(embedded, 200_000, packet)
            assertTrue("A backward seek must redraw the earlier animation state", seekBack.x == faded.x)
            assertArrayEquals(faded.pixels, seekBack.pixels)
            val ended =
                FfmpegNativeBridge.renderAss(
                    embedded,
                    3_500_000,
                    1,
                    arrayOf(packet),
                    longArrayOf(0),
                    longArrayOf(3_000_000),
                )
            assertTrue("Expired ASS display sets must clear the previous bitmap", ended != null && ended.isEmpty())
        } finally {
            FfmpegNativeBridge.closeAssRenderer(embedded)
            FfmpegNativeBridge.closeAssRenderer(external)
        }
    }

    private fun draw(
        handle: Long,
        timeUs: Long,
        packet: ByteArray?,
    ): YSubtitlePayload.BitmapArgb {
        val encoded =
            requireNotNull(
                FfmpegNativeBridge.renderAss(
                    handle,
                    timeUs,
                    1,
                    packet?.let { arrayOf(it) } ?: emptyArray(),
                    if (packet == null) longArrayOf() else longArrayOf(0),
                    if (packet == null) longArrayOf() else longArrayOf(3_000_000),
                ),
            )
        return encoded
            .toBitmapSubtitleCues(
                YCompressedSample(YTrackId(0), byteArrayOf(), timeUs),
            ).single()
            .payload as YSubtitlePayload.BitmapArgb
    }

    private fun opacity(bitmap: YSubtitlePayload.BitmapArgb) = bitmap.pixels.maxOf { it ushr 24 }
}

private const val HEADER = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
WrapStyle: 0
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,sans-serif,28,&H0000FF00,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
