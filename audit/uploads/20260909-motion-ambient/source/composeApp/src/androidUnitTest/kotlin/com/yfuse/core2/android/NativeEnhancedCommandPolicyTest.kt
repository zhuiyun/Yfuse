package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeEnhancedCommandPolicyTest {
    @Test
    fun primary_and_secondary_selections_survive_the_same_worker_batch() {
        val primary = AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack(1, null)
        val secondary = AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack(2, null, secondary = true)
        val secondaryOff = AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack(null, null, secondary = true)

        assertEquals(
            listOf(primary, secondaryOff),
            coalesceNativeEnhancedCommands(listOf(primary, secondary, secondaryOff)),
        )
        assertEquals(
            listOf(secondary, primary),
            coalesceNativeEnhancedCommands(listOf(secondary, primary)),
        )
    }

    @Test
    fun seek_remains_a_barrier_between_subtitle_selections() {
        val before = AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack(null, "external:0", secondary = true)
        val seek = AndroidNativeEnhancedYPlayer.Command.Seek(20_000_000L)
        val after = AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack(3, null, secondary = true)
        assertEquals(listOf(before, seek, after), coalesceNativeEnhancedCommands(listOf(before, seek, after)))
    }

    @Test
    fun consecutive_scrub_and_speed_commands_keep_only_the_latest_value() {
        val commands =
            listOf(
                AndroidNativeEnhancedYPlayer.Command.Seek(1L),
                AndroidNativeEnhancedYPlayer.Command.Seek(2L),
                AndroidNativeEnhancedYPlayer.Command.SetSpeed(1.25f),
                AndroidNativeEnhancedYPlayer.Command.SetSpeed(1.5f),
            )

        assertEquals(
            listOf(
                AndroidNativeEnhancedYPlayer.Command.Seek(2L),
                AndroidNativeEnhancedYPlayer.Command.SetSpeed(1.5f),
            ),
            coalesceNativeEnhancedCommands(commands),
        )
    }

    @Test
    fun playback_barrier_preserves_seek_order() {
        val commands =
            listOf(
                AndroidNativeEnhancedYPlayer.Command.Seek(1L),
                AndroidNativeEnhancedYPlayer.Command.Pause,
                AndroidNativeEnhancedYPlayer.Command.Seek(2L),
            )

        assertEquals(commands, coalesceNativeEnhancedCommands(commands))
    }

    @Test
    fun enhanced_remote_files_use_the_transport_proxy() {
        assertTrue(shouldProxyEnhancedSourceUri("https://media.example/video.mkv?token=secret"))
        assertTrue(shouldProxyEnhancedSourceUri("webdav://media.example/video.mkv"))
    }

    @Test
    fun local_and_loopback_sources_do_not_reenter_the_proxy() {
        assertFalse(shouldProxyEnhancedSourceUri("file:///storage/emulated/0/video.mkv"))
        assertFalse(shouldProxyEnhancedSourceUri("content://media/external/video/1"))
        assertFalse(shouldProxyEnhancedSourceUri("http://127.0.0.1:1234/ycore/video.mkv"))
        assertFalse(shouldProxyEnhancedSourceUri("http://localhost:1234/ycore/video.mkv"))
    }
}
