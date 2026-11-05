package com.yfuse.feature.player

import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.playback.PlayerCustomHeaders
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenPlayerRoutingTest {
    private fun item(url: String) =
        PlayerMediaItem(
            id = fileSourceItemId("source", listOf("movie.mkv")),
            url = url,
            transcodeUrl = "",
            title = "Movie",
        )

    @Test
    fun resolved_cloud_http_with_origin_headers_keeps_the_selected_backend() {
        val media =
            item("http://media.example/movie.mkv").copy(
                customHeaders = PlayerCustomHeaders(mapOf("Authorization" to "Bearer example")),
            )
        assertFalse(media.requiresYCoreFileTransport())
        assertFalse(item("https://media.example/movie.mkv").requiresYCoreFileTransport())
    }

    @Test
    fun smb_and_ftp_continue_to_use_the_credential_aware_ycore_transports() {
        assertTrue(item("smb://nas/share/movie.mkv").requiresYCoreFileTransport())
        assertTrue(item("ftp://nas/movie.mkv").requiresYCoreFileTransport())
    }
}
