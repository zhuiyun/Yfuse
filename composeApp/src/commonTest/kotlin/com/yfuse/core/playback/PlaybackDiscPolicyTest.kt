package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiscPolicyTest {
    @Test
    fun bdmv_entry_path_is_normalized_to_the_disc_root_case_insensitively() {
        assertEquals("/media/Movie", bluRayDiscRoot("/media/Movie/bdmv/index.bdmv"))
        assertEquals("/media/Movie", bluRayDiscRoot("/media/Movie/BDMV"))
    }

    @Test
    fun bdmv_is_detected_from_container_or_label() {
        assertEquals(PlaybackDiscKind.Bdmv, detectPlaybackDiscKind("bdmv"))
        assertEquals(PlaybackDiscKind.Bdmv, detectPlaybackDiscKind(null, "Movie/BDMV/index.bdmv"))
    }

    @Test
    fun server_resolved_bluray_main_feature_preserves_original_streams() {
        val decision =
            planDiscPlayback(
                probe(
                    kind = PlaybackDiscKind.BluRay,
                    local = false,
                    transcode = true,
                    resolved = true,
                ),
            )

        assertEquals(PlaybackDiscStrategy.ServerResolvedLinear, decision.strategy)
        assertFalse(decision.requiresServerTranscode)
        assertFalse(decision.requiresNativeEngine)
        assertTrue(decision.preservesOriginalStreams)
    }

    @Test
    fun remote_iso_prefers_server_main_feature_parsing_when_no_linear_stream_exists() {
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
        assertFalse(decision.preservesOriginalStreams)
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
        assertTrue(decision.preservesOriginalStreams)
    }

    @Test
    fun iso_directory_descriptors_identify_dvd_and_bluray_images() {
        assertEquals(
            PlaybackDiscKind.Dvd,
            detectPlaybackDiscImageKind("padding VIDEO_TS.IFO padding".encodeToByteArray()),
        )
        assertEquals(
            PlaybackDiscKind.BluRay,
            detectPlaybackDiscImageKind("padding BDMV/PLAYLIST/00001.MPLS".encodeToByteArray()),
        )
        assertEquals(
            PlaybackDiscKind.Iso,
            detectPlaybackDiscImageKind("ordinary iso without a movie layout".encodeToByteArray()),
        )
    }

    @Test
    fun generic_iso_requires_positive_bluray_evidence_before_libbluray_routing() {
        assertFalse(isConfirmedBluRaySource(PlaybackDiscKind.Iso, "Movie.iso"))
        assertFalse(isConfirmedBluRaySource(PlaybackDiscKind.Dvd, "DVD ISO"))
        assertTrue(isConfirmedBluRaySource(PlaybackDiscKind.Iso, "UHD Blu-ray ISO"))
        assertTrue(isConfirmedBluRaySource(PlaybackDiscKind.BluRay, null))
        assertTrue(isConfirmedBluRaySource(PlaybackDiscKind.Bdmv, "Movie/BDMV"))
    }

    private fun probe(
        kind: PlaybackDiscKind,
        local: Boolean,
        transcode: Boolean,
        resolved: Boolean = false,
    ) = PlaybackMediaProbe(
        container = kind.name,
        discSource = true,
        source = PlaybackSourceRequirements(false, false, null),
        hasServerTranscode = transcode,
        discKind = kind,
        localSource = local,
        discMainFeatureResolved = resolved,
    )
}
