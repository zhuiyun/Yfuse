package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertTrue

class FallbackTranscodeContractTest {
    @Test
    fun hls_fallback_forces_a_real_compatibility_encode() {
        val url = EmbyStream.transcode("https://emby.example", "movie", "token")

        listOf(
            "TranscodingProtocol=hls",
            "Container=ts",
            "TranscodingContainer=ts",
            "SegmentContainer=ts",
            "VideoCodec=h264",
            "AudioCodec=aac",
            "EnableAutoStreamCopy=false",
            "AllowVideoStreamCopy=false",
            "AllowAudioStreamCopy=false",
            "RequireAvc=true",
        ).forEach { parameter -> assertTrue(parameter in url, url) }
    }

    @Test
    fun progressive_fallback_cannot_stream_copy_dolby_vision() {
        val url = EmbyStream.progressiveTranscode("https://emby.example", "movie", "token")

        listOf(
            "Container=mp4",
            "TranscodingContainer=mp4",
            "VideoCodec=h264",
            "AudioCodec=aac",
            "EnableAutoStreamCopy=false",
            "AllowVideoStreamCopy=false",
            "AllowAudioStreamCopy=false",
            "RequireAvc=true",
        ).forEach { parameter -> assertTrue(parameter in url, url) }
    }
}
