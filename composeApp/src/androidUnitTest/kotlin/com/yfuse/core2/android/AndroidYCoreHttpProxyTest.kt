package com.yfuse.core2.android

import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidYCoreHttpProxyTest {
    @Test
    fun adaptive_children_keep_source_headers_and_credentials() {
        val credentials = YTransportCredentials.UsernamePassword("viewer", "secret")
        val inherited =
            yCoreProxyUpstreamRequestContext(
                upstreamHeaders = mapOf("X-Media-Key" to "opaque"),
                configuredUserAgent = "Yfuse/1",
                credentials = credentials,
            )
        assertEquals("opaque", inherited.headers["X-Media-Key"])
        assertEquals("Yfuse/1", inherited.headers["User-Agent"])
        assertSame(credentials, inherited.credentials)

        val explicit =
            yCoreProxyUpstreamRequestContext(
                upstreamHeaders = mapOf("user-agent" to "Origin/2"),
                configuredUserAgent = "Yfuse/1",
                credentials = credentials,
            )
        assertEquals(mapOf("user-agent" to "Origin/2"), explicit.headers)
    }

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

    @Test
    fun adaptive_cache_key_survives_token_rotation_but_keeps_semantic_coordinates() {
        assertEquals(
            stableAdaptiveResourceKey(
                "https://cdn.example/media/segment.m4s?seq=42&token=old&X-Amz-Signature=first",
            ),
            stableAdaptiveResourceKey(
                "https://cdn.example/media/segment.m4s?X-Amz-Signature=second&token=new&seq=42",
            ),
        )
        assertTrue(
            stableAdaptiveResourceKey("https://cdn.example/media/segment.m4s?seq=42") !=
                stableAdaptiveResourceKey("https://cdn.example/media/segment.m4s?seq=43"),
        )
    }
}
