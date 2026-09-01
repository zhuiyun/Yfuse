package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YAdaptiveBitrateTest {
    @Test
    fun dash_switching_ladder_excludes_different_init_codec_and_timeline() {
        val shared =
            YDashSegmentTemplate(
                initialization = "shared-init.mp4",
                media = "video-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s",
                timescale = 1_000L,
                duration = 2_000L,
            )
        fun representation(
            id: String,
            bitrate: Long,
            codecs: List<String> = listOf("avc1.640028"),
            template: YDashSegmentTemplate = shared,
        ) = YDashRepresentation(
            id = id,
            baseUri = "https://media.example.test/",
            bandwidthBitsPerSecond = bitrate,
            contentType = YDashContentType.Video,
            mimeType = "video/mp4",
            codecs = codecs,
            segmentTemplate = template,
        )
        val manifest =
            YDashManifest(
                isLive = false,
                mediaPresentationDurationUs = 20_000_000L,
                representations =
                    listOf(
                        representation("low", 800_000L),
                        representation("selected", 2_000_000L),
                        representation("hevc", 1_500_000L, codecs = listOf("hvc1.2.4.L120")),
                        representation(
                            "different-init",
                            3_000_000L,
                            template = shared.copy(initialization = "other-init.mp4"),
                        ),
                        representation(
                            "different-timeline",
                            4_000_000L,
                            template = shared.copy(duration = 4_000L),
                        ),
                    ),
            )

        assertEquals(
            listOf("low", "selected"),
            alignYDashSwitchingRepresentations(manifest, "selected").map { it.id },
        )
    }

    private val variants =
        listOf(
            variant("low", 500_000L, 640, 360),
            variant("mid", 1_500_000L, 1280, 720),
            variant("high", 4_000_000L, 3840, 2160),
        )

    @Test
    fun estimator_smooths_samples_without_accepting_zero_duration() {
        val estimator = YAdaptiveBandwidthEstimator(previousWeightPermille = 500)

        assertEquals(0L, estimator.addSample(bytes = 1_000L, durationMs = 0L))
        assertEquals(8_000_000L, estimator.addSample(bytes = 1_000_000L, durationMs = 1_000L))
        assertEquals(6_000_000L, estimator.addSample(bytes = 500_000L, durationMs = 1_000L))
    }

    @Test
    fun selector_downshifts_immediately_but_requires_buffer_for_upgrade() {
        val lowBuffer =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond = 8_000_000L,
                bufferedDurationUs = 1_000_000L,
            )
        assertEquals("low", YAdaptiveVariantSelector.select(variants, lowBuffer, "mid").id)

        val recovering = lowBuffer.copy(bufferedDurationUs = 5_000_000L)
        assertEquals("mid", YAdaptiveVariantSelector.select(variants, recovering, "mid").id)

        val healthy = lowBuffer.copy(bufferedDurationUs = 12_000_000L)
        assertEquals("high", YAdaptiveVariantSelector.select(variants, healthy, "mid").id)
    }

    @Test
    fun device_resolution_and_metered_budget_bound_the_selection() {
        val conditions =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond = 8_000_000L,
                bufferedDurationUs = 12_000_000L,
                maximumWidth = 1920,
                maximumHeight = 1080,
                metered = true,
            )

        assertEquals("mid", YAdaptiveVariantSelector.select(variants, conditions).id)
    }

    @Test
    fun hls_alignment_keeps_only_decoder_and_timeline_compatible_segments() {
        val selected = variant("selected", 2_000_000L, 1920, 1080)
        val lower = variant("lower", 1_000_000L, 1280, 720)
        val differentCodec =
            variant("hevc", 800_000L, 1280, 720).copy(codecs = listOf("hvc1.1.6.L93.B0", "mp4a.40.2"))
        val selectedMedia = media(segment(sequence = 10L, uri = "selected-10.ts"))
        val lowerMedia = media(segment(sequence = 10L, uri = "lower-10.ts"))
        val misalignedMedia = media(segment(sequence = 10L, uri = "hevc-10.ts", durationUs = 5_500_000L))

        val aligned =
            alignYHlsVariantSegments(
                variants =
                    listOf(
                        YHlsVariantMediaPlaylist(selected, selectedMedia),
                        YHlsVariantMediaPlaylist(lower, lowerMedia),
                        YHlsVariantMediaPlaylist(differentCodec, misalignedMedia),
                    ),
                selectedVariantId = selected.id,
            ).single()

        assertEquals(listOf("lower", "selected"), aligned.resources.map { it.variant.id }.sorted())
        assertTrue(aligned.resources.none { it.uri == "hevc-10.ts" })
    }

    @Test
    fun hls_alignment_rejects_range_or_encryption_changes() {
        val selected = variant("selected", 2_000_000L, 1920, 1080)
        val lower = variant("lower", 1_000_000L, 1280, 720)
        val reference =
            segment(sequence = 10L, uri = "selected.ts").copy(
                byteRange = YAdaptiveByteRange(1_024L, 0L),
                encryption = YAdaptiveEncryption(YAdaptiveEncryptionMethod.Aes128, "https://key.test/common"),
            )
        val changed =
            reference.copy(
                uri = "lower.ts",
                byteRange = YAdaptiveByteRange(2_048L, 0L),
            )
        val aligned =
            alignYHlsVariantSegments(
                listOf(
                    YHlsVariantMediaPlaylist(selected, media(reference)),
                    YHlsVariantMediaPlaylist(lower, media(changed)),
                ),
                selected.id,
            ).single()

        assertEquals(listOf("selected"), aligned.resources.map { it.variant.id })
    }

    @Test
    fun hls_alignment_never_switches_between_dolby_profiles_or_compatibility_brands() {
        val selected =
            variant("dv84", 2_000_000L, 1920, 1080).copy(
                codecs = listOf("hvc1.2.4.L120.B0"),
                supplementalCodecs = listOf("dvh1.08.07/db4h"),
            )
        val differentBrand =
            variant("dv81", 1_500_000L, 1920, 1080).copy(
                codecs = listOf("hvc1.2.4.L120.B0"),
                supplementalCodecs = listOf("dvh1.08.06/db1p"),
            )
        val differentProfile =
            variant("dv5", 1_000_000L, 1920, 1080).copy(
                codecs = listOf("dvh1.05.06"),
            )

        val aligned =
            alignYHlsVariantSegments(
                listOf(
                    YHlsVariantMediaPlaylist(selected, media(segment(10L, "dv84.ts"))),
                    YHlsVariantMediaPlaylist(differentBrand, media(segment(10L, "dv81.ts"))),
                    YHlsVariantMediaPlaylist(differentProfile, media(segment(10L, "dv5.ts"))),
                ),
                selected.id,
            ).single()

        assertEquals(listOf("dv84"), aligned.resources.map { it.variant.id })
    }

    private fun variant(
        id: String,
        bandwidth: Long,
        width: Int,
        height: Int,
    ) = YAdaptiveVariant(
        id = id,
        uri = "https://media.example.test/$id.m3u8",
        bandwidthBitsPerSecond = bandwidth,
        width = width,
        height = height,
        codecs = listOf("avc1.640028", "mp4a.40.2"),
    )

    private fun media(vararg segments: YAdaptiveSegment) =
        YHlsPlaylist.Media(
            isLive = false,
            mediaSequence = segments.first().sequence,
            targetDurationUs = 6_000_000L,
            segments = segments.toList(),
        )

    private fun segment(
        sequence: Long,
        uri: String,
        durationUs: Long = 6_000_000L,
    ) = YAdaptiveSegment(
        sequence = sequence,
        uri = uri,
        startTimeUs = 0L,
        durationUs = durationUs,
    )
}
