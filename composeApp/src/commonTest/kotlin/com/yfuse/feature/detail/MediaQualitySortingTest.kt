package com.yfuse.feature.detail

import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.MediaStreamDto
import com.yfuse.core.data.dto.toSourceInfo
import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.model.VideoStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaQualitySortingTest {
    @Test
    fun version_resolution_beats_file_size() {
        val oversized1080p =
            version(
                id = "1080",
                width = 1920,
                height = 1080,
                bitrate = 80_000_000,
                size = 100L.gib,
            )
        val compact4k =
            version(
                id = "4k",
                width = 3840,
                height = 1600,
                bitrate = 20_000_000,
                size = 20L.gib,
            )

        assertEquals(
            listOf("4k", "1080"),
            listOf(oversized1080p, compact4k).bestVersionsFirst().map { it.id },
        )
        assertEquals("4K", compact4k.resolutionLabel)
    }

    @Test
    fun version_uses_bitrate_then_range_and_audio_quality_to_break_resolution_ties() {
        val highBitrate = version(id = "bitrate", bitrate = 70_000_000)
        val dolbyVision = version(id = "dv", bitrate = 50_000_000, range = "DOVI", dvProfile = 8)
        val hdrAtmos =
            version(
                id = "atmos",
                bitrate = 50_000_000,
                range = "HDR10",
                audio = listOf(audio(codec = "eac3", profile = "Dolby Atmos", channels = 8)),
            )
        val hdrStereo = version(id = "stereo", bitrate = 50_000_000, range = "HDR10")

        assertEquals(
            listOf("bitrate", "dv", "atmos", "stereo"),
            listOf(hdrStereo, hdrAtmos, dolbyVision, highBitrate)
                .bestVersionsFirst()
                .map { it.id },
        )
    }

    @Test
    fun version_size_is_the_fallback_when_stream_quality_is_missing() {
        val small = version(id = "small", width = null, height = null, bitrate = null, size = 3L.gib)
        val large = version(id = "large", width = null, height = null, bitrate = null, size = 12L.gib)

        assertEquals(
            listOf("large", "small"),
            listOf(small, large).bestVersionsFirst().map { it.id },
        )
    }

    @Test
    fun server_sort_uses_raw_resolution_instead_of_label_or_size() {
        val raw4k =
            source(
                id = "raw-4k",
                info =
                    sourceInfo(
                        quality = "未知清晰度",
                        width = 3840,
                        height = 1600,
                        bitrate = 20_000_000,
                        size = 18L.gib,
                    ),
            )
        val misleadingLabel =
            source(
                id = "label-4k",
                info =
                    sourceInfo(
                        quality = "4K HDR",
                        width = 1920,
                        height = 1080,
                        bitrate = 80_000_000,
                        size = 80L.gib,
                    ),
            )

        assertEquals(
            listOf("raw-4k", "label-4k"),
            listOf(misleadingLabel, raw4k).bestSourcesFirst().map { it.serverId },
        )
    }

    @Test
    fun server_sort_is_deterministic_and_puts_unavailable_entries_last() {
        val sameQuality = sourceInfo(width = 1920, height = 1080, bitrate = 15_000_000)
        val beta = source(id = "b", name = "Beta", info = sameQuality)
        val alpha = source(id = "a", name = "Alpha", info = sameQuality)
        val unavailable = source(id = "offline", name = "Offline", info = sameQuality, reachable = false)

        assertEquals(
            listOf("a", "b", "offline"),
            listOf(unavailable, beta, alpha).bestSourcesFirst().map { it.serverId },
        )
    }

    @Test
    fun source_mapping_keeps_raw_video_and_audio_quality() {
        val info =
            MediaSourceDto(
                Id = "media",
                Size = 25L.gib,
                // Exercise the stream-bitrate fallback used by servers that omit source bitrate.
                Bitrate = null,
                MediaStreams =
                    listOf(
                        MediaStreamDto(
                            Type = "Video",
                            Width = 3840,
                            Height = 1600,
                            BitRate = 24_000_000,
                            VideoRange = "HDR10",
                            BitDepth = 10,
                        ),
                        MediaStreamDto(
                            Type = "Audio",
                            Codec = "truehd",
                            Profile = "Dolby Atmos",
                            Channels = 8,
                            BitRate = 4_500_000,
                        ),
                    ),
            ).toSourceInfo()!!

        assertEquals("4K HDR10", info.quality)
        assertEquals(3840, info.videoWidth)
        assertEquals(1600, info.videoHeight)
        assertEquals(24_000_000, info.bitrateBps)
        assertEquals("HDR10", info.videoRange)
        assertEquals(10, info.videoBitDepth)
        assertEquals(8, info.maxAudioChannels)
        assertEquals(4_500_000, info.maxAudioBitrateBps)
        assertTrue(info.dolbyAtmos)
        assertTrue(info.losslessAudio)
        assertTrue(info.hasQualityEvidence())
    }

    @Test
    fun lossless_audio_detection_does_not_mistake_adpcm_for_pcm() {
        assertTrue(audio(codec = "pcm_s24le").isLossless)
        assertTrue(audio(codec = "dts", profile = "DTS-HD MA").isLossless)
        assertFalse(audio(codec = "adpcm").isLossless)
    }

    @Test
    fun selected_version_restates_all_structured_source_facts() {
        val base =
            source(
                id = "server",
                itemId = "movie",
                info = sourceInfo(width = 1920, height = 1080, bitrate = 10_000_000),
            )
        val selected =
            version(
                id = "selected",
                width = 3840,
                height = 2160,
                bitrate = 60_000_000,
                range = "DOVI",
                dvProfile = 8,
                audio = listOf(audio(codec = "truehd", profile = "Dolby Atmos", channels = 8)),
            )

        val restated = listOf(base).describing(selected, "server", "movie").single().source!!

        assertEquals(3840, restated.videoWidth)
        assertEquals(2160, restated.videoHeight)
        assertEquals(60_000_000, restated.bitrateBps)
        assertTrue(restated.dolbyVision)
        assertTrue(restated.dolbyAtmos)
        assertTrue(restated.losslessAudio)
        assertEquals(selected.sizeBytes, restated.sizeBytes)
    }

    @Test
    fun selected_version_is_restated_before_visible_sources_are_ranked() {
        val selectedServer =
            source(
                id = "selected-server",
                itemId = "movie",
                info = sourceInfo(width = 3840, height = 2160, bitrate = 70_000_000),
            )
        val otherServer =
            source(
                id = "other-server",
                info = sourceInfo(width = 1920, height = 1080, bitrate = 15_000_000),
            )
        val selected720p =
            version(
                id = "selected-720",
                width = 1280,
                height = 720,
                bitrate = 5_000_000,
            )

        val visible =
            listOf(selectedServer, otherServer)
                .describing(selected720p, "selected-server", "movie")
                .bestSourcesFirst()

        assertEquals(listOf("other-server", "selected-server"), visible.map { it.serverId })
        assertEquals(1280, visible.last().source?.videoWidth)
    }

    private fun version(
        id: String,
        width: Int? = 3840,
        height: Int? = 2160,
        bitrate: Int? = 50_000_000,
        size: Long? = 40L.gib,
        range: String? = null,
        bitDepth: Int? = 10,
        dvProfile: Int? = null,
        audio: List<AudioTrackInfo> = emptyList(),
    ) = MediaVersion(
        id = id,
        name = id,
        container = "mkv",
        sizeBytes = size,
        bitrateBps = bitrate,
        videoCodec = "hevc",
        videoHeight = height,
        videoRange = range,
        video =
            if (width != null || height != null || dvProfile != null) {
                VideoStreamInfo(
                    width = width,
                    height = height,
                    bitDepth = bitDepth,
                    dolbyProfile = dvProfile,
                )
            } else {
                null
            },
        audioTracks = audio,
    )

    private fun audio(
        codec: String,
        profile: String? = null,
        channels: Int? = null,
        bitrate: Int? = null,
    ) = AudioTrackInfo(
        codec = codec,
        channels = channels?.let { "$it channels" },
        language = "英语",
        profile = profile,
        bitrateBps = bitrate,
        channelCount = channels,
    )

    private fun source(
        id: String,
        name: String = id,
        itemId: String = "item-$id",
        info: SourceInfo,
        reachable: Boolean = true,
    ) = ServerSource(
        serverId = id,
        serverName = name,
        isCurrent = false,
        source = info,
        reachable = reachable,
        itemId = itemId,
    )

    private fun sourceInfo(
        quality: String = "1080P",
        width: Int? = null,
        height: Int? = null,
        bitrate: Int? = null,
        size: Long? = null,
    ) = SourceInfo(
        quality = quality,
        size = null,
        bitrate = null,
        sizeBytes = size,
        videoWidth = width,
        videoHeight = height,
        bitrateBps = bitrate,
    )

    private val Long.gib: Long get() = this * 1024L * 1024L * 1024L
}
