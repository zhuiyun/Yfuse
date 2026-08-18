package com.yfuse.feature.player

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UhdBluRayServerStreamTest {
    @Test
    fun negotiated_m2ts_main_feature_is_direct_stream_instead_of_server_transcode() {
        val version =
            MediaVersion(
                id = "disc-source",
                name = "UHD Blu-ray",
                container = "bluray",
                sizeBytes = 86_000_000_000L,
                bitrateBps = 82_000_000,
                videoCodec = "hevc",
                videoHeight = 2_160,
                videoRange = "HDR10",
                videoType = "BluRay",
                supportsDirectPlay = false,
                supportsDirectStream = true,
                supportsTranscoding = true,
                directStreamUrl = "/Videos/movie/main-feature.m2ts",
            )

        val selected =
            listOf(version)
                .toPlayerMediaVersions(
                    baseUrl = "http://host:8096",
                    itemId = "movie",
                    token = "tok",
                    negotiatedPlaySessionId = "session-disc",
                ).single()

        assertTrue(selected.discSource)
        assertEquals(PlaybackMethod.DirectStream, selected.playMethod)
        assertTrue("main-feature.m2ts" in selected.url)
    }
}
