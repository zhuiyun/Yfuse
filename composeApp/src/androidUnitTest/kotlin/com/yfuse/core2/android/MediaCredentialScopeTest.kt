package com.yfuse.core2.android

import com.yfuse.core.network.embyPlaybackHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
