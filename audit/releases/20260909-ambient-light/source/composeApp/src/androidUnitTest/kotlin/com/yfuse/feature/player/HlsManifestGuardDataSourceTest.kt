package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsManifestGuardDataSourceTest {
    @Test
    fun hls_signature_accepts_bom_and_leading_whitespace() {
        assertTrue("#EXTM3U\n#EXT-X-VERSION:3".encodeToByteArray().hasHlsManifestSignature())
        assertTrue("\uFEFF  \r\n#EXTM3U\n".encodeToByteArray().hasHlsManifestSignature())
    }

    @Test
    fun html_and_json_error_bodies_are_rejected_before_the_hls_parser() {
        assertFalse("<html><body>transcode failed</body></html>".encodeToByteArray().hasHlsManifestSignature())
        assertFalse("{\"ErrorCode\":\"TranscodeFailure\"}".encodeToByteArray().hasHlsManifestSignature())
    }

    @Test
    fun only_manifest_urls_are_guarded() {
        assertTrue("https://example.test/Videos/1/master.m3u8?token=secret".isHlsManifestUrl())
        assertTrue("https://example.test/Videos/1/MASTER.M3U8#fragment".isHlsManifestUrl())
        assertFalse("https://example.test/Videos/1/stream.mp4".isHlsManifestUrl())
        assertFalse("https://example.test/segments/00001.ts".isHlsManifestUrl())
    }
}
