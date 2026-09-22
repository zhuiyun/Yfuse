package com.yfuse.core2.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.subtitle.YSubtitleCueBuffer
import com.yfuse.core2.subtitle.YSubtitleDecodeResult
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Exercises the packaged FFmpeg decoder/JNI, not synthetic already-decoded bitmap payloads. */
@RunWith(AndroidJUnit4::class)
class AndroidPgsDisplaySetInstrumentedTest {
    @Test
    fun pgs_rectangles_replace_and_clear_using_composition_pts_even_with_later_end_packets() {
        assertTrue("The installed AAR must preserve PGS display sets", FfmpegNativeBridge.subtitleDisplaySetAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File.createTempFile("pgs-display-sets-", ".sup", context.cacheDir)
        fixture.writeBytes(pgsStream())
        val demuxer = AndroidFfmpegDemuxer()
        try {
            val opened = demuxer.open(YDemuxSource(fixture.absolutePath))
            val track = opened.tracks.single()
            demuxer.selectTracks(setOf(track.id))
            val displays = mutableListOf<YSubtitleDecodeResult.DisplaySet>()
            var noOutputCount = 0
            while (true) {
                val sample = demuxer.readSample() ?: break
                when (val decoded = demuxer.decodeSubtitle(sample)) {
                    is YSubtitleDecodeResult.DisplaySet -> displays += decoded
                    YSubtitleDecodeResult.NoOutput -> noOutputCount++
                    is YSubtitleDecodeResult.Append -> error("PGS must decode to a display set")
                }
            }
            assertTrue("Palette/object packets must stay distinct from empty clears", noOutputCount > 0)
            assertEquals(listOf(0L, 1_000_000L, 3_000_000L, 5_000_000L), displays.map { it.startUs })
            assertEquals(listOf(0, 2, 1, 0), displays.map { it.cues.size })
            assertTrue(displays.flatMap { it.cues }.all { it.endUs == Long.MAX_VALUE })
            val buffer = YSubtitleCueBuffer()
            displays.forEach(buffer::apply) // All events are prefetched before any rendering query.
            val timeline = YSubtitleTimeline(buffer.toList())
            assertEquals(
                listOf(10, 20),
                timeline.activeAt(1_500_000L).map { (it.payload as YSubtitlePayload.BitmapArgb).x },
            )
            assertEquals(
                listOf(30),
                timeline.activeAt(3_500_000L).map { (it.payload as YSubtitlePayload.BitmapArgb).x },
            )
            assertTrue(timeline.activeAt(5_500_000L).isEmpty())
            assertTrue(timeline.activeAt(3_600_000_000L).isEmpty())
        } finally {
            demuxer.close()
            fixture.delete()
        }
    }

    private fun pgsStream(): ByteArray =
        bytes {
            display(0, 0, emptyList()) // Establish the media origin without showing a picture.
            display(90_000, 1, listOf(10, 20))
            display(270_000, 2, listOf(30))
            display(450_000, 3, emptyList())
        }

    private fun DataOutputStream.display(
        pts90k: Int,
        number: Int,
        xPositions: List<Int>,
    ) {
        segment(
            pts90k,
            0x16,
            bytes {
                writeShort(64)
                writeShort(48)
                writeByte(0x10)
                writeShort(number)
                writeByte(0x80) // Epoch start; this display supplies its own palette and objects.
                writeByte(0)
                writeByte(0)
                writeByte(xPositions.size)
                xPositions.forEachIndexed { id, x ->
                    writeShort(id)
                    writeByte(0)
                    writeByte(0)
                    writeShort(x)
                    writeShort(40)
                }
            },
        )
        if (xPositions.isNotEmpty()) {
            segment(pts90k, 0x14, byteArrayOf(0, 0, 1, 235.toByte(), 128.toByte(), 128.toByte(), 255.toByte()))
            xPositions.indices.forEach { id ->
                segment(
                    pts90k,
                    0x15,
                    bytes {
                        writeShort(id)
                        writeByte(0)
                        writeByte(0xc0) // Complete object in one segment.
                        writeByte(0)
                        writeShort(8) // Width/height (4 bytes) plus RLE (4 bytes).
                        writeShort(2)
                        writeShort(1)
                        write(byteArrayOf(1, 1, 0, 0)) // Two white pixels and end-of-line.
                    },
                )
            }
        }
        segment(pts90k + 22_500, 0x80, byteArrayOf()) // END arrives 250 ms after its composition.
    }

    private fun DataOutputStream.segment(
        pts90k: Int,
        type: Int,
        data: ByteArray,
    ) {
        writeShort(0x5047)
        writeInt(pts90k)
        writeInt(0)
        writeByte(type)
        writeShort(data.size)
        write(data)
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply { DataOutputStream(this).use(block) }.toByteArray()
}
