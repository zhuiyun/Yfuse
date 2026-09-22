package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRemoteBluRayPreflightTest {
    @Test
    fun raw_disc_url_contains_session_identity_but_no_server_token() {
        val url =
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "https://media.example:8096/emby/",
                itemId = "movie id/42",
                mediaSourceId = "source-A",
                playSessionId = "session-123",
                deviceIdentifier = "device-xyz",
            ).orEmpty()

        assertTrue(url.startsWith("https://media.example:8096/emby/Videos/movie%20id%2F42/stream?"))
        assertTrue("static=true" in url)
        assertTrue("MediaSourceId=source-A" in url)
        assertTrue("PlaySessionId=session-123" in url)
        assertTrue("DeviceId=device-xyz" in url)
        assertFalse("api_key=" in url)
        assertFalse("X-Emby-Token" in url)
        assertFalse(url.contains("token", ignoreCase = true))
    }

    @Test
    fun blank_session_is_omitted_and_invalid_base_url_is_rejected() {
        val url =
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "https://media.example",
                itemId = "movie",
                mediaSourceId = "source with space",
                playSessionId = "",
                deviceIdentifier = "device",
            ).orEmpty()

        assertFalse("PlaySessionId=" in url)
        assertTrue("MediaSourceId=source%20with%20space" in url)
        assertNull(
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "   ",
                itemId = "movie",
                mediaSourceId = "source",
                playSessionId = "session",
                deviceIdentifier = "device",
            ),
        )
        assertNull(
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "ftp://media.example",
                itemId = "movie",
                mediaSourceId = "source",
                playSessionId = "session",
                deviceIdentifier = "device",
            ),
        )
    }
}
