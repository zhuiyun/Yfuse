package com.yfuse.core.widget

import com.yfuse.feature.widgets.widgetPlaybackRoutes
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetSourcePlaybackTest {
    private class Runtime : WidgetRuntime {
        val calls = mutableListOf<Pair<String, JsonElement>>()
        var response = "{}"
        var missingDetail = false

        override suspend fun metadata(source: String) = source

        override suspend fun invoke(
            widget: InstalledWidget,
            function: String,
            argument: JsonElement,
            timeoutSeconds: Int,
        ): String {
            calls += function to argument
            if (missingDetail && function == "loadDetail") error("组件未提供此函数")
            return response
        }
    }

    private suspend fun TestScope.repository(
        runtime: Runtime,
        stream: Boolean = false,
    ): WidgetRepository {
        val disk =
            object : WidgetPersistence {
                override suspend fun read(): String? = null

                override suspend fun write(value: String) = Unit
            }
        val repository = WidgetRepository(disk, runtime, backgroundScope, { "id" }, { it }, { 0L })
        val module =
            if (stream) {
                """,{"id":"loadResource","title":"Stream","type":"stream","functionName":"freshStream"}"""
            } else {
                ""
            }
        val metadata =
            """{"id":"local","title":"Local","modules":[{"id":"list","title":"List","functionName":"list"}$module]}"""
        repository.install(repository.inspect(metadata, "file:local.js"))
        return repository
    }

    @Test fun aRankingUrlCardKeepsItsTvIdentityAndSuppliesTheSelectedEpisodeToItsResolver() =
        runTest {
            val runtime =
                Runtime().apply {
                    response = """[{"name":"S2E3","url":"https://cdn.example/episode.m3u8"}]"""
                }
            val repository = repository(runtime, stream = true)
            val media =
                parseWidgetMedia(
                    """[{"id":"source-42","type":"url","mediaType":"tv","title":"玉簟秋","releaseDate":"2026","originalTitle":"Original title","seasonInfo":"第 2 季","genreTitle":"[GXF cat=tv|area=大陆|vid=42]","tmdbInfo":{"originalTitle":"Original title"},"videoUrl":"https://cdn.example/preview.mp4"}]""",
                ).single()
            assertEquals("tv", media.widgetDetailSeed().mediaType)
            assertEquals(0, media.widgetDetailSeed().id)
            assertNull(media.tmdbDetailItemOrNull())
            assertEquals(2, media.season)
            val context = WidgetSourceContext("local", media, buildJsonObject { put("quality", "HD") })
            val resolved = repository.playback(context, media.copy(episode = 3)).widgetPlaybackRoutes().single()
            assertEquals("https://cdn.example/episode.m3u8", resolved.videoUrl)
            val (function, argument) = runtime.calls.single()
            assertEquals("freshStream", function)
            val args = argument.jsonObject
            assertEquals(JsonPrimitive("玉簟秋"), args["title"])
            assertEquals(JsonPrimitive("玉簟秋"), args["seriesName"])
            assertEquals(JsonPrimitive("tv"), args["mediaType"])
            assertEquals(JsonPrimitive("url"), args["type"])
            assertEquals(JsonPrimitive(""), args["tmdbId"])
            assertEquals(JsonPrimitive(2), args["season"])
            assertEquals(JsonPrimitive(3), args["episode"])
            assertEquals(JsonPrimitive("2026"), args["releaseDate"])
            assertEquals(JsonPrimitive("Original title"), args["originalTitle"])
            assertEquals(media.resourceMetadata["genreTitle"], args["genreTitle"])
            assertEquals(media.resourceMetadata["tmdbInfo"], args["tmdbInfo"])
            assertEquals(JsonPrimitive("HD"), args["quality"])
        }

    @Test fun episodeRoutesRetainTheirSeriesTitleAndTmdbIdentityAfterDetailLoading() =
        runTest {
            val runtime =
                Runtime().apply {
                    response =
                        """{"episodes":[{"id":"ep-3","title":"第3集","season":2,"episode":3,"link":"https://source.example/3"}]}"""
                }
            val repository = repository(runtime, stream = true)
            val media =
                parseWidgetMedia(
                    """[{"id":"42","type":"tmdb","mediaType":"tv","title":"Series title","releaseDate":"2026","genreTitle":"Drama","link":"https://source.example/show"}]""",
                ).single()
            val loaded = repository.detail("local", media)
            val route = loaded.widgetPlaybackRoutes().single()
            runtime.response = """[{"name":"HD","url":"https://cdn.example/3.m3u8"}]"""
            repository.playback(WidgetSourceContext("local", loaded), route)
            val args =
                runtime.calls
                    .last()
                    .second
                    .jsonObject
            assertEquals(JsonPrimitive("第3集"), args["title"])
            assertEquals(JsonPrimitive("Series title"), args["seriesName"])
            assertEquals(JsonPrimitive("42"), args["tmdbId"])
            assertEquals(JsonPrimitive("tv"), args["mediaType"])
            assertEquals(JsonPrimitive("Drama"), args["genreTitle"])
            assertEquals(JsonPrimitive(2), args["season"])
            assertEquals(JsonPrimitive(3), args["episode"])
        }

    @Test fun anIdOnlyCatalogCardCanUseItsDeclaredStreamResolverWithoutADetailLink() =
        runTest {
            val runtime =
                Runtime().apply {
                    response = """[{"id":"route","title":"HD","videoUrl":"https://cdn.example/live.m3u8"}]"""
                }
            val repository = repository(runtime, stream = true)
            val media = WidgetMedia("room-42", "Live", mediaType = "video", type = "live")
            val context = WidgetSourceContext("local", media)
            assertTrue(context.hasStreamResolver(repository.database.value))
            val resolved = repository.playback(context, media).widgetPlaybackRoutes()
            assertEquals("freshStream", runtime.calls.single().first)
            val args =
                runtime.calls
                    .single()
                    .second
                    .jsonObject
            assertEquals(JsonPrimitive("room-42"), args["id"])
            assertEquals("https://cdn.example/live.m3u8", resolved.single().videoUrl)
            val database = repository.database.value
            assertFalse(
                context.hasStreamResolver(database.copy(widgets = database.widgets.map { it.copy(enabled = false) })),
            )
        }

    @Test fun live_url_cards_open_details_without_a_fake_movie_identity_and_keep_nested_sources() {
        val source =
            parseWidgetMedia(
                """[{"id":"room","type":"url","title":"Live room","link":"https://live.example/room","headers":{"Referer":"https://live.example/"},"childItems":[{"id":"one","name":"HD","videoUrl":"https://cdn.example/live.flv","customHeaders":{"User-Agent":"Widget"}}]}]""",
            ).single()
        assertEquals(0, source.widgetDetailSeed().id)
        assertEquals("video", source.widgetDetailSeed().mediaType)
        assertFalse(source.isWidgetFilmOrSeries())
        val route = source.widgetPlaybackRoutes().single()
        assertEquals("https://cdn.example/live.flv", route.videoUrl)
        assertEquals(mapOf("Referer" to "https://live.example/", "User-Agent" to "Widget"), route.headers)
    }

    @Test fun a_direct_playback_address_needs_neither_title_matching_nor_loadDetail() =
        runTest {
            val runtime = Runtime()
            val repository = repository(runtime)
            val media =
                WidgetMedia(
                    "direct",
                    "Film",
                    videoUrl = "https://cdn.example/film.m3u8",
                    headers =
                        mapOf(
                            "Referer" to "source",
                        ),
                )
            assertEquals(media, repository.playback(WidgetSourceContext("local", media), media))
            assertTrue(runtime.calls.isEmpty())
        }

    @Test fun a_live_link_refreshes_its_signed_address_on_play_but_does_not_poll_on_return() =
        runTest {
            val runtime =
                Runtime().apply { response = """{"videoUrl":"https://cdn.example/live.flv?token=fresh"}""" }
            val repository = repository(runtime)
            val media =
                WidgetMedia(
                    "room",
                    "Live",
                    mediaType = "video",
                    type = "url",
                    link = "https://live.example/room",
                    videoUrl = "https://cdn.example/live.flv?token=old",
                )
            repository.detail("local", media)
            repository.detail("local", media)
            assertEquals(1, runtime.calls.size)
            val prepared = repository.playback(WidgetSourceContext("local", media), media)
            assertEquals(2, runtime.calls.size)
            assertEquals("https://cdn.example/live.flv?token=fresh", prepared.videoUrl)
        }

    @Test fun url_identity_can_supply_a_direct_stream_when_loadDetail_is_absent() =
        runTest {
            val runtime = Runtime().apply { missingDetail = true }
            val repository = repository(runtime)
            val media =
                parseWidgetMedia(
                    """[{"id":"https://cdn.example/live.m3u8","type":"url","title":"Live"}]""",
                ).single()
            assertEquals(media.id, media.link)
            val prepared = repository.playback(WidgetSourceContext("local", media), media)
            assertEquals(media.id, prepared.videoUrl)
        }

    @Test fun linked_episodes_resolve_on_play_and_inherit_headers_and_identity() =
        runTest {
            val runtime =
                Runtime().apply {
                    response =
                        """{"videoUrl":"https://cdn.example/episode.m3u8","customHeaders":{"User-Agent":"Widget"}}"""
                }
            val repository = repository(runtime)
            val media =
                WidgetMedia(
                    "episode",
                    "Episode",
                    link = "https://source.example/episode",
                    season = 2,
                    episode = 3,
                    headers =
                        mapOf(
                            "Referer" to "source",
                        ),
                )
            val route = media.widgetPlaybackRoutes().single()
            val playback = repository.playback(WidgetSourceContext("local", media), route)
            assertEquals("loadDetail", runtime.calls.single().first)
            assertEquals("Episode", playback.title)
            assertEquals(2, playback.season)
            assertEquals(3, playback.episode)
            assertEquals(mapOf("Referer" to "source", "User-Agent" to "Widget"), playback.headers)
        }

    @Test fun returning_to_details_reuses_sources_but_explicit_retry_fetches_again() =
        runTest {
            val runtime = Runtime().apply { response = """{"videoUrl":"https://cdn.example/film.m3u8"}""" }
            val repository = repository(runtime)
            val media = WidgetMedia("one", "Film", link = "https://source.example/one")
            repository.detail("local", media)
            repository.detail("local", media)
            assertEquals(1, runtime.calls.size)
            repository.detail("local", media, forceRefresh = true)
            assertEquals(2, runtime.calls.size)
        }

    @Test fun a_live_resource_module_runs_on_each_play_with_the_original_user_parameters() =
        runTest {
            val runtime =
                Runtime().apply {
                    response =
                        """[{"name":"Live","url":"https://cdn.example/live.flv","headers":{"User-Agent":"Browser"}}]"""
                }
            val repository = repository(runtime, stream = true)
            val media = WidgetMedia("room", "Live", type = "url", link = "https://live.example/room")
            val context = WidgetSourceContext("local", media, buildJsonObject { put("quality", "HD") })
            repeat(2) {
                val playback = repository.playback(context, media).widgetPlaybackRoutes().single()
                assertEquals("https://cdn.example/live.flv", playback.videoUrl)
                assertEquals("Browser", playback.headers["User-Agent"])
            }
            assertEquals(listOf("freshStream", "freshStream"), runtime.calls.map { it.first })
            assertEquals(
                JsonPrimitive("HD"),
                runtime.calls
                    .last()
                    .second.jsonObject["quality"],
            )
            assertEquals(
                JsonPrimitive(media.link),
                runtime.calls
                    .last()
                    .second.jsonObject["link"],
            )
        }
}
