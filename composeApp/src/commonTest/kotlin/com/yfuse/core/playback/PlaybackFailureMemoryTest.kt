package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailureMemoryTest {
    @Test
    fun failure_classifier_separates_transport_from_backend_failures() {
        assertEquals(PlaybackFailureKind.Authorization, classifyPlaybackFailure("HTTP 401 Unauthorized"))
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure("socket timeout"))
        assertEquals(PlaybackFailureKind.Container, classifyPlaybackFailure("unrecognized input format"))
        assertEquals(PlaybackFailureKind.Decoder, classifyPlaybackFailure("MediaCodec decoder failed"))
        assertEquals(PlaybackFailureKind.Renderer, classifyPlaybackFailure("failed to create GPU surface"))
        assertEquals(PlaybackFailureKind.AudioSink, classifyPlaybackFailure("AudioTrack init failed"))
    }

    @Test
    fun network_failures_never_blacklist_a_player_engine() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)

        memory.record("MKV|H264", PlayerEngine.Exo, PlaybackFailureKind.Network)

        assertTrue(memory.excludedEngines("MKV|H264").isEmpty())
        assertFalse(PlaybackFailureKind.Network.allowsBackendFallback)
        assertFalse(PlaybackFailureKind.Authorization.allowsBackendFallback)
        assertTrue(PlaybackFailureKind.Decoder.allowsBackendFallback)
    }

    @Test
    fun repeated_decoder_failure_is_scoped_to_one_capability_signature() {
        val memory = PlaybackFailureMemory(failureThreshold = 2)

        repeat(2) {
            memory.record("MKV|HEVC|2160", PlayerEngine.Exo, PlaybackFailureKind.Decoder)
        }

        assertTrue(PlayerEngine.Exo in memory.excludedEngines("MKV|HEVC|2160"))
        assertFalse(PlayerEngine.Exo in memory.excludedEngines("MP4|H264|1080"))
    }

    @Test
    fun confirmed_playback_clears_a_transient_device_quirk() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        memory.record("MKV|HEVC", PlayerEngine.Mdk, PlaybackFailureKind.Renderer)

        memory.recordSuccess("MKV|HEVC", PlayerEngine.Mdk)

        assertTrue(memory.excludedEngines("MKV|HEVC").isEmpty())
    }

    @Test
    fun memory_evicts_old_signatures_instead_of_growing_without_bound() {
        val memory = PlaybackFailureMemory(failureThreshold = 1, maxSignatures = 2)
        memory.record("first", PlayerEngine.Exo, PlaybackFailureKind.Decoder)
        memory.record("second", PlayerEngine.Exo, PlaybackFailureKind.Decoder)
        memory.record("third", PlayerEngine.Exo, PlaybackFailureKind.Decoder)

        assertEquals(0, memory.failureCount("first", PlayerEngine.Exo))
        assertEquals(1, memory.failureCount("second", PlayerEngine.Exo))
        assertEquals(1, memory.failureCount("third", PlayerEngine.Exo))
    }

    @Test
    fun persisted_quirks_restore_and_expire_after_the_cooling_period() {
        var now = 10_000L
        var persisted = emptyList<PlaybackFailureRecord>()
        val first =
            PlaybackFailureMemory(
                failureThreshold = 2,
                failureTtlMs = 1_000L,
                nowEpochMs = { now },
                onChanged = { persisted = it },
            )
        repeat(2) {
            first.record("MKV|HEVC|2160", PlayerEngine.Exo, PlaybackFailureKind.Decoder)
        }

        val restored =
            PlaybackFailureMemory(
                failureThreshold = 2,
                failureTtlMs = 1_000L,
                nowEpochMs = { now },
                initialRecords = persisted,
                onChanged = { persisted = it },
            )

        assertTrue(PlayerEngine.Exo in restored.excludedEngines("MKV|HEVC|2160"))
        now += 1_001L
        assertTrue(restored.excludedEngines("MKV|HEVC|2160").isEmpty())
        assertTrue(persisted.isEmpty())
    }
}
