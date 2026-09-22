package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeDirectCommandPolicyTest {
    @Test
    fun primary_and_secondary_selections_survive_the_same_worker_batch() {
        val primary = AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack(1, null)
        val secondary = AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack(2, null, secondary = true)
        val secondaryOff = AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack(null, null, secondary = true)

        assertEquals(
            listOf(primary, secondaryOff),
            coalesceNativeDirectCommands(listOf(primary, secondary, secondaryOff)),
        )
        assertEquals(
            listOf(secondary, primary),
            coalesceNativeDirectCommands(listOf(secondary, primary)),
        )
    }

    @Test
    fun seek_remains_a_barrier_between_subtitle_selections() {
        val before = AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack(null, "external:0", secondary = true)
        val seek = AndroidNativeDirectYPlayer.Command.Seek(20_000_000L)
        val after = AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack(3, null, secondary = true)
        assertEquals(listOf(before, seek, after), coalesceNativeDirectCommands(listOf(before, seek, after)))
    }

    @Test
    fun one_hundred_scrub_updates_execute_only_the_latest_seek() {
        val commands =
            (1L..100L).map { index ->
                AndroidNativeDirectYPlayer.Command.Seek(index * 1_000_000L)
            }

        val compacted = coalesceNativeDirectCommands(commands)

        assertEquals(1, compacted.size)
        assertEquals(100_000_000L, assertIs<AndroidNativeDirectYPlayer.Command.Seek>(compacted.single()).positionUs)
    }

    @Test
    fun lifecycle_barriers_preserve_order_while_redundant_work_is_compacted() {
        val compacted =
            coalesceNativeDirectCommands(
                listOf(
                    AndroidNativeDirectYPlayer.Command.Prepare,
                    AndroidNativeDirectYPlayer.Command.Prepare,
                    AndroidNativeDirectYPlayer.Command.SetVideoOutput(null),
                    AndroidNativeDirectYPlayer.Command.SetVideoOutput(null),
                    AndroidNativeDirectYPlayer.Command.Seek(1_000_000L),
                    AndroidNativeDirectYPlayer.Command.Seek(2_000_000L),
                    AndroidNativeDirectYPlayer.Command.Pause,
                    AndroidNativeDirectYPlayer.Command.SelectItem(2),
                    AndroidNativeDirectYPlayer.Command.Seek(3_000_000L),
                    AndroidNativeDirectYPlayer.Command.Play,
                ),
            )

        assertEquals(
            listOf(
                AndroidNativeDirectYPlayer.Command.Prepare,
                AndroidNativeDirectYPlayer.Command.SetVideoOutput(null),
                AndroidNativeDirectYPlayer.Command.Seek(2_000_000L),
                AndroidNativeDirectYPlayer.Command.Pause,
                AndroidNativeDirectYPlayer.Command.SelectItem(2),
                AndroidNativeDirectYPlayer.Command.Seek(3_000_000L),
                AndroidNativeDirectYPlayer.Command.Play,
            ),
            compacted,
        )
    }
}
