package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class AndroidAdaptiveDrmInitializationDataTest {
    @Test
    fun resolves_widevine_pssh_from_child_hls_media_playlist() {
        val master =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4000000
            video/main.m3u8
            """.trimIndent()
        val media =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=SAMPLE-AES,KEYFORMAT="com.widevine",URI="data:text/plain;base64,AQIDBA=="
            #EXTINF:6,
            segment.m4s
            #EXT-X-ENDLIST
            """.trimIndent()

        val result =
            resolveWidevineAdaptiveInitializationData(master, "https://media.example/root/master.m3u8") { uri ->
                if (uri == "https://media.example/root/video/main.m3u8") media else null
            }

        assertContentEquals(byteArrayOf(1, 2, 3, 4), result)
    }

    @Test
    fun fails_closed_when_hls_tree_contains_no_widevine_initialization_data() {
        val media =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            segment.m4s
            #EXT-X-ENDLIST
            """.trimIndent()

        assertNull(
            resolveWidevineAdaptiveInitializationData(media, "https://media.example/main.m3u8") { null },
        )
    }
}
