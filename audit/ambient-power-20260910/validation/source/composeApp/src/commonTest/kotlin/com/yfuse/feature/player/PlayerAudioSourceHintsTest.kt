package com.yfuse.feature.player

import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.MediaVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAudioSourceHintsTest {
    @Test
    fun `preferred source audio geometry reaches the player version`() {
        val version =
            MediaVersion(
                id = "source",
                name = "4K",
                container = "mkv",
                sizeBytes = 1_000L,
                bitrateBps = 50_000_000,
                videoCodec = "hevc",
                videoHeight = 2160,
                videoRange = "HDR10",
                audioTracks =
                    listOf(
                        AudioTrackInfo(codec = "aac", channels = "2.0", language = "eng", channelCount = 2),
                        AudioTrackInfo(
                            codec = "truehd",
                            channels = "7.1",
                            language = "chi",
                            channelCount = 8,
                            sampleRateHz = 96_000,
                            default = true,
                        ),
                    ),
            )

        val playerVersion =
            listOf(version)
                .toPlayerMediaVersions("https://media.example", "item", "token", "session")
                .single()

        assertEquals(8, playerVersion.sourceAudioChannelCount)
        assertEquals(96_000, playerVersion.sourceAudioSampleRateHz)
        assertEquals(2, playerVersion.audioTrackCount)
    }
}
