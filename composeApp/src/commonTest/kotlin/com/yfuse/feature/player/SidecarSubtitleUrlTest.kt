package com.yfuse.feature.player

import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.MediaStreamDto
import com.yfuse.core.data.dto.toMediaVersion
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Emby and Jellyfin send a sidecar's `DeliveryUrl` as a path on the server. Passed on as it was,
 * YCore, MPV and Exo were each handed an address they could not open, so the subtitle never loaded.
 */
class SidecarSubtitleUrlTest {
    @Test
    fun emby_relative_sidecar_is_completed_against_the_saved_server() {
        val subtitle =
            sidecars(
                "https://host/emby",
                subtitleStream(3, "srt", "/videos/movie/src/Subtitles/3/0/Stream.srt?api_key=secret"),
            ).single()

        val url = Url(subtitle.uri)
        assertEquals("https", url.protocol.name)
        assertEquals("host", url.host)
        assertEquals("/emby/videos/movie/src/Subtitles/3/0/Stream.srt", url.encodedPath)
        assertEquals("secret", url.parameters["api_key"])
        assertEquals("srt", subtitle.codec)
    }

    @Test
    fun jellyfin_sidecar_without_a_token_carries_the_server_credential() {
        val url =
            Url(
                sidecars(
                    "http://server/jellyfin",
                    subtitleStream(2, "webvtt", "/Videos/movie/src/Subtitles/2/0/Stream.vtt"),
                ).single().uri,
            )

        assertEquals("server", url.host)
        assertEquals("/jellyfin/Videos/movie/src/Subtitles/2/0/Stream.vtt", url.encodedPath)
        assertEquals("secret", url.parameters["api_key"])
        assertEquals("secret", url.parameters["ApiKey"])
    }

    @Test
    fun a_converted_sidecar_is_parsed_as_the_format_the_server_sends() {
        // This device profile takes ASS only embedded, so Emby converts an ASS sidecar to SRT.
        val subtitles =
            sidecars(
                "https://host",
                subtitleStream(4, "ass", "/videos/movie/src/Subtitles/4/0/Stream.srt?api_key=secret"),
                subtitleStream(5, "ass", "https://cdn.example/subs/movie.ass", sidecarPath = true),
            )

        assertEquals(listOf("srt", "ass"), subtitles.map(PlayerExternalSubtitle::codec))
    }

    @Test
    fun a_sidecar_path_on_the_server_disk_is_requested_from_the_subtitle_endpoint() {
        val subtitles =
            sidecars(
                "https://host/emby",
                subtitleStream(5, "srt", "/mnt/strm/电影/Movie.chs.srt", sidecarPath = true),
                subtitleStream(6, "ass", """D:\Media\Movie.chs.ass""", sidecarPath = true),
            )

        assertEquals(
            listOf("/emby/Videos/movie/src/Subtitles/5/Stream.srt", "/emby/Videos/movie/src/Subtitles/6/Stream.ass"),
            subtitles.map { Url(it.uri).encodedPath },
        )
        assertTrue(subtitles.all { Url(it.uri).parameters["api_key"] == "secret" })
        assertEquals(listOf("srt", "ass"), subtitles.map(PlayerExternalSubtitle::codec))
    }

    @Test
    fun a_sidecar_on_another_host_keeps_its_address_and_gets_no_server_credential() {
        val cdn = "https://cdn.example/subs/movie.srt?sig=abc"

        val subtitles = sidecars("https://host", subtitleStream(7, "srt", cdn, sidecarPath = true))

        assertEquals(listOf(cdn), subtitles.map(PlayerExternalSubtitle::uri))
    }

    @Test
    fun embedded_streams_stay_out_of_the_sidecar_list() {
        val subtitles =
            sidecars(
                "https://host",
                subtitleStream(
                    index = 8,
                    codec = "subrip",
                    deliveryUrl = "/videos/movie/src/Subtitles/8/0/Stream.subrip?api_key=secret",
                    external = false,
                ),
            )

        assertTrue(subtitles.isEmpty())
    }

    private fun subtitleStream(
        index: Int,
        codec: String,
        deliveryUrl: String,
        external: Boolean = true,
        sidecarPath: Boolean = false,
    ): MediaStreamDto =
        MediaStreamDto(
            Index = index,
            Type = "Subtitle",
            Codec = codec,
            IsExternal = external,
            DeliveryUrl = deliveryUrl,
            IsExternalUrl = sidecarPath,
        )

    private fun sidecars(
        baseUrl: String,
        vararg subtitles: MediaStreamDto,
    ): List<PlayerExternalSubtitle> =
        listOf(
            MediaSourceDto(
                Id = "src",
                Container = "mkv",
                MediaStreams = listOf(MediaStreamDto(Index = 0, Type = "Video", Codec = "h264")) + subtitles,
                SupportsDirectPlay = true,
            ).toMediaVersion(fallbackId = "movie", ordinal = 0),
        ).toPlayerMediaVersions(baseUrl = baseUrl, itemId = "movie", token = "secret", userId = "user")
            .single()
            .externalSubtitles
}
