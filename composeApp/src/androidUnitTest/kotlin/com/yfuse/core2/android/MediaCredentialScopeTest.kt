package com.yfuse.core2.android

import com.yfuse.core.network.embyPlaybackHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCredentialScopeTest {
    @Test
    fun embyIdentityIsScopedToSchemeHostAndPort() {
        val origin = "https://server.example/Videos/1/stream?api_key=private-token&UserId=private-user"
        val headers = embyPlaybackHeaders(origin) { "1" } + ("User-Agent" to "player")
        listOf(
            "https://cdn.example/video",
            "https://server.example:8443/video",
            "http://server.example/video",
        ).forEach { target ->
            assertEquals(mapOf("User-Agent" to "player"), scopedMediaHeaders(headers, origin, target))
        }
        assertEquals(headers, scopedMediaHeaders(headers, origin, "https://SERVER.example:443/video"))
        assertTrue(headers.keys.filter { it != "User-Agent" }.all { it.isCredentialHeader() })
    }

    @Test
    fun aRemotePlaylistCannotSendTheProxyToThisDeviceOrTheViewersShares() {
        val remote = "https://cdn.example/live/index.m3u8"
        assertTrue(adaptiveChildStaysInSchemeFamily(remote, "http://other.example/seg-1.ts"))
        assertFalse(adaptiveChildStaysInSchemeFamily(remote, "content://com.android.providers.media/video/1"))
        assertFalse(adaptiveChildStaysInSchemeFamily(remote, "smb://nas.local/private/movie.mkv"))
        assertFalse(adaptiveChildStaysInSchemeFamily(remote, "file:///data/data/com.yfuse/files/token"))
    }

    @Test
    fun anSmbOrDevicePlaylistStaysOnItsOwnServerOrProvider() {
        val share = "smb://user@nas.local/Movies/Film/index.m3u8"
        assertTrue(adaptiveChildStaysInSchemeFamily(share, "smb://nas.local/Movies/Film/seg 1.ts"))
        assertFalse(adaptiveChildStaysInSchemeFamily(share, "smb://other.local/Movies/seg.ts"))
        assertFalse(adaptiveChildStaysInSchemeFamily(share, "https://cdn.example/seg.ts"))
        val document = "content://com.example.documents/tree/1/index.m3u8"
        assertTrue(adaptiveChildStaysInSchemeFamily(document, "content://com.example.documents/tree/1/seg.ts"))
        assertFalse(adaptiveChildStaysInSchemeFamily(document, "content://com.android.contacts/data"))
    }
}
