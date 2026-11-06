package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRecoveryStepTest {
    private fun copyOn(serverId: String?) =
        PlayerMediaItem(
            id = "item",
            url = "https://example.invalid/$serverId/stream",
            transcodeUrl = "",
            title = "第 1 集",
            serverId = serverId,
        )

    private fun step(
        eligible: Boolean = true,
        nextVersionId: String? = "v2",
        servers: List<PlayerMediaItem> = listOf(copyOn("s2")),
        serversTried: Set<String> = setOf("s1"),
    ) = nextPlaybackRecoveryStep(
        backendFallbackEligible = eligible,
        nextVersionId = nextVersionId,
        serverCandidates = servers,
        serversTried = serversTried,
    )

    @Test
    fun anotherVersionAfterSelectedEngineFailed() {
        assertEquals(PlaybackRecoveryStep.Version("v2"), step())
    }

    @Test
    fun anotherServerOnceNoVersionIsLeft() {
        val result = step(nextVersionId = null)
        assertEquals(PlaybackRecoveryStep.Server(copyOn("s2"), "s2"), result)
    }

    @Test
    fun aFailureTheBackendIsNotBehindSkipsStraightToAnotherServer() {
        // A network failure retried on another decoder only blames that decoder for it.
        assertEquals(PlaybackRecoveryStep.Server(copyOn("s2"), "s2"), step(eligible = false))
    }

    @Test
    fun triedAndUnnamedServersAreSkipped() {
        val servers = listOf(copyOn(null), copyOn("s1"), copyOn("s3"))
        assertEquals(
            PlaybackRecoveryStep.Server(copyOn("s3"), "s3"),
            step(eligible = false, servers = servers),
        )
    }

    @Test
    fun nothingLeftLeavesTheFailureStanding() {
        assertEquals(
            PlaybackRecoveryStep.Exhausted,
            step(eligible = false, servers = listOf(copyOn("s1"))),
        )
    }
}
