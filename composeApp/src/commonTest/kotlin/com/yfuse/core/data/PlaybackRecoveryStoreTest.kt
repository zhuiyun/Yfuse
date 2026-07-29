package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRecoveryStoreTest {
    @Test
    fun checkpoint_survives_recreation_without_media_url() {
        val settings = MapSettings()
        PlaybackRecoveryStore(settings).record(
            itemId = "episode-7",
            title = "第 7 集",
            serverId = "server-a",
            positionMs = 123_000L,
            durationMs = 2_400_000L,
            engine = "MDK",
            force = true,
        )

        val restored = PlaybackRecoveryStore(settings).snapshot.value

        assertEquals("episode-7", restored?.itemId)
        assertEquals(123_000L, restored?.positionMs)
        assertEquals("server-a", restored?.serverId)
    }

    @Test
    fun clear_removes_persisted_checkpoint() {
        val settings = MapSettings()
        val store = PlaybackRecoveryStore(settings)
        store.record("movie", "Movie", "server", 5_000L, 10_000L, "Exo", true)

        store.clear()

        assertNull(PlaybackRecoveryStore(settings).snapshot.value)
    }
}
