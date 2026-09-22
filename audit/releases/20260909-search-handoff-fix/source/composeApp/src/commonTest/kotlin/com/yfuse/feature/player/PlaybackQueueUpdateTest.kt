package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackQueueUpdateTest {
    @Test
    fun catalog_enrichment_preserves_active_session_with_history_inserted_before_it() {
        val current = item("episode-2")
        val catalog = listOf(item("episode-1"), current.copy(title = "Updated title"), item("episode-3"))
        assertTrue(canUpdatePlaybackQueue(listOf(current), 0, catalog, 1))
        assertEquals(setOf(1), remapPlaybackQueueIndices(setOf(0), listOf(current), catalog))
    }

    @Test
    fun a_changed_source_or_session_is_not_mistaken_for_metadata_enrichment() {
        val current = item("episode-2")
        assertFalse(canUpdatePlaybackQueue(listOf(current), 0, listOf(current.copy(url = "https://media/other")), 0))
        assertFalse(canUpdatePlaybackQueue(listOf(current), 0, listOf(current.copy(playSessionId = "new-session")), 0))
        assertFalse(canUpdatePlaybackQueue(listOf(current), 0, listOf(current.copy(serverId = "other-server")), 0))
        assertFalse(canUpdatePlaybackQueue(listOf(current), 0, listOf(current, current), 0))
    }

    @Test
    fun recovery_flags_follow_media_identity_when_catalog_order_changes() {
        val old = listOf(item("a"), item("b"), item("c"))
        assertEquals(setOf(0, 2), remapPlaybackQueueIndices(setOf(0, 2), old, listOf(old[2], old[1], old[0])))
        assertEquals(emptySet(), remapPlaybackQueueIndices(setOf(1), old, listOf(old[0], old[2])))
    }

    private fun item(id: String) =
        PlayerMediaItem(
            id = id,
            url = "https://media/$id",
            transcodeUrl = "https://media/$id/transcode",
            title = id,
            serverId = "server",
            playSessionId = "session-$id",
        )
}
