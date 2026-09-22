package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbyPlaybackHeadersTest {
    @Test
    fun identityComesFromThisRequestAndMatchingTokenAliasesAreAccepted() {
        val headers =
            embyPlaybackHeaders(
                "https://emby.example/Videos/movie/stream?api_key=t%2B1&ApiKey=t%2B1&UserId=user-a",
            ) { "test-version" }
        assertEquals("t+1", headers["X-Emby-Token"])
        assertEquals("test-version", headers["X-Emby-Client-Version"])
        assertTrue(headers.getValue("Authorization").contains("UserId=\"user-a\""))
        assertTrue(headers.getValue("Authorization").contains("Token=\"t+1\""))
        assertEquals(headers["Authorization"], headers["X-Emby-Authorization"])
    }

    @Test
    fun differentAccountsNeverReuseThePreviousToken() {
        val first = embyPlaybackHeaders("https://host/Videos/1/stream?api_key=a&UserId=user-a") { "1" }
        val second = embyPlaybackHeaders("https://host/Videos/1/stream?api_key=b&UserId=user-b") { "1" }
        assertEquals("a", first["X-Emby-Token"])
        assertEquals("b", second["X-Emby-Token"])
        assertTrue(second.getValue("Authorization").contains("UserId=\"user-b\""))
    }

    @Test
    fun anonymousPlexAndIncompleteIdentityDoNotAcquireServerHeaders() {
        listOf(
            "https://cdn.example/video?signature=secret",
            "https://host/Videos/1/stream?api_key=token&UserId=user&sig=provider-signature",
            "https://plex.example/library/parts/1?X-Plex-Token=secret",
            "https://host/Videos/1/stream?api_key=token",
            "https://host/Videos/1/stream?UserId=user",
            "file:///tmp/movie.mkv",
        ).forEach { url ->
            assertTrue(embyPlaybackHeaders(url) { error("No identity should be built") }.isEmpty())
        }
    }

    @Test
    fun ambiguousCredentialsAndHeaderInjectionAreRejected() {
        listOf(
            "api_key=a&ApiKey=b&UserId=u",
            "api_key=a&UserId=u&userid=other",
            "api_key=a%0D%0AInjected&UserId=u",
            "api_key=a&UserId=u%0AInjected",
            "api_key=&UserId=u",
        ).forEach { query ->
            assertTrue(embyPlaybackHeaders("https://host/Videos/1/stream?$query") { "1" }.isEmpty())
        }
    }

    @Test
    fun signedOriginalUrlIsReturnedByteForByte() {
        val url = "https://cdn.example/opaque-file?signature=a%2Bb&expires=123"
        assertEquals(url, originalNegotiatedPlaybackUrl(url))
    }

    @Test
    fun directPlayDoesNotAdoptAnAdaptiveOrCodecConvertingStream() {
        listOf(
            "master.m3u8?static=true",
            "manifest.mpd",
            "stream?static=false",
            "stream?AudioCodec=aac",
            "stream?VideoCodec=h264",
            "stream?TranscodingProtocol=hls",
        ).forEach { path -> assertNull(originalNegotiatedPlaybackUrl("https://host/$path")) }
        val copied = "https://host/stream?VideoCodec=copy&AudioCodec=copy&static=true"
        assertEquals(copied, originalNegotiatedPlaybackUrl(copied))
    }
}
