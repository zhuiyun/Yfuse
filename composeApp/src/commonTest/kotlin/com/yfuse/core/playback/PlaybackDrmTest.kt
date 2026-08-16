package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDrmTest {
    @Test
    fun diagnostic_string_redacts_license_credentials() {
        val configuration =
            PlaybackDrmConfiguration(
                scheme = PlaybackDrmScheme.Widevine,
                licenseUri = "https://license.example.test/wv?token=secret",
                requestHeaders = mapOf("Authorization" to "Bearer secret"),
            )

        val text = configuration.toString()

        assertTrue("<redacted>" in text)
        assertFalse("license.example.test" in text)
        assertFalse("Bearer secret" in text)
    }

    @Test
    fun offline_key_equality_uses_key_contents() {
        val first =
            PlaybackDrmConfiguration(
                scheme = PlaybackDrmScheme.Widevine,
                offlineKeySetId = byteArrayOf(1, 2, 3),
            )
        val second = first.copy(offlineKeySetId = byteArrayOf(1, 2, 3))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
