package com.yfuse.core2.android

import com.yfuse.core2.api.YChapter
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.dolby.YDolbyVisionStreamEvidence
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.strategy.YPlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidYCoreVerifiedRouteMemoryTest {
    private val dolbyConfig =
        YDolbyVisionConfig(
            versionMajor = 1,
            versionMinor = 0,
            profile = 5,
            level = 6,
            rpuPresent = true,
            enhancementLayerPresent = false,
            baseLayerPresent = true,
            baseLayerCompatibilityId = 0,
            metadataCompression = 0,
        )

    private val probe =
        YCore2ProbeResult.Success(
            playbackRequest =
                YPlaybackRequest(
                    container = YContainer.Matroska,
                    video =
                        YVideoRequirement(
                            codec = YVideoCodec.H265,
                            width = 3840,
                            height = 2160,
                            frameRate = 23.976f,
                            bitDepth = 10,
                            hdrType = YHdrType.DolbyVision,
                            dolbyVisionProfile = 5,
                        ),
                    audio = YAudioRequirement(codec = YAudioCodec.Eac3, channelCount = 6, sampleRate = 48_000),
                    platformDemuxSupported = true,
                    enhancedDemuxSupported = true,
                    fallbackHdrType = null,
                    platformAudioDemuxSupported = false,
                    sourceDeclaresAudio = true,
                ),
            videoMime = "video/dolby-vision",
            audioMime = "audio/eac3",
            durationMs = 2_940_000L,
            dolbyVisionConfig = dolbyConfig,
            // Bitstream evidence is deliberately not persisted; the config alone routes P5.
            dolbyVisionStreamEvidence = YDolbyVisionStreamEvidence(dolbyConfig),
        )

    @Test
    fun `a verified probe survives the round trip without its bitstream evidence`() {
        val record = YVerifiedRouteRecord("emby/108450/v1", probe, verifiedAtEpochMs = 1_700_000_000_000L)
        val encoded = encodeVerifiedRouteRecord(record, systemImage = "36:1")
        val decoded = decodeVerifiedRouteRecord(encoded, systemImage = "36:1")

        assertEquals(record.copy(probe = probe.copy(dolbyVisionStreamEvidence = null)), decoded)
        // The record names the media only through its identity, never through a URL.
        assertFalse(encoded.contains("http"))
    }

    @Test
    fun `the pixel aspect ratio and chapters are remembered, and version 1 records still read`() {
        val anamorphic =
            probe.copy(
                dolbyVisionStreamEvidence = null,
                pixelAspectRatio = 32.0 / 27.0,
                chapters = listOf(YChapter(0L, "序章"), YChapter(90_000L, "Opening: part 1")),
            )
        val record = YVerifiedRouteRecord("local/film/1", anamorphic, verifiedAtEpochMs = 9L)
        val encoded = encodeVerifiedRouteRecord(record, "36:1")

        assertEquals(record, decodeVerifiedRouteRecord(encoded, "36:1"))
        // A record written before version 2: the first 35 fields under version 1.
        val versionOne =
            "1" +
                encoded
                    .split('\t')
                    .take(35)
                    .drop(1)
                    .joinToString("\t", prefix = "\t")
        assertEquals(
            record.copy(probe = anamorphic.copy(pixelAspectRatio = null, chapters = emptyList())),
            decodeVerifiedRouteRecord(versionOne, "36:1"),
        )
    }

    @Test
    fun `records from another system image or layout are ignored`() {
        val record = YVerifiedRouteRecord("emby/108450/v1", probe, verifiedAtEpochMs = 1L)
        val encoded = encodeVerifiedRouteRecord(record, systemImage = "36:1")
        assertNull(decodeVerifiedRouteRecord(encoded, systemImage = "35:1"))
        assertNull(decodeVerifiedRouteRecord(encoded.substringBeforeLast('\t'), systemImage = "36:1"))
        assertNull(decodeVerifiedRouteRecord("garbage", systemImage = "36:1"))
    }

    @Test
    fun `audio-only and sdr probes round trip with their absent fields`() {
        val audioOnly =
            probe.copy(
                playbackRequest =
                    probe.playbackRequest.copy(
                        audioOnly = true,
                        video = YVideoRequirement(codec = YVideoCodec.H264),
                    ),
                videoMime = "video/avc",
                audioMime = null,
                dolbyVisionConfig = null,
                dolbyVisionStreamEvidence = null,
            )
        val record = YVerifiedRouteRecord("emby/1/", audioOnly, verifiedAtEpochMs = 5L)
        assertEquals(record, decodeVerifiedRouteRecord(encodeVerifiedRouteRecord(record, "36:1"), "36:1"))
    }

    @Test
    fun `only media with a credential-free identity is remembered and profile 7 never is`() {
        val identified =
            YMediaItem(
                id = "item",
                uri = "https://origin/item.mkv?token=secret",
                cacheIdentity = YCacheIdentity(scope = "emby", mediaId = "108450", version = "v1"),
            )
        assertEquals("emby/108450/v1", identified.verifiedRouteIdentity())
        assertNull(YMediaItem(id = "item", uri = "https://origin/item.mkv").verifiedRouteIdentity())

        assertTrue(probe.isPersistableAsVerifiedRoute())
        assertFalse(probe.copy(unconfiguredDolbyVisionSignal = true).isPersistableAsVerifiedRoute())
        assertFalse(probe.copy(dolbyVisionConfig = dolbyConfig.copy(profile = 7)).isPersistableAsVerifiedRoute())
    }
}
