package com.yfuse.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Base64UrlTest {
    @Test
    fun usesUrlSafeAlphabetWithoutPadding() {
        assertEquals("-_8", byteArrayOf(0xfb.toByte(), 0xff.toByte()).toBase64Url())
        assertTrue(byteArrayOf(0xfb.toByte(), 0xff.toByte()).contentEquals("-_8".base64UrlToBytes()))
        assertEquals("", byteArrayOf().toBase64Url())
        assertTrue("".base64UrlToBytes().isEmpty())
    }

    @Test
    fun rejectsPaddingStandardAlphabetInvalidLengthAndNonCanonicalTailBits() {
        listOf("AA==", "A+", "A/", "A", "AB", "AA\n").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("Expected rejection for '$invalid'") {
                invalid.base64UrlToBytes()
            }
        }
    }
}
