package com.yfuse.feature.player

import androidx.media3.common.C
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExoDrmConfigurationTest {
    @Test
    fun original_stream_receives_secure_media3_configuration() {
        val item = secureItem()
        val configuration = item.drmConfiguration!!
        val drm = configuration.copy(licenseUri = null).toMedia3Configuration()

        assertEquals(C.WIDEVINE_UUID, drm.scheme)
        assertEquals("Bearer credential", drm.licenseRequestHeaders["Authorization"])
        assertEquals(configuration, playbackDrmConfiguration(item, item.url))
    }

    @Test
    fun server_transcode_does_not_reuse_original_stream_keys() {
        val item = secureItem()

        val drm = playbackDrmConfiguration(item, item.transcodeUrl)

        assertNull(drm)
    }

    private fun secureItem() =
        PlayerMediaItem(
            id = "movie",
            url = "https://media.example.test/manifest.mpd",
            transcodeUrl = "https://media.example.test/transcode.m3u8",
            title = "Secure movie",
            drmConfiguration =
                PlaybackDrmConfiguration(
                    scheme = PlaybackDrmScheme.Widevine,
                    licenseUri = "https://license.example.test/widevine",
                    requestHeaders = mapOf("Authorization" to "Bearer credential"),
                ),
        )
}
