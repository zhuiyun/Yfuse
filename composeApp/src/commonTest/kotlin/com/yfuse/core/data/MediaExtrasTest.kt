package com.yfuse.core.data

import com.yfuse.core.data.dto.EmbyMediaUrlDto
import com.yfuse.core.data.dto.PlexExtraDto
import com.yfuse.core.data.dto.PlexExtraMediaDto
import com.yfuse.core.data.dto.PlexExtraPartDto
import com.yfuse.core.data.dto.PlexThemeOwnerDto
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaExtrasTest {
    private val emby = SavedServer("emby", "http://host:8096", "客厅", "u1", "viewer", "token")
    private val plex =
        SavedServer(
            id = "plex",
            baseUrl = "http://plex:32400",
            serverName = "书房 Plex",
            userId = "yun",
            userName = "yun",
            accessToken = "secret-token",
            kind = MediaServerKind.Plex,
        )

    @Test
    fun emby_trailers_list_the_server_files_first_and_then_only_the_links_a_system_app_can_open() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repo =
                testRepo { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/Users/u1/Items/m1/LocalTrailers" ->
                            json(
                                """[{"Id":"t1","Name":"trailer","RunTimeTicks":1500000000},""" +
                                    """{"Id":"t2","Name":"正式预告","RunTimeTicks":0}]""",
                            )
                        "/Users/u1/Items/m1" ->
                            json(
                                """{"Id":"m1","RemoteTrailers":[""" +
                                    """{"Url":"https://www.youtube.com/watch?v=abc","Name":"Official Trailer"},""" +
                                    """{"Url":"HTTPS://WWW.YOUTUBE.COM/WATCH?V=ABC","Name":"Again"},""" +
                                    """{"Url":"plugin://plugin.video.youtube/?videoid=abc","Name":"Kodi"},""" +
                                    """{"Url":"https://www.bilibili.com/video/BV1","Name":null}]}""",
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            val trailers = repo.trailers(emby, "m1")

            val local = trailers.filterIsInstance<MediaTrailer.Local>()
            assertEquals(listOf("t1", "t2"), local.map { it.itemId })
            assertEquals(listOf("预告片 1", "正式预告"), local.map { it.title })
            assertEquals(150_000L, local.first().durationMs)
            assertNull(local.last().durationMs)
            assertTrue(
                local.first().streamUrl.startsWith("http://host:8096/Videos/t1/stream?static=true&api_key=token"),
            )
            assertTrue("UserId=u1" in local.first().streamUrl)
            // The token rides in the address, never in what a log would print.
            assertFalse("token" in local.first().toString())

            val remote = trailers.filterIsInstance<MediaTrailer.Remote>()
            assertEquals(listOf("YouTube", "哔哩哔哩"), remote.map { it.site })
            assertEquals(listOf("Official Trailer", "预告片 2"), remote.map { it.title })
            assertTrue(trailers.indexOf(local.last()) < trailers.indexOf(remote.first()))

            val fields = requests.single { it.url.encodedPath == "/Users/u1/Items/m1" }
            assertEquals("RemoteTrailers", fields.url.parameters["Fields"])
            assertTrue(requests.all { it.headers["X-Emby-Token"] == "token" })
        }

    @Test
    fun a_half_the_server_cannot_answer_only_hides_that_half() =
        runTest {
            val repo =
                testRepo { request ->
                    if (request.url.encodedPath.endsWith("/LocalTrailers")) {
                        respond(content = "", status = HttpStatusCode.NotFound)
                    } else {
                        json("""{"Id":"m1","RemoteTrailers":[{"Url":"https://youtu.be/xyz","Name":""}]}""")
                    }
                }

            val trailers = repo.trailers(emby, "m1")

            assertEquals(listOf("预告片"), trailers.map { it.title })
            assertEquals("YouTube", (trailers.single() as MediaTrailer.Remote).site)
        }

    @Test
    fun theme_songs_are_inherited_from_the_show_and_streamed_as_the_file() =
        runTest {
            var seen: HttpRequestData? = null
            val repo =
                testRepo { request ->
                    seen = request
                    json("""{"Items":[{"Id":"s1","Name":"theme"}],"TotalRecordCount":1,"OwnerId":42}""")
                }

            val songs = repo.themeSongs(emby, "e1")

            val request = requireNotNull(seen)
            assertEquals("/Items/e1/ThemeSongs", request.url.encodedPath)
            assertEquals("u1", request.url.parameters["UserId"])
            assertEquals("true", request.url.parameters["InheritFromParent"])
            assertEquals("s1", songs.single().itemId)
            assertTrue(
                songs.single().streamUrl.startsWith("http://host:8096/Audio/s1/stream?static=true&api_key=token"),
            )
        }

    @Test
    fun a_theme_request_that_fails_is_an_empty_list_not_an_error() =
        runTest {
            val repo = testRepo { respond(content = "", status = HttpStatusCode.InternalServerError) }

            assertTrue(repo.themeSongs(emby, "m1").isEmpty())
            assertNull(repo.person(emby, "p1"))
        }

    @Test
    fun a_person_record_reads_its_birthday_as_the_day_that_was_meant() =
        runTest {
            var seen: HttpRequestData? = null
            val repo =
                testRepo { request ->
                    seen = request
                    json(
                        """{"Id":"p1","Name":"梁朝伟","Type":"Person","Overview":"  演员。 ",""" +
                            """"PremiereDate":"1962-06-26T16:00:00.0000000Z",""" +
                            """"EndDate":"0001-01-01T00:00:00.0000000Z",""" +
                            """"ProductionLocations":["","香港"],"ProviderIds":{"tmdb":"1337","Imdb":"nm0504897"},""" +
                            """"ImageTags":{"Primary":"face"}}""",
                    )
                }

            val person = requireNotNull(repo.person(emby, "p1"))

            assertEquals("/Users/u1/Items/p1", requireNotNull(seen).url.encodedPath)
            assertEquals("Overview,ProviderIds,ProductionLocations", seen?.url?.parameters?.get("Fields"))
            assertEquals("梁朝伟", person.name)
            assertEquals("演员。", person.overview)
            assertEquals("1962-06-27", person.birthDate)
            assertNull(person.deathDate)
            assertEquals("香港", person.birthPlace)
            assertEquals("face", person.primaryImageTag)
            assertEquals(1337, person.tmdbId)
        }

    @Test
    fun server_dates_keep_the_day_when_the_server_is_west_of_greenwich() {
        assertEquals("1962-06-27", serverCalendarDay("1962-06-27T05:00:00Z"))
        assertEquals("1962-06-27", serverCalendarDay("1962-06-27"))
        assertNull(serverCalendarDay("not a date"))
        assertNull(serverCalendarDay(null))
    }

    @Test
    fun trailer_links_keep_web_addresses_only() {
        val links =
            remoteTrailerLinks(
                listOf(
                    EmbyMediaUrlDto(Url = "https://vimeo.com/1", Name = "Teaser"),
                    EmbyMediaUrlDto(Url = "ftp://example.test/trailer.mp4", Name = "Ftp"),
                    EmbyMediaUrlDto(Url = "https://example.test/a b", Name = "Spaces"),
                    EmbyMediaUrlDto(Url = null, Name = "Nothing"),
                    EmbyMediaUrlDto(Url = "https://m.example.test/watch", Name = "trailer"),
                ),
            )

        assertEquals(listOf("Vimeo", "example.test"), links.map { it.site })
        assertEquals(listOf("Teaser", "预告片 2"), links.map { it.title })
    }

    @Test
    fun plex_trailers_are_the_trailer_extras_that_point_at_a_file_on_the_server() {
        val extras =
            listOf(
                extra("11", subtype = "trailer", part = "/library/parts/1/file.mp4", duration = 90_000L),
                extra("12", subtype = "behindTheScenes", part = "/library/parts/2/file.mp4"),
                extra("13", extraType = JsonPrimitive(1), part = "/library/parts/3/file.mp4"),
                extra("14", subtype = "trailer", part = "/library/parts/4/file.mp4", indirect = JsonPrimitive("1")),
                extra("15", subtype = "trailer", part = "https://elsewhere.test/file.mp4"),
            )

        val trailers = plexTrailers(plex, extras)

        assertEquals(listOf("11", "13"), trailers.map { it.itemId })
        assertEquals("http://plex:32400/library/parts/1/file.mp4?X-Plex-Token=secret-token", trailers.first().streamUrl)
        assertEquals(90_000L, trailers.first().durationMs)
        assertEquals(listOf("预告片 1", "预告片 2"), trailers.map { it.title })
    }

    @Test
    fun a_plex_episode_plays_its_show_theme_and_nothing_off_the_server() {
        val episode = PlexThemeOwnerDto(ratingKey = "9", grandparentTheme = "/library/metadata/1/theme/1700")
        val stranger = PlexThemeOwnerDto(ratingKey = "10", theme = "https://elsewhere.test/theme.mp3")

        val song = requireNotNull(plexThemeSong(plex, "9", episode))

        assertEquals("http://plex:32400/library/metadata/1/theme/1700?X-Plex-Token=secret-token", song.streamUrl)
        assertNull(plexThemeSong(plex, "10", stranger))
    }

    @Test
    fun plex_extras_and_theme_are_read_with_the_token_in_a_header() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val repo =
                testRepo { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/library/metadata/42/extras" ->
                            json(
                                """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"77","type":"clip",""" +
                                    """"subtype":"trailer","extraType":1,"title":"Official Trailer",""" +
                                    """"duration":120000,""" +
                                    """"Media":[{"Part":[{"key":"/library/parts/77/1/file.mp4"}]}]}]}}""",
                            )
                        "/library/metadata/42" ->
                            json(
                                """{"MediaContainer":{"Metadata":[{"ratingKey":"42","theme":"/library/metadata/42/theme/5"}]}}""",
                            )
                        else -> error("unexpected ${request.url}")
                    }
                }

            val trailers = repo.trailers(plex, "42")
            val songs = repo.themeSongs(plex, "42")

            assertEquals("Official Trailer", trailers.single().title)
            assertEquals("42", songs.single().itemId)
            assertNull(repo.person(plex, "501"))
            assertTrue(requests.all { it.headers["X-Plex-Token"] == "secret-token" })
            assertTrue(requests.none { "X-Plex-Token" in it.url.toString() })
        }

    private fun extra(
        id: String,
        subtype: String? = null,
        extraType: JsonPrimitive? = null,
        part: String,
        indirect: JsonPrimitive? = null,
        duration: Long? = null,
    ) = PlexExtraDto(
        ratingKey = id,
        type = "clip",
        subtype = subtype,
        extraType = extraType,
        title = "Trailer",
        duration = duration,
        Media = listOf(PlexExtraMediaDto(indirect = indirect, Part = listOf(PlexExtraPartDto(key = part)))),
    )
}
