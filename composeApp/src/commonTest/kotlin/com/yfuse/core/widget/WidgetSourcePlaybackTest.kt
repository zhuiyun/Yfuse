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
            val runtime = Runtime().apply { response = """{"videoUrl":"https://cdn.example/live.flv?token=fresh"}""" }
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
