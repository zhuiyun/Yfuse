package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unresolved remote disc images stay on the native local block-I/O / libbluray / FFmpeg pipeline
 * instead of automatically delegating to server transcoding.
 */
class RemoteDiscRoutingTest {
    @Test
    fun a_remote_disc_with_server_transcode_still_stays_on_native_pipeline() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = true))

        assertEquals(PlaybackDiscStrategy.NativeRemoteFallback, decision.strategy)
        assertFalse(decision.requiresServerTranscode)
        assertTrue(decision.requiresNativeEngine)
    }

    @Test
    fun a_remote_disc_preserves_original_streams_without_forcing_server_transcode() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = true))

        assertTrue(decision.preservesOriginalStreams)
    }

    @Test
    fun a_local_disc_keeps_the_native_route() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = false, local = true))

        assertEquals(PlaybackDiscStrategy.NativeLocalImage, decision.strategy)
        assertTrue(decision.requiresNativeEngine)
    }

    private fun remoteDisc(
        hasServerTranscode: Boolean,
        local: Boolean = false,
    ) = PlaybackMediaProbe(
        container = "ISO",
        discSource = true,
        source = PlaybackSourceRequirements(false, false, null),
        hasServerTranscode = hasServerTranscode,
        discKind = PlaybackDiscKind.Iso,
        localSource = local,
    )
}
