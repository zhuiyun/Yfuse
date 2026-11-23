package com.yfuse.core2.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.dolby.dolbyVisionHevcBaseLayerSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Real bundled JNI, including buffer-growth retries and an EOF with a second field pending. */
@RunWith(AndroidJUnit4::class)
class SoftwareOutputContractInstrumentedTest {
    @Test
    fun profile_five_maps_each_rpu_before_and_after_seek_and_rejects_missing_metadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val file = File.createTempFile("profile-five-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            val fixture = assets.open("ycore/dolby-profile5-swatches.mp4.b64").use { it.readBytes() }
            file.writeBytes(Base64.decode(fixture, Base64.DEFAULT))
            val references =
                assets.open("ycore/dolby-profile5-sdr.csv").bufferedReader().useLines { lines ->
                    val points = lines.drop(1).map { line -> line.split(',').map(String::toInt) }
                    points.groupBy { it.first().toLong() }
                }
            assertEquals(6, references.size)
            val demux = AndroidFfmpegDemuxer()
            val track =
                try {
                    demux
                        .open(YDemuxSource(file.absolutePath))
                        .tracks
                        .mapNotNull { it.video }
                        .single()
                } finally {
                    demux.close()
                }
            val plan = softwareRecoveryPlanFromDemux(track, protectedContent = false)
            assertTrue(plan.softwareVideoToneMap && !plan.extractsDolbyBaseLayer)
            assertEquals(YHdrType.DolbyVision, plan.inputHdrType)
            assertEquals(YHdrType.Sdr, plan.outputHdrType)
            val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
            try {
                FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, true, requireProfile5Mapping = true)
                val initial = checkProfile5Frames(handle, references)
                assertEquals(references.keys.sorted(), initial)
                FfmpegNativeBridge.seek(handle, 500_000L)
                FfmpegNativeBridge.flushSoftwareDecoder(handle, 0)
                val after = checkProfile5Frames(handle, references, minimumUs = 500_000L)
                assertEquals(initial.filter { it >= 500_000L }, after)
            } finally {
                FfmpegNativeBridge.close(handle)
            }
            checkProfile5WithoutRpu(file, requireNotNull(track.samplePacking))
        } finally {
            file.delete()
        }
    }

    private fun checkProfile5Frames(
        handle: Long,
        references: Map<Long, List<List<Int>>>,
        minimumUs: Long = Long.MIN_VALUE,
    ): List<Long> =
        buildList {
            decode(
                handle,
                video = true,
                minimumPresentationTimeUs = minimumUs,
                skipped = { _, _ -> },
            ) { metadata, data ->
                val pts = metadata[2]
                val coords = references.getValue(pts)
                assertEquals(8, coords.size)
                for (point in coords) {
                    val at = point[2] * metadata[5].toInt() + 4 * point[1]
                    for (channel in 0 until 3) {
                        val value = data.get(at + channel).toInt() and 255
                        val label = "PTS $pts at ${point[1]},${point[2]}, channel $channel got $value"
                        assertTrue(label, abs(value - point[3 + channel]) <= 2)
                    }
                    assertEquals(255, data.get(at + 3).toInt() and 255)
                }
                add(pts)
            }
        }

    private fun checkProfile5WithoutRpu(
        file: File,
        packing: YSamplePacking,
    ) {
        val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
        try {
            FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
            FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, true, requireProfile5Mapping = true)
            val failure =
                runCatching {
                    decode(
                        handle,
                        video = true,
                        packetTransform = { dolbyVisionHevcBaseLayerSample(it, packing, packing) },
                    ) { _, _ ->
                        error("A Profile 5 frame without an RPU produced pixels")
                    }
                }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("supported per-frame RPU"))
        } finally {
            FfmpegNativeBridge.close(handle)
        }
    }

    @Test
    fun compatible_base_keeps_all_six_tone_mapped_pictures_and_timestamps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("hdr-base-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            val encoded =
                instrumentation.context.assets
                    .open(
                        "ycore/hdr10-compatible-base.mp4.b64",
                    ).use { it.readBytes() }
            file.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
            val demux = AndroidFfmpegDemuxer()
            val track =
                try {
                    demux
                        .open(YDemuxSource(file.absolutePath))
                        .tracks
                        .mapNotNull { it.video }
                        .single()
                } finally {
                    demux.close()
                }
            val packing = requireNotNull(track.samplePacking)
            assertEquals(YSamplePacking.LengthPrefixed(4), packing)
            assertEquals(8, track.dolbyVisionConfig?.profile)
            val plan = softwareRecoveryPlanFromDemux(track, protectedContent = false)
            assertTrue(plan.usesHdrFallback && plan.softwareVideoToneMap)
            assertEquals(YHdrType.Hdr10, plan.inputHdrType)
            assertEquals(YHdrType.Sdr, plan.outputHdrType)
            val reference = toneMappedPictures(file) { it }
            // The generated movie has an HDR10 base. Each second-pass access unit also contains
            // one synthetic enhancement NAL; its payload must never reach the base decoder.
            val enhancement = byteArrayOf(0, 0, 0, 3, 126, 1, 85)
            val filtered =
                toneMappedPictures(file) { dolbyVisionHevcBaseLayerSample(it + enhancement, packing, packing) }
            assertEquals(6, reference.size)
            assertTrue(reference.map { it.second }.distinct().size > 1)
            assertTrue(reference.zipWithNext().all { (a, b) -> a.first < b.first })
            assertEquals(reference, filtered)
        } finally {
            file.delete()
        }
    }

    private fun toneMappedPictures(
        file: File,
        packetTransform: (ByteArray) -> ByteArray,
    ): List<Pair<Long, List<Byte>>> {
        val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
        try {
            FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
            FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, true)
            return buildList {
                decode(handle, video = true, packetTransform = packetTransform) { metadata, data ->
                    val bytes = ByteArray(metadata[1].toInt())
                    data.duplicate().apply { position(0) }.get(bytes)
                    add(metadata[2] to MessageDigest.getInstance("SHA-256").digest(bytes).toList())
                }
            }
        } finally {
            FfmpegNativeBridge.close(handle)
        }
    }

    @Test
    fun interlaced_video_delivers_both_fields_with_monotonic_timestamps() {
        for ((fixture, fieldDurationUs) in listOf("top25" to 20_000L, "bottom30" to 16_683L)) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val file = File(instrumentation.targetContext.cacheDir, "$fixture.mkv")
            file.writeBytes(
                Base64.decode(
                    instrumentation.context.assets.open("ycore/$fixture.mkv.b64").use {
                        it.readBytes()
                    },
                    Base64.DEFAULT,
                ),
            )
            try {
                val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
                try {
                    FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                    FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, false)
                    val pts = mutableListOf<Long>()
                    decode(handle, video = true) { metadata, _ -> pts += metadata[2] }
                    assertEquals("Every interlaced picture must deliver two fields", 6, pts.size)
                    assertTrue(pts.zipWithNext().all { (a, b) -> b > a })
                    for (first in listOf(0, 2, 4)) {
                        assertTrue(
                            "Field spacing for $fixture: $pts",
                            kotlin.math.abs(pts[first + 1] - pts[first] - fieldDurationUs) < 1_000L,
                        )
                    }
                    FfmpegNativeBridge.flushSoftwareDecoder(handle, 0)
                    assertEquals(
                        0L,
                        FfmpegNativeBridge.receiveSoftwareVideoFrame(handle, 0, ByteBuffer.allocateDirect(1))[0],
                    )
                } finally {
                    FfmpegNativeBridge.close(handle)
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun overdue_interlaced_fields_skip_conversion_and_preserve_the_next_field() {
        assertTrue(FfmpegNativeBridge.softwareVideoTimed)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "timed-top25.mkv")
        file.writeBytes(
            Base64.decode(
                instrumentation.context.assets
                    .open("ycore/top25.mkv.b64")
                    .use { it.readBytes() },
                Base64.DEFAULT,
            ),
        )
        try {
            val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
            try {
                FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, false)
                val presented = mutableListOf<Long>()
                val discarded = mutableListOf<Long>()
                decode(
                    handle,
                    video = true,
                    minimumPresentationTimeUs = 50_000L,
                    skipped = { metadata, buffer ->
                        discarded += metadata[2]
                        // No oversized RGBA allocation or conversion may precede a discarded field.
                        assertEquals(1, buffer.capacity())
                    },
                ) { metadata, _ -> presented += metadata[2] }
                assertEquals(listOf(0L, 20_000L, 40_000L), discarded)
                assertEquals(listOf(60_000L, 80_000L, 100_000L), presented)
            } finally {
                FfmpegNativeBridge.close(handle)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun float_pcm_retains_bits_below_signed_16_resolution() {
        assertTrue("Bundled native audio must support float PCM", FfmpegNativeBridge.softwareAudioFloat)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "precision24.wav")
        val samples = intArrayOf(1, -1, 255, -255, 65537, -65537)
        val wave = ByteBuffer.allocate(44 + samples.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(wave.capacity() - 8).put("WAVEfmt ".toByteArray())
        wave
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(48000)
            .putInt(144000)
            .putShort(3)
            .putShort(24)
        wave.put("data".toByteArray()).putInt(samples.size * 3)
        samples.forEach { value -> repeat(3) { byte -> wave.put((value shr (8 * byte)).toByte()) } }
        file.writeBytes(wave.array())
        try {
            val handle = FfmpegNativeBridge.open(file.absolutePath, emptyMap())
            try {
                FfmpegNativeBridge.selectTracks(handle, intArrayOf(0))
                FfmpegNativeBridge.configureSoftwareDecoder(handle, 0, false)
                val decoded = mutableListOf<Float>()
                decode(handle, video = false) { metadata, data ->
                    assertEquals(1L, metadata[3])
                    assertEquals(48000L, metadata[4])
                    repeat(metadata[5].toInt()) { decoded += data.getFloat(it * 4) }
                }
                assertEquals(samples.size, decoded.size)
                samples.forEachIndexed { index, value -> assertEquals(value / 8388608f, decoded[index], 0f) }
            } finally {
                FfmpegNativeBridge.close(handle)
            }
        } finally {
            file.delete()
        }
    }

    private fun decode(
        handle: Long,
        video: Boolean,
        minimumPresentationTimeUs: Long = Long.MIN_VALUE,
        skipped: (LongArray, ByteBuffer) -> Unit = { _, _ -> error("Unexpected discarded frame") },
        packetTransform: (ByteArray) -> ByteArray = { it },
        output: (LongArray, ByteBuffer) -> Unit,
    ) {
        val packetBuffer = ByteBuffer.allocateDirect(65536)
        var frameBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
        var eof = false
        repeat(100) {
            while (true) {
                val result =
                    if (video) {
                        FfmpegNativeBridge.receiveSoftwareVideoFrame(handle, 0, frameBuffer, minimumPresentationTimeUs)
                    } else {
                        FfmpegNativeBridge.receiveSoftwareAudioFrame(handle, 0, frameBuffer)
                    }
                when (result[0]) {
                    -1L -> frameBuffer = ByteBuffer.allocateDirect(result[1].toInt()).order(ByteOrder.nativeOrder())
                    1L -> output(result, frameBuffer)
                    3L -> skipped(result, frameBuffer)
                    2L -> return
                    0L -> break
                    else -> error("Unexpected software frame ${result[0]}")
                }
            }
            check(!eof) { "Decoder requested input after draining EOF" }
            packetBuffer.clear()
            val packet = FfmpegNativeBridge.readPacket(handle, packetBuffer)
            if (packet[0] == 0L) {
                eof = true
                check(FfmpegNativeBridge.sendSoftwarePacket(handle, 0, null, null, null))
            } else {
                check(packet[0] == 1L && packet[1] == 0L)
                val data = ByteArray(packet[2].toInt())
                packetBuffer.position(0)
                packetBuffer.get(data)
                check(FfmpegNativeBridge.sendSoftwarePacket(handle, 0, packetTransform(data), packet[3], packet[4]))
            }
        }
        error("Software decoder did not reach EOF")
    }
}
