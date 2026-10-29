package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackStartPositionTest {
    @Test
    fun syncedExactItemWinsOverStalePortableAliasAtLaunch() {
        val store = PlaybackSyncStore(MapSettings()) { 9_000_000L }
        store.seedServerProgressIfAbsent("server", "item", 5 * 60_000L, false, 1_000L, 3_600_000L)
        store.absorbServerProgress("server", "item", 22 * 60_000L, false, 2_000L)
        store.updatePlayback(
            "tmdb:1",
            emptyList(),
            5 * 60_000L,
            3_600_000L,
            false,
            "component",
            null,
            null,
            PlaybackMutationKind.AutoProgress,
            PlaybackSyncTrigger.Stop,
        )
        assertEquals(
            22 * 60_000L,
            store.authoritativeStartPositionMs(
                "tmdb:1",
                serverId = "server",
                serverItemId = "item",
            ),
        )
    }

    @Test
    fun missingCloudStateLeavesMediaServerPositionUntouched() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }

        assertNull(store.authoritativeStartPositionMs("tmdb:1"))
    }

    @Test
    fun positiveCloudProgressOverridesOlderMediaServerResume() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = listOf("imdb:tt1"),
            positionMs = 70_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "tv",
            serverId = "server-b",
            serverItemId = "item-b",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        assertEquals(
            70_000L,
            store.authoritativeStartPositionMs("imdb:tt1", listOf("tmdb:1")),
        )
    }

    @Test
    fun manualUnwatchedZeroIsAuthoritativeInsteadOfMeaningMissing() {
        var now = 1_000L
        val store = PlaybackSyncStore(MapSettings()) { now++ }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = emptyList(),
            positionMs = 70_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "phone",
            serverId = "server-a",
            serverItemId = "item-a",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        store.markManual(
            mediaKey = "tmdb:1",
            watched = false,
            serverId = "server-a",
            serverItemId = "item-a",
        )

        assertEquals(0L, store.authoritativeStartPositionMs("tmdb:1"))
    }

    @Test
    fun completedProgressResetsOrdinaryLaunchToStart() {
        val store = PlaybackSyncStore(MapSettings()) { 1_000L }
        store.updatePlayback(
            mediaKey = "tmdb:1",
            aliases = emptyList(),
            positionMs = 95_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "tv",
            serverId = "server-b",
            serverItemId = "item-b",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )

        assertEquals(0L, store.authoritativeStartPositionMs("tmdb:1"))
    }
}
