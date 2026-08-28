package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeEnhancedCommandPolicyTest {
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
}
