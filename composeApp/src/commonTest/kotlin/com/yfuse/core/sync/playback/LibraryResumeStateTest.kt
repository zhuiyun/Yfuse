package com.yfuse.core.sync.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryResumeStateTest {
    private fun record(
        position: Long,
        server: String? = null,
    ) = PlaybackStateRecord(
        mediaKey = "tmdb:42/s2e3",
        positionMs = position,
        deviceId = "device",
        serverId = server,
        serverItemId = server?.let { "episode" },
    )

    @Test
    fun `newer shared viewing generation overrides an older direct record`() {
        val direct = record(22 * 60_000L, "server")
        val alias = record(5 * 60_000L).copy(lastPlayedAtEpochMs = 9_000_000L, progressEpoch = 3L)
        assertEquals(alias, libraryResumeState(direct, listOf(alias)))
    }

    @Test
    fun `explicit restart and completion do not resurrect portable progress`() {
        val old = record(22 * 60_000L)
        val reset = record(0L, "server").copy(progressEpoch = 2L)
        assertEquals(reset, libraryResumeState(reset, listOf(old)))
        val completed = reset.copy(played = true)
        assertEquals(completed, libraryResumeState(completed, listOf(old)))
    }

    @Test
    fun `missing library state uses the newest known shared source`() {
        assertEquals(record(500L, "other"), libraryResumeState(null, listOf(record(500L, "other"))))
        val portable = record(1_000L).copy(lastPlayedAtEpochMs = 1_000L)
        assertEquals(portable, libraryResumeState(null, listOf(record(2_000L, "other"), portable)))
    }
}
