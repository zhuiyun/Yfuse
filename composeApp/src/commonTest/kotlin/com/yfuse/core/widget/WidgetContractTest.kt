package com.yfuse.core.widget

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetContractTest {
    @Test
    fun required_and_numeric_parameters_report_actionable_errors() {
        val module =
            WidgetModule(
                "list",
                "List",
                "list",
                params =
                    listOf(
                        WidgetParameter("year", "年份", "integer", required = true),
                    ),
            )
        assertEquals("请填写年份", module.parameterError(JsonObject(emptyMap())))
        assertEquals("年份需为有效数字", module.parameterError(buildJsonObject { put("year", "abc") }))
        assertEquals("年份需为整数", module.parameterError(buildJsonObject { put("year", 2.5) }))
        assertEquals(
            "年份格式不支持，请重新填写",
            module.parameterError(buildJsonObject { put("year", JsonObject(emptyMap())) }),
        )
        assertNull(module.parameterError(buildJsonObject { put("year", 2026) }))
    }

    @Test
    fun structured_parameter_defaults_and_choices_are_refused_before_installation() {
        val invalidParameters =
            listOf(
                """{"name":"kind","defaultValue":{"nested":"value"}}""",
                """{"name":"kind","enumOptions":[{"title":"Invalid","value":[]}]}""",
            )
        invalidParameters.forEach { parameter ->
            assertFailsWith<IllegalArgumentException> {
                parseWidgetMetadata(
                    """{"title":"Example","modules":[{"title":"List","functionName":"load","params":[$parameter]}]}""",
                    "example",
                )
            }
        }
    }

    @Test fun capy_metadata_aliases_global_parameters_and_conditions_are_supported() {
        val metadata =
            parseWidgetMetadata(
                """{
          "name":"My widgets", "globalParams":[{"name":"kind","type":"enum","value":"movie","enumValues":["movie","tv"]}],
          "modules":[{"name":"Recent","functionName":"api.load","params":[{"name":"year","type":"number","belongTo":{"paramName":"kind","value":["movie"]}}]}]
        }""",
                "fallback",
            )
        val module = metadata.modules.single()
        assertEquals("fallback", metadata.id)
        assertEquals("api.load", module.id)
        assertEquals(2, module.params.size)
        assertTrue(module.params.last().visible(module.parameterValues(JsonObject(emptyMap()))))
        assertFalse(module.params.last().visible(buildJsonObject { put("kind", "tv") }))
    }

    @Test fun injected_function_names_and_empty_modules_are_refused() {
        assertFailsWith<IllegalArgumentException> {
            parseWidgetMetadata("""{"title":"x","modules":[{"title":"x","functionName":"x);evil("}]}""", "x")
        }
        assertFailsWith<IllegalArgumentException> { parseWidgetMetadata("""{"title":"x","modules":[]}""", "x") }
    }

    @Test fun media_aliases_missing_artwork_and_progress_are_normalized_without_inventing_metadata() {
        val items =
            parseWidgetMedia(
                """[{"id":42,"name":"Movie","poster_path":"/poster.jpg","backdropPath":"/wide.jpg","type":"tmdb","mediaType":"movie","progress":9},{"id":"empty","title":"No image"}]""",
            )
        assertEquals("42", items.first().tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w780/poster.jpg", items.first().poster)
        assertEquals("https://image.tmdb.org/t/p/w780/wide.jpg", items.first().backdrop)
        assertEquals(1f, items.first().progress)
        assertEquals("", items.last().rating)
        assertEquals("", items.last().poster)
        assertNull(items.last().progress)
    }

    @Test fun detail_supports_seasons_episodes_and_playback_headers() {
        val raw =
            """
            {"title":"Series","customHeaders":{"Referer":"https://media.example/"},"seasons":[
              {"id":"s1","title":"Season 1","seasonNumber":1,"episodes":[
                {"id":"ep1","title":"Episode 1","episodeNumber":1,"videoUrl":"https://media.example/1.mp4"}
              ]}
            ]}
            """.trimIndent()
        val media = parseWidgetItem(widgetJson.parseToJsonElement(raw).jsonObject)
        assertEquals(
            "https://media.example/1.mp4",
            media.children
                .single()
                .children
                .single()
                .videoUrl,
        )
        assertEquals("https://media.example/", media.headers["Referer"])
    }

    @Test fun lists_must_be_arrays_and_duplicate_identifiers_do_not_crash_lazy_layouts() {
        assertFailsWith<IllegalArgumentException> { parseWidgetMedia("""{"results":[]}""") }
        assertEquals(1, parseWidgetMedia("""[{"id":"a","title":"A"},{"id":"a","title":"A"}]""").size)
    }

    @Test fun every_saved_style_resolves_and_unknown_future_styles_fall_back() {
        assertEquals(
            36,
            WidgetStyle.entries
                .map { it.id }
                .distinct()
                .size,
        )
        WidgetStyle.entries.forEach { assertEquals(it, WidgetStyle.fromId(it.id)) }
        assertEquals(WidgetStyle.Landscape, WidgetStyle.fromId("future-layout"))
    }
}
