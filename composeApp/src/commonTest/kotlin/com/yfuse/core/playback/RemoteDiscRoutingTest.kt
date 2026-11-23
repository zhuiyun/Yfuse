package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A remote ISO can only reach the screen through the server.
 *
 * libdvdnav and libbluray use local device paths. A remote disc image therefore needs a main
 * feature stream from the server. The probe service skips the remote image itself.
 */
class RemoteDiscRoutingTest {
    @Test
    fun a_remote_disc_with_server_transcode_is_routed_to_the_server() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = true))

        assertEquals(PlaybackDiscStrategy.ServerMainFeature, decision.strategy)
        assertTrue(decision.requiresServerTranscode)
    }

    @Test
    fun a_remote_disc_is_never_routed_to_a_native_engine_when_the_server_can_parse_it() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = true))

        assertTrue(
            !decision.requiresNativeEngine,
            "Remote disc playback requires a main feature stream from the server",
        )
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
