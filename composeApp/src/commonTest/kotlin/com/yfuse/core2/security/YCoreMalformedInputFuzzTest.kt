package com.yfuse.core2.security

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.dolby.splitDolbyVisionLayers
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Deterministic malformed-input lane for the parsers that run before MediaCodec. */
class YCoreMalformedInputFuzzTest {
    @Test
    fun random_access_units_are_bounded_and_never_escape_the_input() {
        val random = Random(0x59434f52)
        repeat(5_000) { iteration ->
            val data = random.nextBytes(random.nextInt(0, 4_097))
            val packing =
                if (iteration and 1 == 0) {
                    YSamplePacking.AnnexB
                } else {
                    YSamplePacking.LengthPrefixed()
                }
            val units = runCatching { YBitstream.scan(data, YNalCodec.H265, packing) }.getOrElse { emptyList() }
            units.forEach { unit ->
                assertTrue(unit.offset >= 0)
                assertTrue(unit.length > 0)
                assertTrue(unit.offset + unit.length <= data.size)
            }
            runCatching { splitDolbyVisionLayers(data, packing, iteration.toLong()) }
                .getOrNull()
                ?.let { split ->
                    assertTrue(split.baseLayer.size <= data.size + units.size * 4)
                    assertTrue(split.rpu.sumOf(ByteArray::size) <= data.size + units.size * 4)
                    assertTrue(split.enhancementLayer.sumOf(ByteArray::size) <= data.size + units.size * 4)
                }
        }
    }

    @Test
    fun arbitrary_dolby_configuration_round_trips_without_hidden_bytes() {
        val random = Random(0x44564943)
        repeat(10_000) {
            val raw = random.nextBytes(5)
            val parsed = YDolbyVisionConfig.parse(raw)
            val encoded = parsed.toConfigurationBytes()
            assertTrue(encoded.size == 5)
            assertContentEquals(encoded, YDolbyVisionConfig.parse(encoded).toConfigurationBytes())
        }
    }
}
