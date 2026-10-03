package com.yfuse.core.sync.playback

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackSyncStoreServerDatesTest {
    private var now = 10_000_000L
    private val store = PlaybackSyncStore(MapSettings()) { now }

    /** A local playback the cloud has already accepted: clean, but still this device's own. */
    private fun watchedHere(
        itemId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        val stored =
            store.updatePlayback(
                mediaKey = "emby:$itemId",
                aliases = emptyList(),
                positionMs = positionMs,
                durationMs = durationMs,
                played = false,
                sessionId = "local",
                serverId = "server",
                serverItemId = itemId,
                mutationKind = PlaybackMutationKind.AutoProgress,
                trigger = PlaybackSyncTrigger.Stop,
            )
        store.markUploaded(
            mediaKey = "emby:$itemId",
            aliases = emptyList(),
            serverId = "server",
            entityKey = "entity-$itemId",
            mutationId = stored.mutationId,
            cursor = 1L,
        )
    }

    @Test
    fun the_servers_copy_of_this_playback_never_erases_a_short_episodes_resume_point() {
        watchedHere("e1", positionMs = 40_000L, durationMs = 120_000L)

        // Jellyfin: under five minutes and past 5% reads "played, position 0"; its date is the
        // start of this same playback.
        assertFalse(
            store.absorbServerProgress(
                "server",
                "e1",
                positionMs = 0L,
                played = true,
                lastPlayedAtEpochMs = now - 45_000L,
                durationMs = 120_000L,
            ),
        )

        val kept = store.stateForServerItem("server", "e1")!!
        assertEquals(40_000L, kept.positionMs)
        assertFalse(kept.played)
    }

    @Test
    fun a_later_playback_on_another_client_still_replaces_the_local_record() {
        watchedHere("e1", positionMs = 40_000L, durationMs = 120_000L)

        assertTrue(
            store.absorbServerProgress(
                "server",
                "e1",
                positionMs = 0L,
                played = true,
                lastPlayedAtEpochMs = now + 30 * 60_000L,
            ),
        )

        val replaced = store.stateForServerItem("server", "e1")!!
        assertTrue(replaced.played)
        assertEquals(now + 30 * 60_000L, replaced.lastPlayedAtEpochMs)
    }

    @Test
    fun clocks_a_minute_apart_are_still_the_same_playback() {
        watchedHere("e1", positionMs = 40_000L, durationMs = 120_000L)

        assertFalse(
            store.absorbServerProgress(
                "server",
                "e1",
                positionMs = 0L,
                played = true,
                lastPlayedAtEpochMs = now + 60_000L,
            ),
        )
    }

    @Test
    fun without_a_server_date_only_the_short_item_bookkeeping_is_ignored() {
        watchedHere("short", positionMs = 40_000L, durationMs = 120_000L)
        watchedHere("film", positionMs = 40 * 60_000L, durationMs = 120 * 60_000L)

        assertFalse(store.absorbServerProgress("server", "short", positionMs = 0L, played = true))
        assertTrue(store.absorbServerProgress("server", "film", positionMs = 0L, played = true))

        assertFalse(store.stateForServerItem("server", "short")!!.played)
        assertTrue(store.stateForServerItem("server", "film")!!.played)
    }

    @Test
    fun imported_items_take_the_servers_date_and_never_crowd_out_local_progress() {
        repeat(10) { index ->
            now += 1_000L
            watchedHere("local-$index", positionMs = 30_000L, durationMs = 600_000L)
        }
        val imported =
            (1..1_000).map { index ->
                PlaybackSyncStore.ServerProgressInput(
                    itemId = "server-$index",
                    positionMs = 0L,
                    played = true,
                    lastPlayedAtEpochMs = 1_000_000L + index,
                )
            }

        now += 60_000L
        store.absorbServerProgressBatch("server", imported, store.scopeToken)

        val states = store.statesForServer("server")
        assertEquals(512, states.size)
        assertEquals((9 downTo 0).map { "local-$it" }, states.take(10).map { it.serverItemId })
        assertEquals("server-1000", states[10].serverItemId)
        assertEquals(1_001_000L, states[10].lastPlayedAtEpochMs)
    }

    @Test
    fun an_item_older_than_everything_a_full_store_keeps_is_not_imported() {
        val recent =
            (1..512).map { index ->
                PlaybackSyncStore.ServerProgressInput("recent-$index", 0L, true, lastPlayedAtEpochMs = 5_000_000L + index)
            }
        store.absorbServerProgressBatch("server", recent, store.scopeToken)

        assertFalse(store.seedServerProgressIfAbsent("server", "ancient", 0L, true, lastPlayedAtEpochMs = 1L))
        assertTrue(store.seedServerProgressIfAbsent("server", "fresh", 0L, true, lastPlayedAtEpochMs = 9_000_000L))
        assertEquals("fresh", store.statesForServer("server").first().serverItemId)
    }

    @Test
    fun a_pull_heals_the_date_an_earlier_import_gave_a_record() {
        store.seedServerProgressIfAbsent("server", "movie", positionMs = 0L, played = true)

        assertTrue(
            store.absorbServerProgress("server", "movie", positionMs = 0L, played = true, lastPlayedAtEpochMs = 4_242L),
        )
        assertEquals(4_242L, store.stateForServerItem("server", "movie")!!.lastPlayedAtEpochMs)
        assertFalse(
            store.absorbServerProgress("server", "movie", positionMs = 0L, played = true, lastPlayedAtEpochMs = 4_242L),
        )
    }
}
