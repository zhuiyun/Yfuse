package com.yfuse.core.data

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlexMediaServerAdapterTest {
    private val server =
        SavedServer(
            id = "plex",
            baseUrl = "http://plex:32400",
            serverName = "客厅 Plex",
            userId = "yun",
            userName = "yun",
            accessToken = "secret-token",
            kind = MediaServerKind.Plex,
        )

    @Test
    fun authenticated_media_url_rejects_cross_origin_paths() {
        assertFailsWith<IllegalArgumentException> {
            plexAuthenticatedUrl("http://plex:32400", "https://attacker.test/file.mkv", "secret")
        }
    }

    @Test
    fun token_authentication_verifies_identity_without_putting_token_in_api_url() =
        runTest {
            val requests = mutableListOf<String>()
            val repo =
                testRepo { request ->
                    requests += request.url.toString()
                    assertEquals("secret-token", request.headers["X-Plex-Token"])
                    when (request.url.encodedPath) {
                        "/identity" ->
                            json(
                                """{"MediaContainer":{"machineIdentifier":"machine-1","version":"1.42"}}""",
                            )
                        "/" ->
                            json(
                                """{"MediaContainer":{"friendlyName":"客厅 Plex","myPlexUsername":"yun"}}""",
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            val result =
                repo.authenticate(
                    "http://plex:32400",
                    username = "",
                    password = "secret-token",
                    kind = MediaServerKind.Plex,
                )

            assertTrue(result.isSuccess, result.toString())
            assertEquals(MediaServerKind.Plex, result.getOrThrow().kind)
            assertEquals("yun", result.getOrThrow().userId)
            assertTrue(requests.none { "secret-token" in it })
        }

    @Test
    fun libraries_detail_and_playback_translate_plex_metadata_into_shared_models() =
        runTest {
            val repo =
                testRepo { request ->
                    assertEquals("secret-token", request.headers["X-Plex-Token"])
                    when (request.url.encodedPath) {
                        "/library/sections" ->
                            json(
                                """{"MediaContainer":{"Directory":[""" +
                                    """{"key":"1","title":"电影","type":"movie"},""" +
                                    """{"key":"2","title":"剧集","type":"show"}]}}""",
                            )
                        "/library/metadata/100" -> json(movieMetadata())
                        else -> error("unexpected ${request.url}")
                    }
                }

            val libraries = repo.libraries(server).getOrThrow()
            val detail = repo.itemDetail(server, "100").getOrThrow()
            val playback =
                repo.playbackInfo(server, "100", playSessionId = "session-1").getOrThrow()
            val source = playback.MediaSources.single()

            assertEquals(listOf("电影", "剧集"), libraries.map { it.name })
            assertEquals("沙丘", detail.title)
            assertEquals("100", detail.id)
            assertEquals("155", detail.providerIds["Tmdb"])
            assertEquals(3, detail.versions.single().subtitleTracks.single().index)
            assertEquals(false, source.SupportsDirectPlay)
            assertEquals(true, source.SupportsDirectStream)
            assertTrue(source.DirectStreamUrl.orEmpty().contains("/library/parts/12/file.mkv"))
            assertTrue(source.DirectStreamUrl.orEmpty().contains("X-Plex-Token=secret-token"))
            assertFalse(source.DirectStreamUrl.orEmpty().contains("api_key="))
            assertNotNull(source.TranscodingUrl)
        }

    @Test
    fun progress_reporting_uses_plex_timeline_and_millisecond_position() =
        runTest {
            var timelineSeen = false
            val repo =
                testRepo { request ->
                    if (request.url.encodedPath == "/:/timeline") {
                        timelineSeen = true
                        assertEquals("secret-token", request.headers["X-Plex-Token"])
                        assertEquals("session-9", request.headers["X-Plex-Session-Identifier"])
                        assertEquals("paused", request.url.parameters["state"])
                        assertEquals("42000", request.url.parameters["time"])
                        assertFalse(request.url.toString().contains("secret-token"))
                        json("""{"MediaContainer":{"size":0}}""")
                    } else {
                        error("unexpected ${request.url}")
                    }
                }

            val result =
                repo.reportPlaybackProgress(
                    server = server,
                    itemId = "100",
                    playSessionId = "session-9",
                    positionTicks = 420_000_000L,
                    isPaused = true,
                )

            assertTrue(result.isSuccess, result.toString())
            assertTrue(timelineSeen)
        }

    @Test
    fun user_snapshot_reads_plex_resume_position_for_local_progress_seeding() =
        runTest {
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/library/sections" ->
                            json(
                                """{"MediaContainer":{"Directory":[""" +
                                    """{"key":"1","title":"电影","type":"movie"}]}}""",
                            )
                        "/library/sections/1/all" -> json(movieMetadata())
                        else -> error("unexpected ${request.url}")
                    }
                }

            val snapshot = repo.userLibrarySnapshot(server).getOrThrow().single()

            assertEquals("100", snapshot.id)
            assertEquals(12_000_000L, snapshot.positionTicks)
            assertFalse(snapshot.played)
        }

    private fun movieMetadata(): String =
        """{"MediaContainer":{"size":1,"Metadata":[{""" +
            """"ratingKey":"100","key":"/library/metadata/100","type":"movie","title":"沙丘",""" +
            """"summary":"香料与命运","year":2021,"duration":9300000,"viewOffset":1200,""" +
            """"thumb":"/library/metadata/100/thumb/1","art":"/library/metadata/100/art/1",""" +
            """"Guid":[{"id":"tmdb://155"},{"id":"imdb://tt1160419"}],""" +
            """"Media":[{"id":7,"duration":9300000,"bitrate":68000,"width":3840,"height":2160,""" +
            """"videoCodec":"hevc","audioCodec":"eac3","container":"mkv","Part":[{"id":12,""" +
            """"key":"/library/parts/12/file.mkv","file":"/media/Dune.mkv","size":73400320000,""" +
            """"Stream":[{"index":0,"streamType":1,"codec":"hevc","width":3840,"height":2160,""" +
            """"DOVIPresent":true,"DOVIProfile":8},{"index":1,"streamType":2,"codec":"eac3",""" +
            """"channels":8,"languageCode":"eng","extendedDisplayTitle":"EAC3 7.1 Atmos"},{""" +
            """"index":3,"streamType":3,"codec":"srt","languageCode":"zho","key":"/library/streams/3"}]}]}]}]}}"""
}
