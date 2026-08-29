package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidYCoreHttpProxyTest {
    @Test
    fun accepts_only_one_explicit_forward_byte_range() {
        assertEquals(YCoreHttpByteRange(0L, null), parseYCoreHttpByteRange("bytes=0-"))
        assertEquals(YCoreHttpByteRange(41L, 99L), parseYCoreHttpByteRange("bytes=41-99"))
        assertNull(parseYCoreHttpByteRange("bytes=-100"))
        assertNull(parseYCoreHttpByteRange("bytes=100-99"))
        assertNull(parseYCoreHttpByteRange("bytes=0-1,4-5"))
        assertNull(parseYCoreHttpByteRange("items=0-1"))
    }

    @Test
    fun detects_authored_separate_hls_renditions_without_false_positive_on_muxed_master() {
        assertTrue(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio"
            video/main.m3u8
            """.trimIndent().hasSeparateYCoreHlsRenditions(),
        )
        assertFalse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            muxed/main.m3u8
            """.trimIndent().hasSeparateYCoreHlsRenditions(),
        )
    }
}
