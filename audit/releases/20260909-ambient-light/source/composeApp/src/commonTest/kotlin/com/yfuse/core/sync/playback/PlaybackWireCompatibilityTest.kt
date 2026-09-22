package com.yfuse.core.sync.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackWireCompatibilityTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun legacyReaderIgnoresProgressEpochAndStillKnowsMutationKind() {
        val current =
            PlaybackSyncDocument(
                state =
                    PlaybackStateRecord(
                        mediaKey = "tmdb:1",
                        positionMs = 0L,
                        durationMs = 100_000L,
                        played = false,
                        lastPlayedAtEpochMs = 1_000L,
                        progressEpoch = 3L,
                        deviceId = "phone",
                        revision = 2L,
                        mutationKind = PlaybackMutationKind.AutoProgress,
                    ),
            )

        val wire = json.encodeToString(current)
        val legacy = json.decodeFromString<LegacyPlaybackSyncDocument>(wire)

        assertEquals("tmdb:1", legacy.state.mediaKey)
        assertEquals(0L, legacy.state.positionMs)
        assertEquals(PlaybackMutationKind.AutoProgress, legacy.state.mutationKind)
        assertFalse(wire.contains("ManualRestart"))
    }

    @Test
    fun currentReaderTreatsLegacyDocumentAsGenerationZero() {
        val legacy =
            LegacyPlaybackSyncDocument(
                state =
                    LegacyPlaybackStateRecord(
                        mediaKey = "tmdb:1",
                        positionMs = 70_000L,
                        durationMs = 100_000L,
                        played = false,
                        lastPlayedAtEpochMs = 1_000L,
                        deviceId = "tv",
                        revision = 1L,
                        mutationKind = PlaybackMutationKind.AutoProgress,
                    ),
            )

        val current = json.decodeFromString<PlaybackSyncDocument>(json.encodeToString(legacy))

        assertEquals(0L, current.state.progressEpoch)
        assertEquals(70_000L, current.state.positionMs)
    }

    @Serializable
    private data class LegacyPlaybackSyncDocument(
        val schemaVersion: Int = 1,
        val state: LegacyPlaybackStateRecord,
    )

    @Serializable
    private data class LegacyPlaybackStateRecord(
        val mediaKey: String,
        val aliases: List<String> = emptyList(),
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val played: Boolean = false,
        val lastPlayedAtEpochMs: Long = 0L,
        val deviceId: String,
        val sessionId: String? = null,
        val serverId: String? = null,
        val serverItemId: String? = null,
        val revision: Long = 0L,
        val mutationKind: PlaybackMutationKind = PlaybackMutationKind.AutoProgress,
    )
}
