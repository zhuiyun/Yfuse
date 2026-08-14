package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchRoomPlaylistTest {
    @Test
    fun authoritative_snapshot_drives_revision_and_entries() {
        val sent = mutableListOf<WatchWireMessage>()
        val controller = WatchRoomPlaylistController { sent += it; true }

        controller.applySnapshot(
            WatchWireMessage(
                type = "welcome",
                capabilities = listOf(WatchProtocol.CAPABILITY_ROOM_PLAYLIST),
                playlist = listOf(entry("a", "tmdb:1", "第一集")),
                playlistRevision = 4L,
            ),
        )

        assertTrue(controller.state.value.supported)
        assertEquals(4L, controller.state.value.revision)
        assertEquals(listOf("第一集"), controller.state.value.entries.map { it.title })

        assertTrue(controller.add("tmdb:2", "第二集"))
        assertEquals(1, sent.size)
        assertEquals("playlistAdd", sent.single().type)
        assertEquals(4L, sent.single().playlistRevision)
        assertTrue(controller.state.value.mutationPending)

        controller.applySnapshot(
            WatchWireMessage(
                type = "roomUpdate",
                playlist =
                    listOf(
                        entry("a", "tmdb:1", "第一集"),
                        entry("b", "tmdb:2", "第二集"),
                    ),
                playlistRevision = 5L,
            ),
        )

        assertEquals(5L, controller.state.value.revision)
        assertFalse(controller.state.value.mutationPending)
        assertEquals(2, controller.state.value.entries.size)
    }

    @Test
    fun only_one_mutation_is_sent_before_server_ack() {
        val sent = mutableListOf<WatchWireMessage>()
        val controller = WatchRoomPlaylistController { sent += it; true }
        controller.applySnapshot(
            WatchWireMessage(
                type = "welcome",
                capabilities = listOf(WatchProtocol.CAPABILITY_ROOM_PLAYLIST),
                playlist = listOf(entry("a", "tmdb:1", "第一集")),
                playlistRevision = 1L,
            ),
        )

        assertTrue(controller.remove("a"))
        assertFalse(controller.add("tmdb:2", "第二集"))
        assertEquals(1, sent.size)
    }

    @Test
    fun stale_error_replaces_local_snapshot_with_server_truth() {
        val controller = WatchRoomPlaylistController { true }
        controller.applySnapshot(
            WatchWireMessage(
                type = "welcome",
                capabilities = listOf(WatchProtocol.CAPABILITY_ROOM_PLAYLIST),
                playlist = listOf(entry("a", "tmdb:1", "旧项目")),
                playlistRevision = 2L,
            ),
        )
        assertTrue(controller.remove("a"))

        controller.applyServerError(
            WatchWireMessage(
                type = "error",
                errorCode = "playlist_stale",
                message = "播放列表已更新，请基于最新版本重试",
                playlist = listOf(entry("c", "tmdb:3", "其他成员新增")),
                playlistRevision = 3L,
            ),
        )

        val state = controller.state.value
        assertEquals(3L, state.revision)
        assertEquals("其他成员新增", state.entries.single().title)
        assertFalse(state.mutationPending)
        assertNotNull(state.error)
    }

    @Test
    fun invalid_destination_is_rejected_without_sending() {
        var sends = 0
        val controller = WatchRoomPlaylistController { sends++; true }
        controller.applySnapshot(
            WatchWireMessage(
                type = "welcome",
                capabilities = listOf(WatchProtocol.CAPABILITY_ROOM_PLAYLIST),
                playlist = listOf(entry("a", "tmdb:1", "第一集")),
                playlistRevision = 0L,
            ),
        )

        assertFalse(controller.move("a", 2))
        assertEquals(0, sends)
        assertNotNull(controller.state.value.error)
    }

    private fun entry(
        id: String,
        mediaKey: String,
        title: String,
    ) = WatchWirePlaylistEntry(id = id, mediaKey = mediaKey, title = title)
}
