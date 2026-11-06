package com.yfuse.core2.android

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.core.playback.PlayerCustomHeaders
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import com.yfuse.feature.player.effectivePlaybackMethod
import com.yfuse.feature.player.startsWithServerTranscode
import com.yfuse.feature.player.withForcedServerTranscode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YCoreServerTranscodeRoutingTest {
    @Test
    fun approved_output_enters_ycore_and_discards_the_original_format_and_cache_identity() {
        val item = fixture().copy(serverTranscodeSupported = true).withForcedServerTranscode("decoder recovery")
        assertTrue(listOf(item).canUseCore2Trial(0))
        assertNull(listOf(item).core2NativeBaselineBlockReason(0))
        val mapped = listOf(item).toCore2MediaItems("test", cacheMaximumBytes = 1024, appVersion = { "1" }).single()
        assertEquals(item.transcodeUrl, mapped.uri)
        assertNull(mapped.mimeType)
        assertNull(mapped.sourceHints)
        assertNull(mapped.cacheIdentity)
        assertEquals(0L, mapped.cacheMaximumBytes)
        assertFalse(mapped.allowNextItemPreparation)
        assertTrue(item.requiresYCoreAdaptiveProxy(mapped.uri))
    }

    @Test
    fun an_unapproved_spare_url_cannot_replace_the_original_stream() {
        val original = fixture()
        val item = original.withForcedServerTranscode("recovery")
        assertFalse(item.startsWithServerTranscode())
        assertEquals(original.url, listOf(item).toCore2MediaItems("test", appVersion = { "1" }).single().uri)
        assertNotNull(listOf(item).core2NativeBaselineBlockReason(0))
    }

    @Test
    fun converted_disc_uses_the_real_output_url_without_raw_disc_routing() {
        val item =
            fixture(
                disc = true,
            ).copy(serverTranscodeSupported = true).withForcedServerTranscode("viewer", byViewer = true)
        assertNull(listOf(item).core2NativeBaselineBlockReason(0))
        val mapped = listOf(item).toCore2MediaItems("test", appVersion = { "1" }).single()
        assertEquals(item.transcodeUrl, mapped.uri)
        assertNull(mapped.disc)
    }

    @Test
    fun dolby_remains_local_until_explicit_approved_conversion() {
        val item = fixture(dolby = true).copy(playMethod = PlaybackMethod.Transcode)
        assertFalse(item.startsWithServerTranscode())
        assertEquals(item.url, listOf(item).toCore2MediaItems("test", appVersion = { "1" }).single().uri)
        val selected = item.withForcedServerTranscode("viewer", byViewer = true)
        assertTrue(selected.startsWithServerTranscode())
        assertNull(listOf(selected).core2NativeBaselineBlockReason(0))
        assertEquals(
            selected.transcodeUrl,
            listOf(selected).toCore2MediaItems("test", appVersion = { "1" }).single().uri,
        )
    }

    @Test
    fun drm_cannot_be_forced_into_clear_server_output_or_reported_as_transcoding() {
        val drm = PlaybackDrmConfiguration(scheme = PlaybackDrmScheme.Widevine)
        for (atItem in listOf(true, false)) {
            val base = fixture()
            val item =
                base.copy(
                    playMethod = PlaybackMethod.Transcode,
                    forcedTranscodeByViewer = true,
                    drmConfiguration = drm.takeIf { atItem },
                    versions = base.versions.map { it.copy(drmConfiguration = drm.takeUnless { atItem }) },
                )
            assertFalse(item.startsWithServerTranscode())
            assertEquals(PlaybackMethod.DirectPlay, item.effectivePlaybackMethod())
            val mapped = listOf(item).toCore2MediaItems("test", appVersion = { "1" }).single()
            assertEquals(item.url, mapped.uri)
            assertEquals(drm, mapped.drmConfiguration)
        }
    }

    @Test
    fun approval_does_not_make_an_unknown_output_protocol_supported() {
        val item = fixture().copy(transcodeUrl = "unimplemented://output", playMethod = PlaybackMethod.Transcode)
        assertTrue(item.startsWithServerTranscode())
        assertFalse(listOf(item).canUseCore2Trial(0))
        assertNotNull(listOf(item).core2NativeBaselineBlockReason(0))
    }

    @Test
    fun conversion_does_not_forward_original_file_credentials_to_a_different_server() {
        val original =
            fixture().copy(
                serverTranscodeSupported = true,
                customHeaders = PlayerCustomHeaders(mapOf("X-Private" to "original-only")),
                transportCredentials = YTransportCredentials.UsernamePassword("fixture-user", "fixture-password"),
            )
        val direct = listOf(original).toCore2MediaItems("test", appVersion = { "1" }).single()
        assertEquals("original-only", direct.headers["X-Private"])
        assertNotNull(direct.transportCredentials)
        val converted = original.withForcedServerTranscode("viewer", byViewer = true)
        val mapped = listOf(converted).toCore2MediaItems("test", appVersion = { "1" }).single()
        assertNull(mapped.headers["X-Private"])
        assertNull(mapped.transportCredentials)
        assertTrue(mapped.originBoundHeaderNames.isEmpty())
    }

    private fun fixture(
        disc: Boolean = false,
        dolby: Boolean = false,
    ): PlayerMediaItem {
        val version =
            PlayerMediaVersion(
                "v",
                "Source",
                "",
                "http://origin/video.avi",
                "http://server/output.m3u8",
                "http://server/output.m3u8",
                container = if (disc) "iso" else "avi",
                sourceVideoCodec = "Theora",
                discSource = disc,
                dolbyVision = dolby,
                dolbyProfile = 9.takeIf { dolby },
                sourceWidth = 7680,
                sourceHeight = 4320,
            )
        return PlayerMediaItem(
            "movie",
            version.url,
            version.transcodeUrl,
            "Movie",
            serverId = "server",
            versions = listOf(version),
            versionId = "v",
            rawDiscUri = "file:///original.iso".takeIf { disc },
        )
    }
}
