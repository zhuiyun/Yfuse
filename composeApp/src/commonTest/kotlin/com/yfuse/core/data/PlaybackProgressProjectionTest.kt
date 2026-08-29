package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.UserDataDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackProgressProjectionTest {
    private val server = SavedServer("server-a", "http://host", "A", "user", "User", "token")

    @Test
    fun disabledProgressRejectsEveryRemoteProgressFieldButKeepsFavorite() {
        val projection =
            PlaybackProgressProjection(
                localStore = PlaybackSyncStore(MapSettings()),
                progressSyncEnabled = { false },
            )
        val projected =
            projection.project(
                server,
                item(
                    userData =
                        UserDataDto(
                            PlayedPercentage = 88.0,
                            PlaybackPositionTicks = 9_000_000L,
                            LastPlayedDate = "2026-08-29T01:02:03Z",
                            Played = true,
                            IsFavorite = true,
                        ),
                ),
            )

        assertEquals(false, projected.UserData?.Played)
        assertNull(projected.UserData?.PlayedPercentage)
        assertNull(projected.UserData?.PlaybackPositionTicks)
        assertNull(projected.UserData?.LastPlayedDate)
        assertTrue(projected.UserData?.IsFavorite == true)
    }

    @Test
    fun disabledProgressUsesOnlyTheMatchingDeviceLocalRecord() {
        val store = PlaybackSyncStore(MapSettings()) { 12_345L }
        store.updatePlayback(
            mediaKey = "tmdb:10",
            aliases = listOf("emby:item-1"),
            positionMs = 25_000L,
            durationMs = 100_000L,
            played = false,
            sessionId = "session",
            serverId = server.id,
            serverItemId = "item-1",
            mutationKind = PlaybackMutationKind.AutoProgress,
            trigger = PlaybackSyncTrigger.Periodic,
        )
        val projection = PlaybackProgressProjection(store) { false }

        val projected = projection.project(server, item())

        assertEquals(false, projected.UserData?.Played)
        assertEquals(25.0, projected.UserData?.PlayedPercentage)
        assertEquals(250_000_000L, projected.UserData?.PlaybackPositionTicks)
        assertNull(projected.UserData?.LastPlayedDate)
    }

    @Test
    fun enabledProgressLeavesServerUserDataUntouched() {
        val remote = UserDataDto(PlayedPercentage = 42.0, PlaybackPositionTicks = 42L, Played = true)
        val projection = PlaybackProgressProjection(progressSyncEnabled = { true })

        assertEquals(remote, projection.project(server, item(remote)).UserData)
    }

    private fun item(userData: UserDataDto? = null) =
        BaseItemDto(
            Id = "item-1",
            Type = "Movie",
            RunTimeTicks = 1_000_000_000L,
            UserData = userData,
            ProviderIds = mapOf("Tmdb" to "10"),
        )
}
