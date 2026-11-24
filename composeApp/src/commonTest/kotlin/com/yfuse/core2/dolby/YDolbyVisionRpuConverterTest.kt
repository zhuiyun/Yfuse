package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.DefaultYPlaybackStrategy
import com.yfuse.core2.strategy.YPlaybackRequest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YDolbyVisionRpuConverterTest {
    @Test
    fun `profile 7 MEL access unit strips EL NAL and keeps compliant RPU intact`() {
        val melRpu = buildSyntheticRpuNal(isFel = false)
        val baseSlice = byteArrayOf(0x26, 0x01, 0xAA.toByte(), 0x55) // IDR_W_RADL (type 19)
        val elNal = byteArrayOf(0x7E, 0x01, 0x11, 0x22, 0x33) // UNSPEC63 (type 63)
        val accessUnit = packAnnexB(baseSlice, melRpu, elNal)

        val converted = convertDolbyVisionProfile7To8Sample(accessUnit, YSamplePacking.AnnexB)
        val spans = YBitstream.scan(converted, YNalCodec.H265, YSamplePacking.AnnexB)

        assertEquals(listOf(19, 62), spans.map { it.type })
        val outRpu = converted.copyOfRange(spans[1].offset, spans[1].offset + spans[1].length)
        assertContentEquals(melRpu, outRpu)
    }

    @Test
    fun `profile 7 12-bit FEL RPU converts to profile 8 point 1 with valid CRC and EPB escaping`() {
        val felRpu = buildSyntheticRpuNal(isFel = true)
        val expectedP81Rpu = buildSyntheticRpuNal(isFel = false)
        val baseSlice = byteArrayOf(0x02, 0x01, 0x44, 0x66) // TRAIL_R (type 1)
        val elNal = byteArrayOf(0x7E, 0x01, 0x99.toByte(), 0x88.toByte()) // UNSPEC63 (type 63)
        val accessUnit = packAnnexB(baseSlice, felRpu, elNal)

        val converted = convertDolbyVisionProfile7To8Sample(accessUnit, YSamplePacking.AnnexB)
        val spans = YBitstream.scan(converted, YNalCodec.H265, YSamplePacking.AnnexB)

        assertEquals(listOf(1, 62), spans.map { it.type })
        val rewrittenRpu = converted.copyOfRange(spans[1].offset, spans[1].offset + spans[1].length)
        assertFalse(felRpu.contentEquals(rewrittenRpu))
        assertContentEquals(expectedP81Rpu, rewrittenRpu)

        // Converting an already-converted Profile 8.1 RPU is idempotent and verifies its MPEG-2 CRC-32.
        val secondPass = convertDolbyVisionProfile7RpuNalTo81(rewrittenRpu)
        assertNotNull(secondPass)
        assertContentEquals(rewrittenRpu, secondPass)
    }

    @Test
    fun `profile 7 config rewrites cleanly to single-layer profile 8 point 1`() {
        val p7 =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 7,
                level = 6,
                rpuPresent = true,
                enhancementLayerPresent = true,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 6,
                metadataCompression = 0,
            )

        val p81 = p7.toProfile81Config()
        val reparsed = YDolbyVisionConfig.parse(p81.toConfigurationBytes())

        assertEquals(8, reparsed.profile)
        assertEquals(1, reparsed.baseLayerCompatibilityId)
        assertTrue(reparsed.rpuPresent)
        assertTrue(reparsed.baseLayerPresent)
        assertFalse(reparsed.enhancementLayerPresent)
    }

    @Test
    fun `strategy enables P7 to P8 conversion for both hardware audio and software audio routes`() {
        val strategy = DefaultYPlaybackStrategy()
        val request =
            YPlaybackRequest(
                container = YContainer.Matroska,
                video =
                    YVideoRequirement(
                        codec = YVideoCodec.H265,
                        width = 3840,
                        height = 2160,
                        frameRate = 23.976f,
                        bitDepth = 10,
                        hdrType = YHdrType.DolbyVision,
                        dolbyVisionProfile = 7,
                    ),
                fallbackHdrType = YHdrType.Hdr10,
                audio = YAudioRequirement(codec = YAudioCodec.TrueHdAtmos, channelCount = 8, sampleRate = 48_000),
                platformDemuxSupported = true,
                enhancedDemuxSupported = true,
            )
        val capabilities =
            YDeviceCapabilities(
                videoDecoders =
                    listOf(
                        YVideoDecoderCapability(
                            name = "c2.vendor.dv.hevc.decoder",
                            codec = YVideoCodec.H265,
                            hdrTypes = setOf(YHdrType.Sdr, YHdrType.Hdr10, YHdrType.DolbyVision),
                            dolbyVisionProfiles = setOf(8),
                            maxWidth = 3840,
                            maxHeight = 2160,
                            maxFrameRate = 60.0,
                            maxBitDepth = 10,
                        ),
                    ),
                displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.Hdr10, YHdrType.DolbyVision),
                audioDecoders = setOf(YAudioCodec.Aac),
            )

        // Software audio (TrueHD Atmos decoded via FFmpeg) + hardware P7->P8.1 video
        val swAudioPlan = strategy.plan(request, capabilities)
        assertTrue(swAudioPlan.convertsDolbyProfile7To8)
        assertTrue(swAudioPlan.softwareAudioDecode)
        assertFalse(swAudioPlan.usesHdrFallback)
        assertEquals(YHdrType.DolbyVision, swAudioPlan.outputHdrType)

        // Hardware audio passthrough + hardware P7->P8.1 video
        val hwAudioPlan =
            strategy.plan(
                request,
                capabilities.copy(audioPassthrough = setOf(YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos)),
            )
        assertTrue(hwAudioPlan.convertsDolbyProfile7To8)
        assertFalse(hwAudioPlan.softwareAudioDecode)
        assertFalse(hwAudioPlan.usesHdrFallback)
        assertEquals(YHdrType.DolbyVision, hwAudioPlan.outputHdrType)
    }

    private fun buildSyntheticRpuNal(isFel: Boolean): ByteArray {
        val writer = TestBitWriter()
        writer.writeBits(2, 6) // rpu_type = 2
        writer.writeBits(18, 11) // rpu_format = 18
        writer.writeBits(1, 4) // vdr_rpu_profile = 1
        writer.writeBits(0, 4) // vdr_rpu_level = 0
        writer.writeBit(1) // vdr_seq_info_present_flag = 1
        writer.writeBit(0) // chroma_resampling_explicit_filter_flag = 0
        writer.writeBits(0, 2) // coefficient_data_type = 0
        writer.writeUe(23) // coefficient_log2_denom = 23
        writer.writeBits(1, 2) // vdr_rpu_normalized_idc = 1
        writer.writeBit(0) // bl_video_full_range_flag = 0
        writer.writeUe(2) // bl_bit_depth_minus8 = 2 (10-bit)
        writer.writeUe(if (isFel) 4 else 2) // el_bit_depth_minus8 = 4 (12-bit FEL) or 2 (10-bit P8.1/MEL)
        writer.writeUe(4) // vdr_bit_depth_minus8 = 4 (12-bit)
        writer.writeBit(0) // spatial_resampling_filter_flag = 0
        writer.writeBits(0, 3) // reserved_zero_3bits = 0
        writer.writeBit(if (isFel) 1 else 0) // el_spatial_resampling_filter_flag
        writer.writeBit(if (isFel) 0 else 1) // disable_residual_flag

        writer.writeBit(1) // vdr_dm_metadata_present_flag = 1
        writer.writeBit(0) // use_prev_vdr_rpu_flag = 0
        writer.writeUe(0) // vdr_rpu_id = 0
        writer.writeUe(0) // mapping_color_space = 0
        writer.writeUe(0) // mapping_chroma_format_idc = 0

        // 3 components, 2 pivots each (num_pivots_minus2 = 0 -> [0, 1023]).
        repeat(3) {
            writer.writeUe(0)
            writer.writeBits(0, 10)
            writer.writeBits(1023, 10)
        }

        if (isFel) {
            writer.writeBits(0, 3) // nlq_method_idc = 0 (NLQ_LINEAR_DZ)
            writer.writeUe(0) // nlq_num_pivots_minus2 = 0 -> 2 pivots
            writer.writeBits(0, 10)
            writer.writeBits(1023, 10)
        }

        writer.writeUe(0) // num_x_partitions_minus1 = 0
        writer.writeUe(0) // num_y_partitions_minus1 = 0
        writer.writeUe(0) // overshoot_range_idc = 0

        // rpu_data_mapping(): 1 partition, 3 components, 1 polynomial piece each (order 0, linear_interp=0 -> 2 coeffs).
        repeat(3) {
            writer.writeUe(0) // mapping_idc = 0 (polynomial)
            writer.writeUe(0) // poly_order_minus1 = 0
            writer.writeBit(0) // linear_interp_flag = 0
            writer.writeSe(0)
            writer.writeBits(0, 23)
            writer.writeSe(1)
            writer.writeBits(0, 23)
        }

        if (isFel) {
            // rpu_data_nlq(): 1 partition, 1 pivot interval, 3 components with 12-bit nlq_offset.
            repeat(3) {
                writer.writeBits(0x100, 12) // nlq_offset (12-bit EL)
                writer.writeUe(1)
                writer.writeBits(0, 23) // vdr_in_max
                writer.writeUe(0)
                writer.writeBits(0x400000, 23) // linear_deadzone_slope
                writer.writeUe(0)
                writer.writeBits(0, 23) // linear_deadzone_threshold
            }
        }

        // vdr_dm_data_payload() with three zero bytes to exercise 0x000003 emulation-prevention escaping.
        writer.writeUe(0) // affected_dm_metadata_id
        writer.writeUe(0) // current_dm_metadata_id
        writer.writeUe(0) // scene_refresh_flag
        repeat(9) { writer.writeBits(if (it % 4 == 0) 0x0000 else 0x1000, 16) } // includes 0x000000 runs!
        repeat(3) { writer.writeBits(0, 32) }
        repeat(9) { writer.writeBits(0x0800, 16) }
        writer.writeBits(0xFFFF, 16) // signal_eotf
        writer.writeBits(0, 16)
        writer.writeBits(0, 16)
        writer.writeBits(0, 32)
        writer.writeBits(12, 5) // signal_bit_depth
        writer.writeBits(0, 2)
        writer.writeBits(0, 2)
        writer.writeBits(1, 2)
        writer.writeBits(62, 12) // source_min_pq
        writer.writeBits(3079, 12) // source_max_pq
        writer.writeBits(42, 10) // source_diagonal
        writer.writeUe(1) // num_ext_blocks = 1 (L1 block)
        writer.alignWithZeros()
        writer.writeUe(5) // ext_block_length = 5 bytes
        writer.writeBits(1, 8) // ext_block_level = 1
        // L1 payload: min_pq=62, max_pq=2800, avg_pq=900 (5 bytes)
        writer.writeBits(0x00, 8)
        writer.writeBits(0x3E, 8)
        writer.writeBits(0x0A, 8)
        writer.writeBits(0xF0, 8)
        writer.writeBits(0x38, 8)

        val payload = writer.toByteArray()
        val crc = doviMpeg2Crc32(payload)
        val rbsp = ByteArray(payload.size + 5)
        payload.copyInto(rbsp, 0)
        rbsp[payload.size] = (crc ushr 24).toByte()
        rbsp[payload.size + 1] = (crc ushr 16).toByte()
        rbsp[payload.size + 2] = (crc ushr 8).toByte()
        rbsp[payload.size + 3] = crc.toByte()
        rbsp[payload.lastIndex + 5] = 0x80.toByte()

        val escaped = escapeRbsp(rbsp)
        // HEVC NAL header for UNSPEC62 (type 62 -> (62 << 1) = 0x7C, layer=0, tid=1 -> 0x01) + 0x19 RPU prefix.
        return byteArrayOf(0x7C, 0x01, 0x19) + escaped
    }

    private fun packAnnexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class TestBitWriter {
        private var buf = ByteArray(256)
        private var pos = 0

        fun writeBit(bit: Int) {
            val idx = pos ushr 3
            if (idx >= buf.size) buf = buf.copyOf(buf.size * 2)
            if ((bit and 1) != 0) {
                buf[idx] = (buf[idx].toInt() or (1 shl (7 - (pos and 7)))).toByte()
            }
            pos++
        }

        fun writeBits(
            value: Int,
            count: Int,
        ) {
            for (s in count - 1 downTo 0) writeBit((value ushr s) and 1)
        }

        fun writeUe(value: Int) {
            val code = value + 1
            val bits = 32 - code.countLeadingZeroBits()
            repeat(bits - 1) { writeBit(0) }
            writeBits(code, bits)
        }

        fun writeSe(value: Int) {
            writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        }

        fun alignWithZeros() {
            while ((pos and 7) != 0) writeBit(0)
        }

        fun toByteArray(): ByteArray {
            alignWithZeros()
            return buf.copyOf(pos ushr 3)
        }
    }
}
