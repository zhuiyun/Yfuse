package com.yfuse.feature.player

import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlayerCustomHeaders
import com.yfuse.core2.network.YTransportCredentials
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerEngineFactoryPolicyTest {
    private fun item(url: String) =
        PlayerMediaItem(
            id = fileSourceItemId("source", listOf("movie.mkv")),
            url = url,
            transcodeUrl = "",
            title = "Movie",
        )

    @Test
    fun direct_http_and_https_preserve_explicit_sen_and_origin_headers() {
        listOf("http", "https").forEach { scheme ->
            val media =
                item("$scheme://media.example/movie.mkv").copy(
                    customHeaders = PlayerCustomHeaders(mapOf("Authorization" to "Bearer synthetic-fixture")),
                )
            assertNull(playbackEngineUnsupportedReason(PlayerEngine.SenPlayer, listOf(media)))
            assertNull(playbackEngineUnsupportedReason(PlayerEngine.YCore, listOf(media)))
        }
    }

    @Test
    fun credential_bound_and_non_http_file_transports_require_explicit_ycore_selection() {
        val credentials = YTransportCredentials.UsernamePassword("fixture", "fixture")
        val media =
            listOf(
                item("https://media.example/movie.mkv").copy(transportCredentials = credentials),
                item("ftp://nas/movie.mkv"),
                item("smb://nas/share/movie.mkv"),
                item("yfuse-cloud://source/revision/movie.mkv"),
            )
        media.forEach {
            assertNotNull(playbackEngineUnsupportedReason(PlayerEngine.SenPlayer, listOf(it)))
            assertNull(playbackEngineUnsupportedReason(PlayerEngine.YCore, listOf(it)))
        }
    }

    @Test
    fun later_queue_entry_cannot_lose_its_required_credential_transport() {
        assertNotNull(
            playbackEngineUnsupportedReason(
                PlayerEngine.SenPlayer,
                listOf(item("https://media.example/movie.mkv"), item("smb://nas/share/next.mkv")),
            ),
        )
    }
}
