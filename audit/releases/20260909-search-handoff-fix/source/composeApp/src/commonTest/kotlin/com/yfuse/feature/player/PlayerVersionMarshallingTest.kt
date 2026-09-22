package com.yfuse.feature.player

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The version list crosses a process boundary as JSON.
 *
 * It used to be six fields joined by an ASCII separator and decoded positionally, which
 * silently dropped every field added afterwards. These lock in the two properties that
 * mattered: everything survives a round trip, and a payload written by a different build
 * still decodes rather than taking the whole queue down with it.
 */
class PlayerVersionMarshallingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlayerMediaVersion.serializer())

    @Test
    fun every_field_survives_the_round_trip() {
        val original =
            listOf(
                PlayerMediaVersion(
                    id = "src-1",
                    label = "Bluray 2160p",
                    detail = "4K Dolby Vision · 60 GB",
                    url = "https://s/a",
                    transcodeUrl = "https://s/b",
                    fallbackTranscodeUrl = "https://s/c",
                    container = "MKV",
                    dolbyVision = true,
                    dolbyAtmos = true,
                    dolbyProfile = 5,
                    needsDolbyDecoder = true,
                    sourceWidth = 3840,
                    sourceBitrateBps = 80_000_000,
                    sourceAudioChannelCount = 8,
                    sourceAudioSampleRateHz = 96_000,
                ),
            )

        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, original))

        assertEquals(original, restored)
    }

    @Test
    fun a_payload_from_another_build_decodes_instead_of_failing() {
        // Fewer keys than this build knows about: the missing ones take their defaults.
        val older =
            """[{"id":"src-1","label":"文件","detail":"","url":"u",""" +
                """"transcodeUrl":"t","fallbackTranscodeUrl":"f"}]"""
        // More keys than this build knows about: the unknown one is ignored.
        val newer =
            """[{"id":"src-1","label":"文件","detail":"","url":"u",""" +
                """"transcodeUrl":"t","fallbackTranscodeUrl":"f","somethingLater":true}]"""

        listOf(older, newer).forEach { payload ->
            val restored = json.decodeFromString(serializer, payload)
            assertEquals("src-1", restored.single().id)
            assertTrue(!restored.single().dolbyVision)
        }
    }
}
