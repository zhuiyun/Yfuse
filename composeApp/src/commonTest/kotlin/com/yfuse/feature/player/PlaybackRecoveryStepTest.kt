package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
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
        engineOrder: List<PlayerEngine> = listOf(PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk),
        enginesTried: Set<PlayerEngine> = setOf(PlayerEngine.Mpv),
        eligible: Boolean = true,
        nextVersionId: String? = "v2",
        servers: List<PlayerMediaItem> = listOf(copyOn("s2")),
        serversTried: Set<String> = setOf("s1"),
    ) = nextPlaybackRecoveryStep(
        engineOrder = engineOrder,
        enginesTried = enginesTried,
        backendFallbackEligible = eligible,
        nextVersionId = nextVersionId,
        serverCandidates = servers,
        serversTried = serversTried,
    )

    @Test
    fun anotherEngineOnTheSameFileComesFirst() {
        assertEquals(PlaybackRecoveryStep.Engine(PlayerEngine.Exo), step())
    }

    @Test
    fun anotherVersionOnceEveryEngineHasFailed() {
        val allTried = setOf(PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk)
        assertEquals(PlaybackRecoveryStep.Version("v2"), step(enginesTried = allTried))
    }

    @Test
    fun anotherServerOnceNoEngineOrVersionIsLeft() {
        val allTried = setOf(PlayerEngine.Mpv, PlayerEngine.Exo, PlayerEngine.Mdk)
        val result = step(enginesTried = allTried, nextVersionId = null)
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
