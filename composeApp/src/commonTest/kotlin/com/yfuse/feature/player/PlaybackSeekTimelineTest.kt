package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSeekTimelineTest {
    private val media = PlaybackTimelineIdentity(0, "server", "movie")
    private val owner = Any()
    private val initial = PlaybackState(positionMs = 600_000, durationMs = 900_000, bufferedPositionMs = 650_000)

    @Test fun backward_seek_while_buffering_accepts_the_target_and_discards_old_buffer_extent() {
        val memory = stabilizePlaybackTimeline(PlaybackTimelineMemory(), media, initial, owner).memory
        val sought = initial.copy(positionMs = 120_000, bufferedPositionMs = 120_000, buffering = true)
        val result = stabilizePlaybackTimeline(memory, media, sought, owner, seekRevision = 1)
        assertEquals(sought, result.state)
        val next = stabilizePlaybackTimeline(result.memory, media, sought.copy(bufferedPositionMs = 125_000), owner, 1)
        assertEquals(120_000, next.state.positionMs)
        assertEquals(125_000, next.state.bufferedPositionMs)
    }

    @Test fun seek_during_reprobe_retains_duration_but_uses_the_requested_position() {
        val memory = stabilizePlaybackTimeline(PlaybackTimelineMemory(), media, initial, owner).memory
        val sought = initial.copy(positionMs = 120_000, durationMs = 0, bufferedPositionMs = 120_000, buffering = true)
        val result = stabilizePlaybackTimeline(memory, media, sought, owner, 1)
        assertEquals(120_000, result.state.positionMs)
        assertEquals(900_000, result.state.durationMs)
        val pending = stabilizePlaybackTimeline(result.memory, media, sought.copy(positionMs = 0), owner, 1)
        assertEquals(120_000, pending.state.positionMs)
    }

    @Test fun replacement_player_revision_is_not_mistaken_for_an_explicit_seek() {
        val memory = stabilizePlaybackTimeline(PlaybackTimelineMemory(), media, initial, owner, 7).memory
        val replacement = stabilizePlaybackTimeline(memory, media, PlaybackState(buffering = true), Any(), 0)
        assertEquals(600_000, replacement.state.positionMs)
        assertEquals(900_000, replacement.state.durationMs)
    }

    @Test fun buffering_without_a_seek_still_rejects_an_unintentional_timeline_reset() {
        val memory = stabilizePlaybackTimeline(PlaybackTimelineMemory(), media, initial, owner, 1).memory
        val result = stabilizePlaybackTimeline(memory, media, initial.copy(positionMs = 0, buffering = true), owner, 1)
        assertEquals(600_000, result.state.positionMs)
    }
}
