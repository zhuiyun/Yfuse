package com.yfuse.feature.player

import android.net.Uri
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
        assertTrue(Uri.parse("https://example.test/Videos/1/master.m3u8?token=secret").isHlsManifestUri())
        assertFalse(Uri.parse("https://example.test/Videos/1/stream.mp4").isHlsManifestUri())
        assertFalse(Uri.parse("https://example.test/segments/00001.ts").isHlsManifestUri())
    }
}
