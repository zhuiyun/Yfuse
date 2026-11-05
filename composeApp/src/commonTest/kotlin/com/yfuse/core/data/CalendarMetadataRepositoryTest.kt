package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.createTheOtherDbClient
import com.yfuse.core.security.TestSecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarMetadataRepositoryTest {
    @Test
    fun todb_requires_tv_identity_and_fills_only_missing_coordinates_with_real_dates() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                createTheOtherDbClient(
                    mockEngine { request ->
                        assertNull(request.headers["Authorization"])
                        assertEquals("theotherdb.org", request.url.host)
                        paths += request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/video/list" ->
                                respond(
                                    """
{
  "items": [
    {
      "video_id": 8,
      "video_type": "tv"
    },
    {
      "video_id": 9,
      "video_type": "tv",
      "video_title": "剧集",
      "origin_countrys": [
        "CN"
      ]
    }
  ]
}
""",
                                )
                            "/api/external/list" -> {
                                assertEquals("video_list", request.url.parameters["relation_type"])
                                if (request.url.parameters["relation_id"] == "8") {
                                    respond("unavailable", HttpStatusCode.NotFound)
                                } else {
                                    respond("""[{"platform":"tmdb_id_tv","value":"42"}]""")
                                }
                            }
                            "/api/video/9/season/all" -> respond("""[{"season_number":0},{"season_number":1}]""")
                            "/api/video/9/season/1/episode/all" ->
                                respond(
                                    """[
                    {"episode_number":1,"date_air":"2026-11-06"},
                    {"episode_number":2,"date_air":"2026-11-12"},
                    {"episode_number":3,"date_air":null},
                    {"episode_number":4,"date_air":"2026-02-30"},
                    {"episode_number":0,"date_air":"2026-11-12"}]
                """,
                                )
                            else -> error("Unexpected TODB request")
                        }
                    },
                )
            try {
                val rows = repository(client).series(42, "剧集").getOrThrow()
                assertEquals(listOf(1, 2), rows.map { it.episodeNumber })
                assertEquals("2026-11-05", rows.first().airDate)
                val added = rows.last()
                assertEquals("TODB", added.scheduleProvider)
                assertEquals("2026-11-12", added.airDate)
                assertEquals(ShowOrigin.Domestic, added.origin)
                assertEquals("/poster.jpg", added.posterPath)
                assertEquals("DateOnly", added.releaseMode)
                assertNull(added.airTime)
                assertNull(added.releaseAtUtc)
                assertFalse(paths.any { "/video/8/season" in it })
            } finally {
                client.close()
            }
        }

    @Test
    fun same_title_and_same_movie_id_do_not_match_a_tv_series() =
        runTest {
            val client =
                createTheOtherDbClient(
                    mockEngine { request ->
                        when (request.url.encodedPath) {
                            "/api/video/list" ->
                                respond(
                                    """{"items":[{"video_id":8,"video_type":"tv","video_title":"剧集"}]}""",
                                )
                            "/api/external/list" ->
                                respond(
                                    """[{"platform":"tmdb_id_movie","value":"42"},{"platform":"tmdb_id_tv","value":"99"}]""",
                                )
                            else -> error("An unverified series must never be fetched")
                        }
                    },
                )
            try {
                assertEquals(listOf(primaryEpisode), repository(client).series(42, "剧集").getOrThrow())
            } finally {
                client.close()
            }
        }

    @Test
    fun provider_failure_does_not_discard_successful_primary_data() =
        runTest {
            val client =
                createTheOtherDbClient(mockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) })
            try {
                assertEquals(listOf(primaryEpisode), repository(client).series(42, "剧集").getOrThrow())
            } finally {
                client.close()
            }
        }

    @Test
    fun disabled_and_unconfigured_sources_send_no_requests_and_do_not_mask_failure() =
        runTest {
            val client = createTheOtherDbClient(mockEngine { error("Disabled provider was contacted") })
            val preferences = preferences().apply { todbEnabled = false }
            try {
                val result =
                    repository(client, preferences, primary = {
                            _,
                            _,
                        ->
                        Result.failure(IllegalStateException("private error"))
                    })
                        .series(42, "剧集")
                assertTrue(result.isFailure)
                assertEquals("暂时无法获取分集排期", result.exceptionOrNull()?.message)
            } finally {
                client.close()
            }
        }

    @Test
    fun tvdb_reauthenticates_once_reads_all_pages_and_keeps_dates_without_invented_times() =
        runTest {
            var logins = 0
            val pages = mutableListOf<String?>()
            val client =
                createTheOtherDbClient(
                    mockEngine { request ->
                        when (request.url.host) {
                            "api.themoviedb.org" -> {
                                assertEquals("/3/tv/42/external_ids", request.url.encodedPath)
                                assertNull(request.headers["Authorization"])
                                respond("""{"tvdb_id":75692}""")
                            }
                            "api4.thetvdb.com" ->
                                when (request.url.encodedPath) {
                                    "/v4/login" -> {
                                        assertNull(request.headers["Authorization"])
                                        assertTrue((request.body as TextContent).text.contains("test-key"))
                                        logins++
                                        respond("""{"data":{"token":"token-$logins"}}""")
                                    }
                                    "/v4/series/75692/extended" -> {
                                        if (request.headers["Authorization"] == "Bearer token-1") {
                                            respond("expired", HttpStatusCode.Unauthorized)
                                        } else {
                                            assertEquals("Bearer token-2", request.headers["Authorization"])
                                            respond(
                                                """
{
  "data": {
    "originalCountry": "usa",
    "airsTime": "21:00",
    "seasons": [
      {
        "number": 1,
        "type": {
          "type": "official"
        }
      },
      {
        "number": 2,
        "type": {
          "type": "dvd"
        }
      }
    ]
  }
}
""",
                                            )
                                        }
                                    }
                                    "/v4/series/75692/episodes/official" -> {
                                        assertEquals("Bearer token-2", request.headers["Authorization"])
                                        assertEquals("1", request.url.parameters["season"])
                                        pages += request.url.parameters["page"]
                                        if (request.url.parameters["page"] == "0") {
                                            respond(
                                                """
{
  "data": {
    "episodes": [
      {
        "seasonNumber": 1,
        "number": 1,
        "aired": "2026-11-05"
      },
      {
        "seasonNumber": 2,
        "number": 3,
        "aired": "2026-11-12"
      }
    ]
  },
  "links": {
    "next": "https://untrusted.example/ignored"
  }
}
""",
                                            )
                                        } else {
                                            respond(
                                                """
{
  "data": {
    "episodes": [
      {
        "seasonNumber": 1,
        "number": 2,
        "aired": "2026-11-12"
      }
    ]
  },
  "links": {
    "next": null
  }
}
""",
                                            )
                                        }
                                    }
                                    else -> error("Unexpected TVDB request")
                                }
                            else -> error("Credentials must never be sent to another host")
                        }
                    },
                )
            val preferences =
                preferences().apply {
                    todbEnabled = false
                    saveTvdb("test-key", "test-pin")
                }
            try {
                val repository =
                    repository(client, preferences, primary = {
                            _,
                            _,
                        ->
                        Result.failure(IllegalStateException())
                    })
                val rows = repository.series(42, "剧集").getOrThrow()
                assertEquals<List<String?>>(listOf("0", "1"), pages)
                assertEquals(2, logins)
                assertEquals(listOf(1, 2), rows.map { it.episodeNumber })
                assertTrue(rows.all { it.scheduleProvider == "TVDB" && it.releaseMode == "DateOnly" })
                assertTrue(rows.all { it.airTime == null && it.timeZoneId == null && it.releaseAtUtc == null })
                repository.series(42, "剧集").getOrThrow()
                assertEquals(2, logins)
            } finally {
                client.close()
            }
        }

    @Test
    fun cancelled_lookup_propagates_cancellation() =
        runTest {
            val client = createTheOtherDbClient(mockEngine { error("No remote request expected") })
            try {
                val repository =
                    repository(client, preferences().apply { todbEnabled = false }, primary = { _, _ ->
                        throw kotlinx.coroutines.CancellationException("screen closed")
                    })
                kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { repository.series(42, "剧集") }
            } finally {
                client.close()
            }
        }

    private fun preferences() = CalendarMetadataPreferences(MapSettings(), TestSecureStore())

    private fun TestScope.mockEngine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        )

    private fun repository(
        client: HttpClient,
        preferences: CalendarMetadataPreferences = preferences(),
        primary: suspend (
            Int,
            String,
        ) -> Result<List<AiringEpisode>> = { _, _ -> Result.success(listOf(primaryEpisode)) },
    ) = CalendarMetadataRepository(primary, client, client, preferences)

    private val primaryEpisode = AiringEpisode(42, "剧集", "/poster.jpg", 1, 1, null, "2026-11-05", ShowOrigin.Domestic)
}
