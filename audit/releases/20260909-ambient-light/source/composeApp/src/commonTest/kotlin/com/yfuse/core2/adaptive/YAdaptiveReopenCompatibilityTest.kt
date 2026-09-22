package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals

class YAdaptiveReopenCompatibilityTest {
    @Test
    fun different_dash_init_is_reopen_eligible_but_still_not_seamless() {
        val selected = dash("high", "high-init.mp4")
        val low = dash("low", "low-init.mp4")
        val changedKey =
            low.copy(id = "key-change", contentProtections = listOf(YDashContentProtection("different-key")))
        val wrongTime =
            low.copy(id = "wrong-time", segmentTemplate = low.segmentTemplate?.copy(presentationTimeOffset = 1L))
        val candidates = listOf(selected, low, changedKey, wrongTime)
        assertEquals(listOf("high", "low"), compatibleYDashReopenRepresentations(selected, candidates).map { it.id })
        val manifest = YDashManifest(false, mediaPresentationDurationUs = 10_000_000L, representations = candidates)
        assertEquals(listOf("high"), alignYDashSwitchingRepresentations(manifest, "high").map { it.id })
    }

    @Test
    fun hls_reopen_keeps_timing_and_encryption_while_allowing_a_new_map() {
        val high = hls("high", "high-init.mp4")
        val low = hls("low", "low-init.mp4")
        val shifted =
            low.copy(
                variant = low.variant.copy(id = "shifted"),
                playlist =
                    low.playlist.copy(
                        segments = low.playlist.segments.map { it.copy(startTimeUs = it.startTimeUs + 1L) },
                    ),
            )
        assertEquals(
            listOf("high", "low"),
            compatibleYHlsReopenVariants(high, listOf(high, low, shifted)).map { it.variant.id },
        )
        assertEquals(
            listOf("high"),
            alignYHlsVariantSegments(listOf(high, low), "high").first().resources.map { it.variant.id },
        )
    }

    private fun dash(
        id: String,
        initialization: String,
    ) = YDashRepresentation(
        id = id,
        baseUri = "https://example.test/",
        bandwidthBitsPerSecond = if (id == "high") 4_000_000L else 1_000_000L,
        contentType = YDashContentType.Video,
        mimeType = "video/mp4",
        codecs = listOf("avc1.640028"),
        segmentTemplate = YDashSegmentTemplate(initialization, "s-${'$'}Number${'$'}.m4s", duration = 2L),
    )

    private fun hls(
        id: String,
        initialization: String,
    ): YHlsVariantMediaPlaylist =
        YHlsVariantMediaPlaylist(
            YAdaptiveVariant(id, "https://example.test/$id.m3u8", 1_000_000L, codecs = listOf("avc1.640028")),
            parseYHlsPlaylist(
                """
                #EXTM3U
                #EXT-X-TARGETDURATION:2
                #EXT-X-MAP:URI="$initialization"
                #EXTINF:2,
                $id-0.m4s
                #EXTINF:2,
                $id-1.m4s
                #EXT-X-ENDLIST
                """.trimIndent(),
                "https://example.test/$id.m3u8",
            ) as YHlsPlaylist.Media,
        )
}
