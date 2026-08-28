package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRemotePolicyTest {
    @Test
    fun `accepts only known restrictive paths from a newer short-lived policy`() {
        val state =
            sanitizePlaybackRemotePolicy(
                document =
                    PlaybackRemotePolicyDocument(
                        revision = 4,
                        expiresAtEpochMs = 2_000,
                        disabledPaths = setOf("ycore.gpu", "mdk", "unknown.future.path"),
                    ),
                currentRevision = 3,
                nowEpochMs = 1_000,
            )

        assertEquals(setOf(PlaybackRemotePath.YCoreGpu, PlaybackRemotePath.Mdk), state?.disabledPaths)
    }

    @Test
    fun `rejects replay expired and excessively long policies`() {
        val base = PlaybackRemotePolicyDocument(5, 2_000, setOf("mpv"))
        assertNull(sanitizePlaybackRemotePolicy(base, currentRevision = 5, nowEpochMs = 1_000))
        assertNull(sanitizePlaybackRemotePolicy(base, currentRevision = 4, nowEpochMs = 2_000))
        assertNull(
            sanitizePlaybackRemotePolicy(
                base.copy(expiresAtEpochMs = 40L * 24L * 60L * 60L * 1_000L),
                currentRevision = 4,
                nowEpochMs = 0,
            ),
        )
    }
}
