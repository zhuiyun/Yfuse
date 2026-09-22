package com.yfuse.core.sync.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackConflictResolverTest {
    @Test
    fun concurrentAutomaticProgressKeepsFurthestPosition() {
        val local = document(positionMs = 40_000L, updatedAt = 1_000L, deviceId = "phone")
        val remote = document(positionMs = 70_000L, updatedAt = 2_000L, deviceId = "tv")

        val merged = PlaybackConflictResolver.merge(local, remote)

        assertEquals(70_000L, merged.state.positionMs)
        assertFalse(merged.state.played)
        assertEquals("tv", merged.state.deviceId)
    }

    @Test
    fun manualUnwatchedBeatsAutomaticFinishedEvenWhenOlder() {
        val local =
            document(
                positionMs = 0L,
                updatedAt = 1_000L,
                deviceId = "phone",
                played = false,
                kind = PlaybackMutationKind.ManualUnwatched,
            )
        val remote =
            document(
                positionMs = 100_000L,
                updatedAt = 2_000L,
                deviceId = "tv",
                played = true,
                kind = PlaybackMutationKind.AutoFinished,
            )

        val merged = PlaybackConflictResolver.merge(local, remote)

        assertFalse(merged.state.played)
        assertEquals(PlaybackMutationKind.ManualUnwatched, merged.state.mutationKind)
    }

    @Test
    fun explicitRestartGenerationBeatsLaterStaleLargerProgress() {
        val restart =
            document(
                positionMs = 0L,
                updatedAt = 5_000L,
                deviceId = "phone",
                sessionId = "new",
                kind = PlaybackMutationKind.AutoProgress,
                progressEpoch = 5L,
            )
        val stale =
            document(
                positionMs = 70_000L,
                updatedAt = 9_000L,
                deviceId = "tv",
                sessionId = "old",
                progressEpoch = 0L,
            )

        val merged = PlaybackConflictResolver.merge(restart, stale)

        assertEquals(0L, merged.state.positionMs)
        assertEquals(5L, merged.state.progressEpoch)
        assertEquals(PlaybackMutationKind.AutoProgress, merged.state.mutationKind)
    }

    @Test
    fun progressAfterRestartAdvancesInsideSameGeneration() {
        val restart =
            document(
                positionMs = 0L,
                updatedAt = 5_000L,
                deviceId = "phone",
                sessionId = "new",
                kind = PlaybackMutationKind.AutoProgress,
                progressEpoch = 5L,
            )
        val progress =
            document(
                positionMs = 8_000L,
                updatedAt = 6_000L,
                deviceId = "phone",
                sessionId = "new",
                kind = PlaybackMutationKind.AutoProgress,
                progressEpoch = 5L,
            )

        val merged = PlaybackConflictResolver.merge(restart, progress)

        assertEquals(8_000L, merged.state.positionMs)
        assertEquals(PlaybackMutationKind.AutoProgress, merged.state.mutationKind)
        assertEquals(5L, merged.state.progressEpoch)
    }

    @Test
    fun laterIndependentSessionWinsOutsideConcurrentWindow() {
        val local =
            document(
                positionMs = 90_000L,
                updatedAt = 1_000L,
                deviceId = "phone",
                sessionId = "a",
            )
        val remote =
            document(
                positionMs = 20_000L,
                updatedAt = 20 * 60_000L,
                deviceId = "tv",
                sessionId = "b",
            )

        val merged = PlaybackConflictResolver.merge(local, remote)

        assertEquals(20_000L, merged.state.positionMs)
        assertEquals("tv", merged.state.deviceId)
    }

    @Test
    fun aliasesHistoryAndNewestPreferenceAreMerged() {
        val local =
            document(
                positionMs = 10_000L,
                updatedAt = 1_000L,
                deviceId = "phone",
                aliases = listOf("imdb:tt1"),
                preference = PlaybackTrackPreference(audioLanguage = "ja", updatedAtEpochMs = 1_000L),
                history = listOf(history("one", 1_000L, 10_000L)),
            )
        val remote =
            document(
                mediaKey = "imdb:tt1",
                positionMs = 20_000L,
                updatedAt = 2_000L,
                deviceId = "tv",
                aliases = listOf("tmdb:1"),
                preference = PlaybackTrackPreference(audioLanguage = "en", updatedAtEpochMs = 2_000L),
                history = listOf(history("two", 2_000L, 20_000L)),
            )

        val merged = PlaybackConflictResolver.merge(local, remote)

        assertTrue("tmdb:1" in merged.state.aliases)
        assertEquals("en", merged.preference?.audioLanguage)
        assertEquals(setOf("one", "two"), merged.history.map { it.sessionId }.toSet())
    }

    private fun document(
        mediaKey: String = "tmdb:1",
        positionMs: Long,
        updatedAt: Long,
        deviceId: String,
        sessionId: String = "session",
        aliases: List<String> = emptyList(),
        played: Boolean = false,
        kind: PlaybackMutationKind = PlaybackMutationKind.AutoProgress,
        progressEpoch: Long = 0L,
        preference: PlaybackTrackPreference? = null,
        history: List<PlaybackHistoryEntry> = emptyList(),
    ) = PlaybackSyncDocument(
        state =
            PlaybackStateRecord(
                mediaKey = mediaKey,
                aliases = aliases,
                positionMs = positionMs,
                durationMs = 100_000L,
                played = played,
                lastPlayedAtEpochMs = updatedAt,
                progressEpoch = progressEpoch,
                deviceId = deviceId,
                sessionId = sessionId,
                revision = updatedAt,
                mutationKind = kind,
            ),
        preference = preference,
        history = history,
    )

    private fun history(
        id: String,
        startedAt: Long,
        endPositionMs: Long,
    ) = PlaybackHistoryEntry(
        sessionId = id,
        startedAtEpochMs = startedAt,
        endPositionMs = endPositionMs,
        deviceId = "device",
    )
}
