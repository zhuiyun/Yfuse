package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiscPolicyTest {
    @Test
    fun bdmv_is_detected_from_container_or_label() {
        assertEquals(PlaybackDiscKind.Bdmv, detectPlaybackDiscKind("bdmv"))
        assertEquals(PlaybackDiscKind.Bdmv, detectPlaybackDiscKind(null, "Movie/BDMV/index.bdmv"))
    }

    @Test
    fun remote_iso_prefers_server_main_feature_parsing() {
        val decision =
            planDiscPlayback(
                probe(
                    kind = PlaybackDiscKind.Iso,
                    local = false,
                    transcode = true,
                ),
            )

        assertEquals(PlaybackDiscStrategy.ServerMainFeature, decision.strategy)
        assertTrue(decision.requiresServerTranscode)
        assertFalse(decision.requiresNativeEngine)
    }

    @Test
    fun local_iso_without_server_uses_native_ffmpeg_path() {
        val decision =
            planDiscPlayback(
                probe(
                    kind = PlaybackDiscKind.Iso,
                    local = true,
                    transcode = false,
                ),
            )

        assertEquals(PlaybackDiscStrategy.NativeLocalImage, decision.strategy)
        assertTrue(decision.requiresNativeEngine)
    }

    private fun probe(
        kind: PlaybackDiscKind,
        local: Boolean,
        transcode: Boolean,
    ) = PlaybackMediaProbe(
        container = kind.name,
        discSource = true,
        source = PlaybackSourceRequirements(false, false, null),
        hasServerTranscode = transcode,
        discKind = kind,
        localSource = local,
    )
}
