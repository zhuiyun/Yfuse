package com.yfuse.core2.android

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Test-only fragment writer for the fixed, 100-frame, single-AVC synthetic fixture. Not a general MP4 muxer. */
internal class GeneratedDashTestMedia private constructor(
    private val directory: File,
    val initialization: File,
    val segment: File,
    val codec: String,
    val width: Int,
    val height: Int,
) : Closeable {
    override fun close() {
        check(directory.deleteRecursively() || !directory.exists()) { "Generated DASH cleanup failed" }
    }

    companion object {
        const val DURATION_US = 10_000_000L
        private const val TIMESCALE = 1_000_000
        private const val FRAME_US = 100_000
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val SAMPLE_BYTES = 256 * 1024

        fun create(
            cacheDirectory: File,
            width: Int = GeneratedAvcTestMedia.WIDTH,
            height: Int = GeneratedAvcTestMedia.HEIGHT,
        ): GeneratedDashTestMedia {
            val original = GeneratedAvcTestMedia.create(cacheDirectory, width, height)
            val directory =
                try {
                    Files.createTempDirectory(cacheDirectory.toPath(), "ycore-generated-dash-").toFile()
                } catch (failure: Throwable) {
                    original.delete()
                    throw failure
                }
            var success = false
            try {
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                check(original.length() in 1L..MAX_BYTES.toLong()) { "Generated AVC exceeds fixture byte budget" }
                val source = original.readBytes()
                var container = Box(0, source.size, 0)
                for (type in listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
                    container = child(source, container.payload, container.end, type)
                }
                check(readInt(source, container.payload + 4) == 1) { "Fixture must have one AVC sample entry" }
                val sampleEntry = child(source, container.payload + 8, container.end, "avc1")
                val avcConfiguration = child(source, sampleEntry.payload + 78, sampleEntry.end, "avcC")
                check(source[avcConfiguration.payload].toInt() == 1)
                check((source[avcConfiguration.payload + 4].toInt() and 3) + 1 == 4) {
                    "Generated AVC fixture requires four-byte NAL lengths"
                }
                val codec =
                    "avc1." +
                        (1..3).joinToString("") { index ->
                            "%02x".format(source[avcConfiguration.payload + index].toInt() and 255)
                        }
                val entryBytes = source.copyOfRange(sampleEntry.start, sampleEntry.end)
                val samples = readSamples(original, deadline, width, height)
                val init = initialization(entryBytes, width, height)
                // MediaExtractor returns Annex-B access units; avcC sample entries require NAL lengths in mdat.
                val packedSamples = samples.map { it.copy(bytes = lengthPrefixedAvc(it.bytes)) }
                val media = fragment(packedSamples)
                val initFile = File(directory, "init.mp4").apply { writeBytes(init) }
                val mediaFile = File(directory, "segment-1.m4s").apply { writeBytes(media) }
                // Independently validate sample tables and fragment offsets with the platform extractor.
                val joined = File(directory, "validate.mp4")
                try {
                    joined.outputStream().use {
                        it.write(init)
                        it.write(media)
                    }
                    val extracted = readSamples(joined, deadline, width, height)
                    val mismatch =
                        samples.indices.firstOrNull { index ->
                            extracted[index].sync != samples[index].sync ||
                                !extracted[index].bytes.contentEquals(samples[index].bytes)
                        }
                    check(mismatch == null) {
                        val actual = extracted[checkNotNull(mismatch)]
                        val expected = samples[mismatch]
                        val byteMismatch =
                            (0 until minOf(actual.bytes.size, expected.bytes.size)).firstOrNull {
                                actual.bytes[it] != expected.bytes[it]
                            }
                        "Fragmented AVC mismatch: frame=$mismatch, actualSync=${actual.sync}, " +
                            "expectedSync=${expected.sync}, actualBytes=${actual.bytes.size}, " +
                            "expectedBytes=${expected.bytes.size}, firstByteMismatch=$byteMismatch"
                    }
                } finally {
                    joined.delete()
                }
                checkDeadline(deadline)
                success = true
                return GeneratedDashTestMedia(directory, initFile, mediaFile, codec, width, height)
            } finally {
                val originalRemoved = original.delete() || !original.exists()
                if (!success || !originalRemoved) directory.deleteRecursively()
                check(originalRemoved) { "Generated AVC cleanup failed" }
            }
        }

        private data class Sample(
            val bytes: ByteArray,
            val sync: Boolean,
        )

        private fun lengthPrefixedAvc(bytes: ByteArray): ByteArray {
            fun isStartCode(offset: Int): Boolean =
                offset <= bytes.size - 4 &&
                    bytes[offset] == 0.toByte() &&
                    bytes[offset + 1] == 0.toByte() &&
                    bytes[offset + 2] == 0.toByte() &&
                    bytes[offset + 3] == 1.toByte()

            check(bytes.size in 5..SAMPLE_BYTES && isStartCode(0)) {
                "Platform AVC sample must begin with a four-byte Annex-B start code; size=${bytes.size}"
            }
            val output = ByteArrayOutputStream(bytes.size)
            DataOutputStream(output).use { stream ->
                var nalStart = 4
                while (nalStart < bytes.size) {
                    var next = nalStart
                    while (next < bytes.size && !isStartCode(next)) next++
                    check(next > nalStart) { "Generated AVC contains an empty NAL unit" }
                    stream.writeInt(next - nalStart)
                    stream.write(bytes, nalStart, next - nalStart)
                    if (next == bytes.size) break
                    check(next < bytes.size - 4) { "Generated AVC has a trailing Annex-B start code" }
                    nalStart = next + 4
                }
            }
            return output.toByteArray().also { check(it.size == bytes.size) }
        }

        private fun readSamples(
            file: File,
            deadline: Long,
            width: Int,
            height: Int,
        ): List<Sample> {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                check(extractor.trackCount == 1) { "DASH fixture supports exactly one AVC track" }
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
                check(format.getInteger(MediaFormat.KEY_WIDTH) == width)
                check(format.getInteger(MediaFormat.KEY_HEIGHT) == height)
                extractor.selectTrack(0)
                val buffer = ByteBuffer.allocate(SAMPLE_BYTES)
                val samples = mutableListOf<Sample>()
                var total = 0
                var lastTimeUs = -1L
                while (extractor.sampleTime >= 0L) {
                    checkDeadline(deadline)
                    check(samples.size < 100) { "DASH fixture exceeded its frame budget" }
                    // This narrow fixture has no B frames: DTS equals the generated constant-rate PTS.
                    check(extractor.sampleTime == samples.size * FRAME_US.toLong()) {
                        "DASH fixture ${file.name}: frame=${samples.size}, pts=${extractor.sampleTime}, " +
                            "expected=${samples.size * FRAME_US.toLong()}"
                    }
                    lastTimeUs = extractor.sampleTime
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() == 0)
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    check(count in 1..SAMPLE_BYTES && total <= MAX_BYTES - count)
                    val bytes = ByteArray(count)
                    buffer.position(0)
                    buffer.get(bytes)
                    samples += Sample(bytes, extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    total += count
                    if (!extractor.advance()) break
                }
                check(samples.size == 100 && samples.first().sync) {
                    "Incomplete AVC fixture ${file.name}: count=${samples.size}, " +
                        "firstSync=${samples.firstOrNull()?.sync}, lastPtsUs=$lastTimeUs, bytes=$total, format=$format"
                }
                return samples
            } finally {
                extractor.release()
            }
        }

        private fun initialization(
            sampleEntry: ByteArray,
            width: Int,
            height: Int,
        ): ByteArray {
            val ftyp =
                box("ftyp") {
                    type("iso6")
                    writeInt(1)
                    type("iso6")
                    type("mp41")
                    type("avc1")
                }
            val mvhd =
                fullBox("mvhd") {
                    writeInt(0)
                    writeInt(0)
                    writeInt(TIMESCALE)
                    writeInt(DURATION_US.toInt())
                    writeInt(0x00010000)
                    writeShort(0x0100)
                    writeShort(0)
                    writeLong(0L)
                    matrix()
                    repeat(6) { writeInt(0) }
                    writeInt(2)
                }
            val tkhd =
                fullBox("tkhd", flags = 7) {
                    writeInt(0)
                    writeInt(0)
                    writeInt(1)
                    writeInt(0)
                    writeInt(DURATION_US.toInt())
                    writeLong(0L)
                    repeat(4) { writeShort(0) }
                    matrix()
                    writeInt(width shl 16)
                    writeInt(height shl 16)
                }
            val mdhd =
                fullBox("mdhd") {
                    writeInt(0)
                    writeInt(0)
                    writeInt(TIMESCALE)
                    writeInt(DURATION_US.toInt())
                    writeShort(0x55c4)
                    writeShort(0)
                }
            val hdlr =
                fullBox("hdlr") {
                    writeInt(0)
                    type("vide")
                    repeat(3) { writeInt(0) }
                    write("Synthetic AVC\u0000".toByteArray())
                }
            val vmhd = fullBox("vmhd", flags = 1) { repeat(4) { writeShort(0) } }
            val dinf =
                box("dinf") {
                    write(
                        fullBox("dref") {
                            writeInt(1)
                            write(fullBox("url ", flags = 1) {})
                        },
                    )
                }
            val stbl =
                box("stbl") {
                    write(
                        fullBox("stsd") {
                            writeInt(1)
                            write(sampleEntry)
                        },
                    )
                    for (type in listOf("stts", "stsc", "stco")) write(fullBox(type) { writeInt(0) })
                    write(
                        fullBox("stsz") {
                            writeInt(0)
                            writeInt(0)
                        },
                    )
                }
            val mdia =
                box("mdia") {
                    write(mdhd)
                    write(hdlr)
                    write(
                        box("minf") {
                            write(vmhd)
                            write(dinf)
                            write(stbl)
                        },
                    )
                }
            val mvex =
                box("mvex") {
                    write(
                        fullBox("trex") {
                            writeInt(1)
                            writeInt(1)
                            writeInt(FRAME_US)
                            writeInt(0)
                            writeInt(0)
                        },
                    )
                }
            return ftyp +
                box("moov") {
                    write(mvhd)
                    write(
                        box("trak") {
                            write(tkhd)
                            write(mdia)
                        },
                    )
                    write(mvex)
                }
        }

        private fun fragment(samples: List<Sample>): ByteArray {
            // Android 9's extractor recognizes only the first sample of each fragment as sync.
            // Preserve every original random-access point with one indexed fragment per GOP.
            val groups = mutableListOf<MutableList<Sample>>()
            samples.forEach { sample ->
                if (sample.sync) groups.add(mutableListOf())
                check(groups.isNotEmpty()) { "Generated AVC must start with a random-access sample" }
                groups.last().add(sample)
            }
            check(groups.size in 1..100)
            var decodeTimeUs = 0L
            val fragments =
                groups.mapIndexed { index, group ->
                    fun moof(dataOffset: Int) =
                        box("moof") {
                            write(fullBox("mfhd") { writeInt(index + 1) })
                            write(
                                box("traf") {
                                    write(fullBox("tfhd", flags = 0x020000) { writeInt(1) })
                                    write(fullBox("tfdt", version = 1) { writeLong(decodeTimeUs) })
                                    write(
                                        fullBox("trun", flags = 0x000701) {
                                            writeInt(group.size)
                                            writeInt(dataOffset)
                                            group.forEach { sample ->
                                                writeInt(FRAME_US)
                                                writeInt(sample.bytes.size)
                                                writeInt(if (sample.sync) 0x02000000 else 0x01010000)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    val moof = moof(moof(0).size + 8)
                    val mdat = box("mdat") { group.forEach { write(it.bytes) } }
                    decodeTimeUs += group.size * FRAME_US.toLong()
                    moof + mdat
                }
            check(decodeTimeUs == DURATION_US)
            val sidx =
                fullBox("sidx") {
                    writeInt(1)
                    writeInt(TIMESCALE)
                    writeInt(0)
                    writeInt(0)
                    writeShort(0)
                    writeShort(fragments.size)
                    fragments.forEachIndexed { index, bytes ->
                        writeInt(bytes.size)
                        writeInt(groups[index].size * FRAME_US)
                        writeInt(0x90000000.toInt())
                    }
                }
            val styp =
                box("styp") {
                    type("msdh")
                    writeInt(0)
                    type("msdh")
                    type("msix")
                }
            return ByteArrayOutputStream()
                .also { output ->
                    output.write(styp)
                    output.write(sidx)
                    fragments.forEach(output::write)
                    check(output.size() <= MAX_BYTES) { "Generated DASH exceeds fixture byte budget" }
                }.toByteArray()
        }

        private fun box(
            type: String,
            body: DataOutputStream.() -> Unit,
        ): ByteArray {
            val payload = ByteArrayOutputStream().also { output -> DataOutputStream(output).use(body) }.toByteArray()
            check(payload.size <= MAX_BYTES - 8)
            return ByteArrayOutputStream(payload.size + 8)
                .also { output ->
                    DataOutputStream(output).use {
                        it.writeInt(payload.size + 8)
                        it.type(type)
                        it.write(payload)
                    }
                }.toByteArray()
        }

        private fun fullBox(
            type: String,
            version: Int = 0,
            flags: Int = 0,
            body: DataOutputStream.() -> Unit,
        ): ByteArray =
            box(type) {
                writeInt((version shl 24) or flags)
                body()
            }

        private fun DataOutputStream.type(value: String) {
            check(value.length == 4)
            write(value.toByteArray(Charsets.US_ASCII))
        }

        private fun DataOutputStream.matrix() {
            listOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach(::writeInt)
        }

        private data class Box(
            val start: Int,
            val end: Int,
            val payload: Int,
        )

        private fun child(
            bytes: ByteArray,
            start: Int,
            end: Int,
            type: String,
        ): Box {
            var offset = start
            while (offset <= end - 8) {
                val shortSize = readInt(bytes, offset)
                val header = if (shortSize == 1) 16 else 8
                check(offset <= end - header)
                val longSize =
                    when (shortSize) {
                        0 -> (end - offset).toLong()
                        1 -> ByteBuffer.wrap(bytes, offset + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                        else -> shortSize.toLong()
                    }
                check(longSize >= header && longSize <= end - offset) { "Truncated generated MP4 box" }
                val size = longSize.toInt()
                if (bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII) == type) {
                    return Box(offset, offset + size, offset + header)
                }
                offset += size
            }
            error("Generated MP4 is missing $type")
        }

        private fun readInt(
            bytes: ByteArray,
            offset: Int,
        ): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

        private fun checkDeadline(deadline: Long) {
            check(!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                "Generated DASH fragment exceeded its 20 second budget"
            }
        }
    }
}
