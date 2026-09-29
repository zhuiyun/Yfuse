package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The expected pixels come from the reference C decoder (woltapp/blurhash `C/decode.c`, punch 1),
 * run on the hashes from the format's own README. That decoder mixes float and double arithmetic,
 * so a channel may land one step away.
 */
class BlurHashTest {
    @Test
    fun the_readme_hash_decodes_as_the_reference_decoder_does() {
        val pixels = assertNotNull(BlurHash.decode(README_HASH, 32, 32))
        assertEquals(32 * 32, pixels.size)
        assertPixel(0x87A4B1, pixels, x = 0, y = 0, width = 32)
        assertPixel(0x89A6B5, pixels, x = 31, y = 0, width = 32)
        assertPixel(0x889093, pixels, x = 0, y = 31, width = 32)
        assertPixel(0x858E93, pixels, x = 31, y = 31, width = 32)
        assertPixel(0x9E7D6C, pixels, x = 16, y = 16, width = 32)
        assertPixel(0x948B85, pixels, x = 8, y = 24, width = 32)
    }

    @Test
    fun a_second_hash_and_an_uneven_size_match_the_reference_too() {
        val colourful = assertNotNull(BlurHash.decode("LGF5]+Yk^6#M@-5c,1J5@[or[Q6.", 32, 32))
        assertPixel(0xB076A3, colourful, x = 0, y = 0, width = 32)
        assertPixel(0xE15481, colourful, x = 31, y = 0, width = 32)
        assertPixel(0x6B7E82, colourful, x = 16, y = 16, width = 32)
        assertPixel(0x8F6162, colourful, x = 31, y = 31, width = 32)

        val tiny = assertNotNull(BlurHash.decode(README_HASH, 4, 3))
        assertPixel(0xA0ACAE, tiny, x = 3, y = 0, width = 4)
        assertPixel(0xA49186, tiny, x = 2, y = 1, width = 4)
        assertPixel(0x908684, tiny, x = 1, y = 2, width = 4)
    }

    @Test
    fun every_pixel_is_opaque_and_a_single_term_hash_is_one_flat_colour() {
        assertTrue(assertNotNull(BlurHash.decode(README_HASH, 8, 8)).all { it ushr 24 == 0xFF })
        // "005?}k": one component, #336699 — nothing but the average survives a decode.
        val flat = assertNotNull(BlurHash.decode("005?}k", 4, 4))
        flat.indices.forEach { assertPixel(0x336699, flat, x = it % 4, y = it / 4, width = 4) }
    }

    @Test
    fun the_average_colour_is_read_straight_from_the_hash() {
        assertEquals(0xFF979695.toInt(), BlurHash.averageColor(README_HASH))
        assertEquals(0xFF336699.toInt(), BlurHash.averageColor("005?}k"))
    }

    @Test
    fun anything_that_is_not_a_hash_decodes_to_nothing() {
        listOf(
            "",
            "LEHV6",
            // One character short of the twelve components its first character declares.
            README_HASH.dropLast(1),
            README_HASH + "0",
            // A character outside the base-83 alphabet.
            README_HASH.dropLast(1) + "é",
        ).forEach { hash ->
            assertNull(BlurHash.decode(hash, 32, 32), hash)
            assertNull(BlurHash.averageColor(hash), hash)
        }
        assertNull(BlurHash.decode(README_HASH, 0, 32))
        assertTrue(BlurHash.isValid(README_HASH))
    }

    private fun assertPixel(
        expectedRgb: Int,
        pixels: IntArray,
        x: Int,
        y: Int,
        width: Int,
    ) {
        val actual = pixels[y * width + x]
        listOf(16, 8, 0).forEach { shift ->
            val want = (expectedRgb shr shift) and 0xFF
            val got = (actual shr shift) and 0xFF
            assertTrue(
                abs(want - got) <= 1,
                "($x, $y) channel at bit $shift: expected $want, got $got",
            )
        }
    }

    private companion object {
        const val README_HASH = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"
    }
}
