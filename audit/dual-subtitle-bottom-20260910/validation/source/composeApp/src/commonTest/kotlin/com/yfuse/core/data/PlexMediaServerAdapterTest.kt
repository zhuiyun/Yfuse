package com.yfuse.core.data

import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.http.HttpMethod
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
            assertEquals(
                3,
                detail.versions
                    .single()
                    .subtitleTracks
                    .single()
                    .index,
            )
            assertEquals(
                "http://plex:32400/library/streams/3?X-Plex-Token=secret-token",
                detail.versions
                    .single()
                    .subtitleTracks
                    .single()
                    .uri,
            )
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

    @Test
    fun collections_playlists_and_container_items_use_local_plex_routes() =
        runTest {
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/library/sections" ->
                            json(
                                """{"MediaContainer":{"Directory":[""" +
                                    """{"key":"1","title":"电影","type":"movie"},""" +
                                    """{"key":"2","title":"剧集","type":"show"}]}}""",
                            )
                        "/library/sections/1/all" -> {
                            assertEquals("18", request.url.parameters["type"])
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"500","type":"collection",""" +
                                    """"title":"科幻合集","thumb":"/library/metadata/500/thumb/1",""" +
                                    """"childCount":"4"}]}}""",
                            )
                        }
                        "/library/sections/2/all" -> json("""{"MediaContainer":{"Metadata":[]}}""")
                        "/playlists" ->
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"600","type":"playlist",""" +
                                    """"title":"周末播放","thumb":"/playlists/600/thumb/1","leafCount":8}]}}""",
                            )
                        "/library/collections/500/children" -> json(movieMetadata())
                        else -> error("unexpected ${request.url}")
                    }
                }

            val containers = repo.mediaContainers(server).getOrThrow()
            val collection = containers.single { it.kind == MediaContainerKind.BoxSet }
            val playlist = containers.single { it.kind == MediaContainerKind.Playlist }
            val items =
                repo
                    .mediaContainerItems(
                        server = server,
                        containerId = collection.id,
                        kind = collection.kind,
                    ).getOrThrow()

            assertEquals("科幻合集", collection.title)
            assertEquals(4, collection.itemCount)
            assertEquals("周末播放", playlist.title)
            assertEquals(8, playlist.itemCount)
            assertEquals("沙丘", items.items.single().title)
        }

    @Test
    fun playlist_contents_preserve_plex_row_identity_for_exact_removal() =
        runTest {
            val repo =
                testRepo { request ->
                    assertEquals("/playlists/600/items", request.url.encodedPath)
                    json(
                        """{"MediaContainer":{"Metadata":[{"ratingKey":"100","playlistItemID":9001,""" +
                            """"type":"movie","title":"沙丘"}]}}""",
                    )
                }

            val items =
                repo
                    .mediaContainerItems(
                        server = server,
                        containerId = "600",
                        kind = MediaContainerKind.Playlist,
                    ).getOrThrow()

            assertEquals("9001", items.items.single().playlistItemId)
        }

    @Test
    fun local_collection_and_playlist_writes_follow_official_plex_contract() =
        runTest {
            data class Seen(
                val method: HttpMethod,
                val path: String,
                val uri: String?,
            )

            val requests = mutableListOf<Seen>()
            val repo =
                testRepo { request ->
                    assertEquals("secret-token", request.headers["X-Plex-Token"])
                    requests +=
                        Seen(
                            request.method,
                            request.url.encodedPath,
                            request.url.parameters["uri"],
                        )
                    when (request.url.encodedPath) {
                        "/identity" ->
                            json("""{"MediaContainer":{"machineIdentifier":"machine-1"}}""")
                        "/library/collections/500" ->
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"500","smart":false}]}}""",
                            )
                        "/playlists/600" ->
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"600","smart":0}]}}""",
                            )
                        else -> json("""{"MediaContainer":{"size":0}}""")
                    }
                }

            repo
                .addItemToMediaContainer(server, "500", MediaContainerKind.BoxSet, "100")
                .getOrThrow()
            repo
                .addItemToMediaContainer(server, "600", MediaContainerKind.Playlist, "101")
                .getOrThrow()
            repo
                .removeItemFromMediaContainer(server, "500", MediaContainerKind.BoxSet, "100")
                .getOrThrow()
            repo
                .removeItemFromMediaContainer(
                    server,
                    "600",
                    MediaContainerKind.Playlist,
                    itemId = "101",
                    playlistItemId = "9001",
                ).getOrThrow()

            assertEquals(
                listOf(
                    Seen(HttpMethod.Get, "/library/collections/500", null),
                    Seen(HttpMethod.Get, "/identity", null),
                    Seen(
                        HttpMethod.Put,
                        "/library/collections/500/items",
                        "server://machine-1/com.plexapp.plugins.library/library/metadata/100",
                    ),
                    Seen(HttpMethod.Get, "/playlists/600", null),
                    Seen(HttpMethod.Get, "/identity", null),
                    Seen(
                        HttpMethod.Put,
                        "/playlists/600/items",
                        "server://machine-1/com.plexapp.plugins.library/library/metadata/101",
                    ),
                    Seen(HttpMethod.Get, "/library/collections/500", null),
                    Seen(HttpMethod.Put, "/library/collections/500/items/100", null),
                    Seen(HttpMethod.Get, "/playlists/600", null),
                    Seen(HttpMethod.Delete, "/playlists/600/items/9001", null),
                ),
                requests,
            )
        }

    @Test
    fun smart_plex_playlist_is_read_only_and_never_receives_a_uri_write() =
        runTest {
            val requests = mutableListOf<Pair<HttpMethod, String>>()
            val repo =
                testRepo { request ->
                    requests += request.method to request.url.encodedPath
                    json(
                        """{"MediaContainer":{"Metadata":[{"ratingKey":"600","smart":true}]}}""",
                    )
                }

            val result =
                repo.addItemToMediaContainer(
                    server,
                    "600",
                    MediaContainerKind.Playlist,
                    "101",
                )

            assertTrue(result.isFailure)
            assertEquals(listOf(HttpMethod.Get to "/playlists/600"), requests)
        }

    @Test
    fun people_search_and_person_items_map_plex_hubs_and_actor_filter() =
        runTest {
            val repo =
                testRepo { request ->
                    when (request.url.encodedPath) {
                        "/hubs/search" ->
                            json("""{"MediaContainer":{"Hub":[]}}""")
                        "/library/sections" ->
                            json(
                                """{"MediaContainer":{"Directory":[""" +
                                    """{"key":"1","title":"电影","type":"movie"}]}}""",
                            )
                        "/library/sections/1/actor" ->
                            json(
                                """{"MediaContainer":{"Directory":[{"key":"99","type":"person",""" +
                                    """"title":"张曼玉","thumb":"/library/people/99/thumb"}]}}""",
                            )
                        "/library/sections/1/all" -> {
                            assertEquals("99", request.url.parameters["actor"])
                            json(movieMetadata())
                        }
                        else -> error("unexpected ${request.url}")
                    }
                }

            val person = repo.searchPeople(server, "张曼玉").single()
            val credits = repo.itemsByPerson(server, person.id).getOrThrow()

            assertEquals("99", person.id)
            assertEquals("张曼玉", person.name)
            assertEquals("plex:/library/people/99/thumb", person.primaryImageTag)
            assertEquals(listOf("沙丘"), credits.map { it.title })
        }

    @Test
    fun bif_index_metadata_exposes_authenticated_timestamp_storyboard() =
        runTest {
            val repo =
                testRepo { request ->
                    assertEquals("/library/metadata/100", request.url.encodedPath)
                    json(
                        """{"MediaContainer":{"Metadata":[{"ratingKey":"100","type":"movie","title":"沙丘",""" +
                            """"duration":25000,"Media":[{"id":7,"duration":25000,"Part":[{"id":12,""" +
                            """"indexes":"sd","duration":25000,"key":"/library/parts/12/file.mkv"}]}]}]}}""",
                    )
                }

            val info = repo.trickplayInfo(server, "100").getOrThrow()

            assertNotNull(info)
            assertEquals(3, info.thumbnailCount)
            assertEquals(1, info.tileColumns)
            assertEquals(10_000L, info.urlIndexMultiplier)
            assertTrue(info.urlPattern.orEmpty().contains("/library/parts/12/indexes/sd/{index}"))
            assertTrue(info.urlPattern.orEmpty().contains("X-Plex-Token=secret-token"))
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
