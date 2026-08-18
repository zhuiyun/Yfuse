package com.yfuse.feature.player

import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
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
            )
        val parsed = Uri.parse(url)

        assertTrue(url.orEmpty().startsWith("https://media.example:8096/emby/Videos/"))
        assertEquals("true", parsed.getQueryParameter("static"))
        assertEquals("source-A", parsed.getQueryParameter("MediaSourceId"))
        assertEquals("session-123", parsed.getQueryParameter("PlaySessionId"))
        assertEquals("device-xyz", parsed.getQueryParameter("DeviceId"))
        assertNull(parsed.getQueryParameter("api_key"))
        assertNull(parsed.getQueryParameter("X-Emby-Token"))
        assertFalse(url.orEmpty().contains("token", ignoreCase = true))
    }

    @Test
    fun blank_session_is_omitted_and_blank_base_url_is_rejected() {
        val url =
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "https://media.example",
                itemId = "movie",
                mediaSourceId = "source",
                playSessionId = "",
                deviceIdentifier = "device",
            )

        assertNull(Uri.parse(url).getQueryParameter("PlaySessionId"))
        assertNull(
            nativeRemoteBluRayRawDiscUrl(
                baseUrl = "   ",
                itemId = "movie",
                mediaSourceId = "source",
                playSessionId = "session",
                deviceIdentifier = "device",
            ),
        )
    }
}
