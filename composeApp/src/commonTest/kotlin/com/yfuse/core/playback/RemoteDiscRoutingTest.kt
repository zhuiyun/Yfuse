package com.yfuse.core.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A remote ISO can only reach the screen through the server.
 *
 * libdvdnav and libbluray address a disc through a device path, which an http URL is not, so
 * mpv cannot be handed `dvd://`/`bd://` for a source on the server — and no demuxer will open
 * a raw `.iso` byte stream either. The whole route therefore depends on the server parsing the
 * main feature, which is why the probe service skips remote disc sources outright.
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
            "mpv cannot address a disc over http; only the server can produce a main feature",
        )
    }

    @Test
    fun a_local_disc_keeps_the_native_route() {
        val decision = planDiscPlayback(remoteDisc(hasServerTranscode = false, local = true))

        assertEquals(PlaybackDiscStrategy.NativeLocalImage, decision.strategy)
        assertTrue(decision.requiresNativeEngine)
    }

    /**
     * The defect this pins.
     *
     * The reconcile effect used to return early unless the probe was Complete, and a remote
     * disc's probe is deliberately Skipped — so `switchToTranscode` was unreachable for exactly
     * the source that cannot play without it. The remote-disc branch has to be evaluated before
     * that gate.
     */
    @Test
    fun the_remote_disc_transcode_switch_is_decided_before_the_probe_completeness_gate() {
        val source = playerRootSource()
        val switchIndex = source.indexOf("remoteDiscNeedsServer && !localState.transcoding")
        val gateIndex = source.indexOf("activeProbeResult.status != PlaybackProbeStatus.Complete")

        assertTrue(switchIndex > 0, "the remote-disc transcode switch is missing")
        assertTrue(gateIndex > 0, "the probe completeness gate is missing")
        assertTrue(
            switchIndex < gateIndex,
            "A remote disc's probe is Skipped by design, so gating its transcode switch on a " +
                "Complete probe makes it unreachable and leaves an .iso URL in the engine",
        )
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

    private fun playerRootSource(): String =
        sequenceOf(
            File("src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt"),
            File("composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate PlayerRoot.kt from ${File(".").absolutePath}")
}
