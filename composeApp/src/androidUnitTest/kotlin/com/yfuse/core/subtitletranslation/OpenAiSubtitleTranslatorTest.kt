package com.yfuse.core.subtitletranslation

import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import com.yfuse.core2.subtitle.YSubtitleTimeline
import com.yfuse.core2.subtitle.YTextSubtitleParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiSubtitleTranslatorTest {
    @Test fun cumulativeOutputBudgetAppliesAcrossBatches() =
        runBlocking {
            MockWebServer().use { server ->
                for (ids in listOf(0..39, 40..40)) {
                    val content =
                        buildJsonArray {
                            for (id in ids) {
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("text", "a")
                                    },
                                )
                            }
                        }
                    val response =
                        buildJsonObject {
                            putJsonArray("choices") {
                                add(buildJsonObject { putJsonObject("message") { put("content", content.toString()) } })
                            }
                        }
                    server.enqueue(MockResponse().setBody(response.toString()))
                }
                val cues =
                    (0..40).map {
                        YSubtitleCue("$it", it * 1_000_000L, (it + 1) * 1_000_000L, YSubtitlePayload.Text("caption"))
                    }
                assertFailsWith<IllegalArgumentException> {
                    OpenAiSubtitleTranslator(OkHttpClient(), maximumTranslatedCharacters = 40).translate(
                        YSubtitleTimeline(cues),
                        SubtitleTranslationSettings(server.url("/v1").toString(), "test-model"),
                    )
                }
                assertEquals(2, server.requestCount)
            }
        }

    @Test fun rejectsMissingDuplicateAndReorderedTranslationIds() {
        assertFailsWith<IllegalArgumentException> { parseTranslationBatch("""[{"id":0,"text":"a"}]""", 0, 2) }
        assertFailsWith<IllegalArgumentException> {
            parseTranslationBatch("""[{"id":0,"text":"a"},{"id":0,"text":"b"}]""", 0, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            parseTranslationBatch("""[{"id":1,"text":"a"},{"id":0,"text":"b"}]""", 0, 2)
        }
    }

    @Test fun allowsHttpAndPreservesConfiguredBasePath() {
        assertEquals(
            "http://localhost:9000/v1/chat/completions",
            translationEndpoint("http://localhost:9000/v1/").toString(),
        )
        assertEquals(
            "http://localhost/chat/completions",
            translationEndpoint("http://localhost/chat/completions").toString(),
        )
        assertFailsWith<IllegalArgumentException> { translationEndpoint("http://user:password@localhost/v1") }
    }

    @Test fun translationKeepsAuthoredTimingAndSendsOnlyCaptionText() =
        runBlocking {
            MockWebServer().use { server ->
                val response =
                    buildJsonObject {
                        putJsonArray("choices") {
                            add(
                                buildJsonObject {
                                    putJsonObject(
                                        "message",
                                    ) { put("content", """[{"id":0,"text":"你好"},{"id":1,"text":"世界"}]""") }
                                },
                            )
                        }
                    }
                server.enqueue(MockResponse().setBody(response.toString()))
                val timeline =
                    YTextSubtitleParser.parse(
                        "1\n00:00:01,250 --> 00:00:03,000\nHello\n\n2\n00:00:04,500 --> 00:00:06,900\nWorld\n",
                        YSubtitleFormat.Srt,
                    )
                val result =
                    OpenAiSubtitleTranslator(
                        OkHttpClient(),
                    ).translate(
                        timeline,
                        SubtitleTranslationSettings(server.url("/v1").toString(), "configured-model", "private"),
                    )
                val parsed = YTextSubtitleParser.parse(result, YSubtitleFormat.Srt)
                assertEquals(timeline.cues.map { it.startUs to it.endUs }, parsed.cues.map { it.startUs to it.endUs })
                assertTrue(result.contains("你好"))
                val request = server.takeRequest()
                assertEquals("Bearer private", request.getHeader("Authorization"))
                val payload = request.body.readUtf8()
                assertTrue(payload.contains("Hello"))
                assertFalse(payload.contains("00:00:"))
            }
        }
}
