package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MpvPendingSeekTest {
    @Test fun the_viewers_latest_seek_replaces_the_resume_position_including_zero() {
        for (target in listOf(0L, 60000L)) {
            val seek = MpvPendingSeek(1200000L)
            seek.updateIfPending(target)
            assertEquals(target, seek.take())
            assertNull(seek.take())
            seek.updateIfPending(5000L)
            assertNull(seek.take())
        }
    }

    @Test fun a_queued_predecessor_loaded_or_eof_cannot_consume_the_new_loads_seek() {
        val gate = MpvVideoReadinessGate()
        val seek = MpvPendingSeek(1200000L)
        gate.onStartFile()
        gate.onFileLoaded()
        gate.onLoadRequested()
        seek.request(60000L)
        assertFalse(gate.acceptsEndOfFile())
        if (gate.onFileLoaded()) seek.take()
        assertEquals(60000L, seek.take())
    }

    @Test fun an_entire_queued_start_loaded_pair_must_not_consume_a_new_files_seek() {
        val gate = MpvVideoReadinessGate()
        val seek = MpvPendingSeek(1200000L)
        gate.onLoadRequested()
        gate.bindNativeIdentity(2L, "https://media.test/b.mp4")
        seek.request(60000L)
        val old = MpvNativeFileIdentity(1L, "https://media.test/a.mp4")
        assertFalse(gate.onStartFile(old))
        assertFalse(gate.onFileLoaded(old))
        assertFalse(gate.acceptsEndOfFile(old))
        val current = MpvNativeFileIdentity(2L, "https://media.test/b.mp4")
        kotlin.test.assertTrue(gate.onStartFile(current))
        assertFalse(gate.onFileLoaded(current, demuxerReady = false))
        kotlin.test.assertTrue(gate.onFileLoaded(current))
        assertEquals(60000L, seek.take())
    }

    @Test fun native_playlist_children_are_accepted_only_for_the_requested_parent_and_newer_id() {
        val gate = MpvVideoReadinessGate()
        gate.onLoadRequested()
        gate.bindNativeIdentity(10L, "https://media.test/list.m3u")
        val child = MpvNativeFileIdentity(11L, "https://media.test/part.mp4", "https://media.test/list.m3u")
        kotlin.test.assertTrue(gate.onStartFile(child))
        kotlin.test.assertTrue(gate.onFileLoaded(child))
        gate.onLoadRequested()
        gate.bindNativeIdentity(12L, "https://media.test/list.m3u")
        assertFalse(gate.onStartFile(child), "A same-URL playlist predecessor still belongs to the older request")
    }

    @Test fun normalized_file_uris_match_the_native_local_path() {
        kotlin.test.assertTrue(mpvMediaPathsMatch("file:///storage/video%20one.mp4", "/storage/video one.mp4"))
        assertFalse(mpvMediaPathsMatch("https://a.test/video", "https://b.test/video"))
    }

    @Test fun a_failed_native_seek_keeps_the_latest_target_for_recovery() {
        val seek = MpvPendingSeek(1200000L)
        seek.updateIfPending(60000L)
        assertFalse(seek.applyIfPending { false })
        assertEquals(60000L, seek.peek())
        kotlin.test.assertTrue(seek.applyIfPending { it == 60000L })
        assertNull(seek.peek())
    }

    @Test fun a_playlist_redirect_keeps_ownership_until_its_child_opens_or_the_watchdog_fails() {
        val gate = MpvVideoReadinessGate()
        val tracker = MpvEndFileTracker()
        tracker.beforeLoad()
        gate.onLoadRequested()
        gate.bindNativeIdentity(10L, "https://media.test/list.m3u")
        val child = MpvNativeFileIdentity(11L, "https://media.test/part.mp4", "https://media.test/list.m3u")
        kotlin.test.assertTrue(gate.defersPlaylistExpansionEnd(child))
        assertFalse(tracker.consumeExpectedEnd(continuing = true))
        kotlin.test.assertTrue(tracker.beforeLoad(), "Replacing the still-opening child must reserve its END_FILE")
        gate.onStartFile(child)
        gate.onFileLoaded(child)
        assertFalse(gate.defersPlaylistExpansionEnd(child))
    }
}
