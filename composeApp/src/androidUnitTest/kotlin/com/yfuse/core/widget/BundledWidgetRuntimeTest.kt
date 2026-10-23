package com.yfuse.core.widget

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundledWidgetRuntimeTest {
    private data class Request(
        val options: JsonObject,
        val tmdb: Boolean,
    )

    private class Host(
        var payload: JsonElement = widgetJson.parseToJsonElement("""{"results":[],"items":[]}"""),
    ) {
        val calls = mutableListOf<Request>()
        var status = 200
        var publicStatus: Int? = null
        var publicPayload: JsonElement? = null
        val runtime =
            SandboxWidgetRuntime(
                request = { options, tmdb ->
                    calls += Request(options, tmdb)
                    val responseStatus = if (tmdb) status else publicStatus ?: status
                    buildJsonObject {
                        put("ok", responseStatus in 200..299)
                        put("status", responseStatus)
                        put("data", if (tmdb) payload else publicPayload ?: payload)
                    }.toString()
                },
                readStorage = { _, _ -> null },
                writeStorage = { _, _, _ -> error("No writes expected") },
            )
    }

    private fun bundledSource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "Missing bundled widget resource: $name" }
            .bufferedReader()
            .use { it.readText() }

    private suspend fun bundles(host: Host): List<InstalledWidget> =
        bundledWidgetSources(::bundledSource).map {
            InstalledWidget(
                parseWidgetMetadata(host.runtime.metadata(it.source), "invalid"),
                it.source,
                it.origin,
                widgetHash(it.source),
            )
        }

    private suspend fun call(
        host: Host,
        widget: InstalledWidget,
        function: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) = parseWidgetMedia(host.runtime.invoke(widget, function, params, 5))

    @Test fun shipped_resources_install_without_network_and_all_preset_sections_reference_valid_modules() =
        runBlocking {
            val host = Host()
            val widgets = bundles(host)
            assertEquals(listOf(BUNDLED_TMDB_ID, BUNDLED_RECOMMENDATIONS_ID), widgets.map { it.metadata.id })
            assertEquals(listOf(9, 5), widgets.map { it.metadata.modules.size })
            assertTrue(host.calls.isEmpty())
            val sources = bundledWidgetSources(::bundledSource)
            val result = mergeBundledWidgets(WidgetDatabase(), sources.zip(widgets))
            assertEquals(2, result.pages.size)
            assertTrue(result.pages.flatMap { it.sections }.all { it.style == "poster" })
            assertTrue(widgets.all { it.source.encodeToByteArray().size < MAX_WIDGET_SOURCE_BYTES })
        }

    @Test fun all_tmdb_modules_use_the_original_public_route_and_expected_paths() =
        runBlocking {
            val host =
                Host(
                    widgetJson.parseToJsonElement(
                        """{"results":[{"id":12,"title":"电影","name":"剧集","poster_path":"/poster.jpg"}]}""",
                    ),
                )
            val widget = bundles(host).first()
            val paths =
                mapOf(
                    "nowPlaying" to "/movie/now_playing",
                    "trending" to "/trending/all/day",
                    "popular" to "/movie/popular",
                    "topRated" to "/movie/top_rated",
                    "categories" to "/discover/movie",
                    "networks" to "/discover/tv",
                    "companies" to "/discover/movie",
                    "getTrendingMovies" to "/trending/movie/day",
                    "getTrendingTv" to "/trending/tv/day",
                )
            widget.metadata.modules.forEach { module ->
                val params =
                    module.parameterValues(
                        buildJsonObject {
                            put("with_networks", "213")
                            put("with_companies", "2")
                        },
                        2,
                    )
                val media = call(host, widget, module.functionName, params).single()
                val request = host.calls.last()
                val expectedUrl = "https://recommend.feifeiduck.com/tmdb${paths[module.id]}"
                assertEquals(expectedUrl, request.options.text("url"), module.id)
                assertFalse(request.tmdb, module.id)
                assertFalse("headers" in request.options)
                assertEquals("2", request.options["params"]?.jsonObject?.text("page"))
                assertEquals("zh-CN", request.options["params"]?.jsonObject?.text("language"))
                assertEquals("12", media.tmdbId)
                assertEquals("tmdb", media.type)
                assertEquals("https://image.tmdb.org/t/p/w780/poster.jpg", media.poster)
                assertTrue(media.videoUrl.isEmpty())
            }
        }

    @Test fun trending_filters_people_invalid_ids_and_empty_titles_but_keeps_movie_tv_identity() =
        runBlocking {
            val host =
                Host(
                    widgetJson.parseToJsonElement(
                        """{"results":[
            {"id":12,"title":"Movie","media_type":"movie"},
            {"id":12,"name":"Series","media_type":"tv"},
            {"id":99,"name":"Person","media_type":"person"},
            {"id":0,"title":"Invalid"},{"id":"x","title":"Invalid"},{"id":21},null
        ]}""",
                    ),
                )
            val result = call(host, bundles(host).first(), "trending")
            assertEquals(listOf("movie.12", "tv.12"), result.map { it.id })
            assertEquals(listOf("movie", "tv"), result.map { it.mediaType })
        }

    @Test fun tmdb_options_route_to_correct_media_type_and_strip_foreign_authentication_parameters() =
        runBlocking {
            val host = Host()
            val widget = bundles(host).first()
            call(host, widget, "nowPlaying", buildJsonObject { put("type", "tv") })
            assertEquals(
                "https://recommend.feifeiduck.com/tmdb/tv/on_the_air",
                host.calls
                    .last()
                    .options
                    .text("url"),
            )
            call(host, widget, "trending", buildJsonObject { put("time_window", "week") })
            assertEquals(
                "https://recommend.feifeiduck.com/tmdb/trending/all/week",
                host.calls
                    .last()
                    .options
                    .text("url"),
            )
            call(
                host,
                widget,
                "categories",
                buildJsonObject {
                    put("type", "tv")
                    put("with_genres", "878")
                },
            )
            assertEquals(
                "https://recommend.feifeiduck.com/tmdb/discover/tv",
                host.calls
                    .last()
                    .options
                    .text("url"),
            )
            assertEquals(
                "10765",
                host.calls
                    .last()
                    .options["params"]
                    ?.jsonObject
                    ?.text("with_genres"),
            )
            listOf("10763", "10767", "10768").forEach { genre ->
                call(
                    host,
                    widget,
                    "categories",
                    buildJsonObject {
                        put("type", "movie")
                        put("with_genres", genre)
                    },
                )
                assertEquals(
                    "https://recommend.feifeiduck.com/tmdb/discover/tv",
                    host.calls
                        .last()
                        .options
                        .text("url"),
                )
            }
            call(
                host,
                widget,
                "popular",
                buildJsonObject {
                    put("__capyTmdbApiToken", "foreign-token")
                    put("__capyTmdbApiBaseUrl", "https://example.invalid")
                    put("api_key", "foreign-key")
                    put("authorization", "foreign-auth")
                    put("session_id", "foreign-session")
                    put("language", "zh-TW")
                },
            )
            val request = host.calls.last().options
            assertFalse(request.toString().contains("foreign"))
            assertFalse(request.toString().contains("example.invalid"))
            assertEquals("zh-TW", request["params"]?.jsonObject?.text("language"))
        }

    @Test fun failed_or_malformed_public_tmdb_results_fall_back_to_yfuse_with_bounded_timeouts() =
        runBlocking {
            for (malformed in listOf(false, true)) {
                val host = Host(widgetJson.parseToJsonElement("""{"results":[{"id":12,"title":"Movie"}]}"""))
                if (malformed) host.publicPayload = JsonPrimitive("invalid") else host.publicStatus = 503
                val result = call(host, bundles(host).first(), "popular").single()
                assertEquals("12", result.tmdbId)
                assertEquals(listOf(false, true), host.calls.map { it.tmdb })
                assertEquals(
                    "https://recommend.feifeiduck.com/tmdb/movie/popular",
                    host.calls
                        .first()
                        .options
                        .text("url"),
                )
                assertEquals(
                    "/movie/popular",
                    host.calls
                        .last()
                        .options
                        .text("url"),
                )
                assertEquals(listOf("10000", "18000"), host.calls.map { it.options.text("timeout") })
                assertTrue(host.calls.all { "headers" !in it.options })
            }
        }

    @Test fun every_recommendation_chart_uses_the_observed_service_without_credentials() =
        runBlocking {
            val host =
                Host(
                    widgetJson.parseToJsonElement(
                        """{"items":[{"tmdbId":12,"title":"Film","media_type":"movie","vote_average":8.45}]}""",
                    ),
                )
            val widget = bundles(host).last()
            val seen = mutableSetOf<String>()
            widget.metadata.modules.forEach { module ->
                module.params.single { it.name == "chart" }.options.forEach { chart ->
                    val params = module.parameterValues(buildJsonObject { put("chart", chart.value) })
                    val media = call(host, widget, module.functionName, params).single()
                    val request = host.calls.last()
                    assertFalse(request.tmdb)
                    val path = chart.value.jsonPrimitive.content
                    assertEquals("https://recommend.feifeiduck.com/$path", request.options.text("url"))
                    assertEquals("100", request.options["params"]?.jsonObject?.text("limit"))
                    assertFalse("headers" in request.options)
                    assertEquals("12", media.tmdbId)
                    assertEquals("movie", media.mediaType)
                    assertEquals("8.5", media.rating)
                    seen += path
                }
            }
            assertEquals(25, seen.size)
        }

    @Test fun recommendation_pagination_deduplicates_and_filters_before_slicing() =
        runBlocking {
            val host =
                Host(
                    buildJsonObject {
                        put(
                            "items",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("tmdbId", 0)
                                        put("media_type", "tv")
                                        put("title", "Invalid")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("tmdbId", 1)
                                        put("media_type", "person")
                                        put("title", "Person")
                                    },
                                )
                                repeat(98) { i ->
                                    add(
                                        buildJsonObject {
                                            put("tmdbId", i + 1)
                                            put("media_type", "tv")
                                            put("name", "Series ${i + 1}")
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            val widget = bundles(host).last()

            suspend fun page(number: Int) =
                call(
                    host,
                    widget,
                    "recTv",
                    buildJsonObject {
                        put("chart", "douban/hot-tv")
                        put("limit", 30)
                        put("page", number)
                    },
                )
            val first = page(1)
            val second = page(2)
            assertEquals((1..30).map { it.toString() }, first.map { it.tmdbId })
            assertEquals((31..60).map { it.toString() }, second.map { it.tmdbId })
            assertEquals(8, page(4).size)
            val before = host.calls.size
            assertTrue(page(5).isEmpty())
            assertEquals(before, host.calls.size)
            host.payload =
                widgetJson.parseToJsonElement(
                    """{"items":[
            {"tmdbId":1,"media_type":"movie","title":"One"},{"tmdbId":1,"media_type":"movie","title":"Duplicate"},
            {"tmdbId":1,"media_type":"tv","title":"Series"}
        ]}""",
                )
            assertEquals(listOf("movie.1", "tv.1"), page(1).map { it.id })
        }

    @Test fun recommendation_calendar_passes_weekday_and_language() =
        runBlocking {
            val host = Host()
            call(
                host,
                bundles(host).last(),
                "recCalendar",
                buildJsonObject {
                    put("chart", "bangumi/calendar")
                    put("day", "3")
                    put("language", "zh-TW")
                },
            )
            val params =
                host.calls
                    .single()
                    .options["params"]!!
                    .jsonObject
            assertEquals("3", params.text("day"))
            assertEquals("zh-TW", params.text("language"))
        }

    @Test fun invalid_parameters_are_rejected_before_network() =
        runBlocking {
            val host = Host()
            val widgets = bundles(host)
            listOf("../private", "https://example.invalid", "douban/hot-movie?token=x").forEach { path ->
                assertFails { call(host, widgets.last(), "recMovie", buildJsonObject { put("chart", path) }) }
            }
            listOf("NaN", "Infinity", "-1").forEach { page ->
                assertFails { call(host, widgets.first(), "popular", buildJsonObject { put("page", page) }) }
                assertFails {
                    call(
                        host,
                        widgets.last(),
                        "recMovie",
                        buildJsonObject {
                            put("chart", "douban/hot-movie")
                            put("page", page)
                        },
                    )
                }
            }
            assertFails {
                call(
                    host,
                    widgets.last(),
                    "recCalendar",
                    buildJsonObject {
                        put("chart", "bangumi/calendar")
                        put("day", "8")
                    },
                )
            }
            assertTrue(host.calls.isEmpty())
        }

    @Test fun service_errors_and_malformed_lists_are_not_reported_as_empty_successes() =
        runBlocking {
            val host = Host()
            val widgets = bundles(host)
            val chart = buildJsonObject { put("chart", "douban/hot-movie") }
            host.status = 503
            assertFails { call(host, widgets.first(), "popular") }
            assertFails { call(host, widgets.last(), "recMovie", chart) }
            host.status = 200
            host.payload = JsonPrimitive("invalid-json")
            assertFails { call(host, widgets.first(), "popular") }
            assertFails { call(host, widgets.last(), "recMovie", chart) }
            host.payload = widgetJson.parseToJsonElement("""{"results":{},"items":{}}""")
            assertFails { call(host, widgets.first(), "popular") }
            assertFails { call(host, widgets.last(), "recMovie", chart) }
            host.payload = widgetJson.parseToJsonElement("""{"results":[],"items":[]}""")
            assertTrue(call(host, widgets.first(), "popular").isEmpty())
            assertTrue(call(host, widgets.last(), "recMovie", chart).isEmpty())
        }
}
