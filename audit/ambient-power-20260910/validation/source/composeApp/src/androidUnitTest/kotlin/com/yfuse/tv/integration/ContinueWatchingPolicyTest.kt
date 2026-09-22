package com.yfuse.tv.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContinueWatchingPolicyTest {
    private val policy = ContinueWatchingPolicy()

    @Test
    fun movieEligibilityUsesEarlierOfThreePercentAndTwoMinutes() {
        val oneHourMovie = entry(positionMs = 107_999L, durationMs = 3_600_000L)
        assertIs<ContinueWatchingDecision.Ignore>(policy.decide(ContinueWatchingObservation(oneHourMovie)))
        assertIs<ContinueWatchingDecision.Upsert>(
            policy.decide(ContinueWatchingObservation(oneHourMovie.copy(positionMs = 108_000L))),
        )

        val longMovie = entry(positionMs = 119_999L, durationMs = 10_800_000L)
        assertIs<ContinueWatchingDecision.Ignore>(policy.decide(ContinueWatchingObservation(longMovie)))
        assertIs<ContinueWatchingDecision.Upsert>(
            policy.decide(ContinueWatchingObservation(longMovie.copy(positionMs = 120_000L))),
        )
    }

    @Test
    fun episodesRequireTwoMinutesAndCompletionDeletesAtNinetyFivePercent() {
        val episode =
            entry(
                positionMs = 119_999L,
                durationMs = 2_400_000L,
                mediaType = ContinueWatchingMediaType.Episode,
            )
        assertIs<ContinueWatchingDecision.Ignore>(policy.decide(ContinueWatchingObservation(episode)))
        assertIs<ContinueWatchingDecision.Upsert>(
            policy.decide(ContinueWatchingObservation(episode.copy(positionMs = 120_000L))),
        )
        assertIs<ContinueWatchingDecision.Delete>(
            policy.decide(ContinueWatchingObservation(episode.copy(positionMs = 2_280_000L))),
        )
    }

    @Test
    fun explicitCompletionAndNewLowGenerationRemoveStaleRow() {
        val eligible = entry(positionMs = 300_000L, durationMs = 3_600_000L)
        assertIs<ContinueWatchingDecision.Delete>(
            policy.decide(ContinueWatchingObservation(eligible, explicitlyCompleted = true)),
        )
        assertIs<ContinueWatchingDecision.Delete>(
            policy.decide(
                ContinueWatchingObservation(
                    eligible.copy(positionMs = 0L),
                    startedNewGeneration = true,
                ),
            ),
        )
    }

    @Test
    fun publicationIsGlobalTopFiveWhileScopeRetentionStaysIsolated() {
        val scopeA = scope("server-a", "profile-a")
        val scopeB = scope("server-a", "profile-b")
        val scopeC = scope("server-b", "profile-a", TvMediaProvider.Plex)
        val entries =
            (1L..8L).map { rank ->
                val scope = listOf(scopeA, scopeB, scopeC)[(rank % 3L).toInt()]
                entry(
                    identity = ContinueWatchingIdentity(scope, "item-$rank"),
                    positionMs = 180_000L,
                    durationMs = 3_600_000L,
                    lastEngagementEpochMs = rank,
                )
            }

        assertEquals(
            listOf("item-8", "item-7", "item-6", "item-5", "item-4"),
            policy.selectForPublication(entries).map { it.identity.itemId },
        )
        assertEquals(
            entries.filter { it.identity.scope == scopeB }.sortedByDescending { it.lastEngagementEpochMs },
            policy.selectForScope(scopeB, entries),
        )
    }

    private fun scope(
        server: String,
        profile: String,
        provider: TvMediaProvider = TvMediaProvider.Emby,
    ) = ContinueWatchingScope(provider, server, profile)

    private fun entry(
        identity: ContinueWatchingIdentity = ContinueWatchingIdentity(scope("server-a", "profile-a"), "item"),
        positionMs: Long,
        durationMs: Long,
        mediaType: ContinueWatchingMediaType = ContinueWatchingMediaType.Movie,
        lastEngagementEpochMs: Long = 1L,
    ) =
        ContinueWatchingEntry(
            identity = identity,
            mediaType = mediaType,
            title = "Title",
            positionMs = positionMs,
            durationMs = durationMs,
            lastEngagementEpochMs = lastEngagementEpochMs,
        )
}
