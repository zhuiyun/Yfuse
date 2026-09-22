package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHistoryTest {
    @Test
    fun seriesKeepsTheMostRecentlyWatchedEpisodeAndItsExactProgress() {
        val old =
            item("ep2", "Episode", "Reacher", "show").copy(
                lastPlayedDate = "2026-09-06T09:00:00Z",
                resumePositionTicks = 800L,
            )
        val recent =
            old.copy(
                id = "ep1",
                lastPlayedDate = "2026-09-06T10:00:00Z",
                resumePositionTicks = 100L,
            )
        assertEquals(listOf(recent), deduplicatePlaybackHistory(listOf(old, recent)))
    }

    @Test
    fun movieVersionsUseProviderIdentityAndKeepTheLatestSource() {
        val old = item("movie-a").copy(providerIds = mapOf("Tmdb" to "123"), lastPlayedDate = "2026-09-05T10:00:00Z")
        val recent =
            item("movie-b", title = "Translated title").copy(
                providerIds = mapOf("tmdb" to "123"),
                lastPlayedDate = "2026-09-06T10:00:00Z",
            )
        val entries = listOf("server-a" to old, "server-b" to recent)
        assertEquals(listOf(entries[1]), deduplicatePlaybackHistory(entries, { it.second }, { it.first }))
    }

    @Test
    fun datesAreComparedAsInstantsIncludingOffsetsAndFractionalSeconds() {
        val a = item("a").copy(lastPlayedDate = "2026-09-06T14:00:00+08:00")
        val b = item("b").copy(lastPlayedDate = "2026-09-06T06:00:00.100Z")
        assertEquals(listOf(b), deduplicatePlaybackHistory(listOf(a, b)))
    }

    @Test
    fun moviesAndSeriesWithTheSameTitleAreSeparateWorks() {
        val movie = item("movie")
        val episode = item("episode", "Episode")
        assertEquals(listOf(movie, episode), deduplicatePlaybackHistory(listOf(movie, episode)))
    }

    @Test
    fun remakesAndConflictingExternalIdsAreNotMergedByTitle() {
        val a = item("a").copy(providerIds = mapOf("Tmdb" to "123"))
        val b = item("b").copy(providerIds = mapOf("Tmdb" to "456"))
        val remake = item("remake").copy(year = 1990)
        assertEquals(listOf(a, b, remake), deduplicatePlaybackHistory(listOf(a, b, remake)))
    }

    @Test
    fun missingDatesPreserveServerOrderAndRepeatedItemIdsAreRemoved() {
        val first = item("same").copy(lastPlayedDate = "invalid", year = null)
        val duplicate = first.copy(resumePositionTicks = 80L)
        val other = item("other", title = "Another title").copy(year = null)
        assertEquals(listOf(first, other), deduplicatePlaybackHistory(listOf(first, duplicate, other)))
    }

    @Test
    fun localItemIdsDoNotCollideBetweenServersWithoutWorkMetadata() {
        val a = item("42").copy(year = null)
        val b = a.copy(title = "Another title")
        val entries = listOf("server-a" to a, "server-b" to b)
        assertEquals(entries, deduplicatePlaybackHistory(entries, { it.second }, { it.first }))
    }

    @Test
    fun episodeProviderIdsDoNotSplitARecordedSeriesIntoEpisodeCards() {
        val a = item("ep1", "Episode", posterId = "series").copy(providerIds = mapOf("Tmdb" to "101"))
        val b = a.copy(id = "ep2", providerIds = mapOf("Tmdb" to "102"))
        assertEquals(listOf(a), deduplicatePlaybackHistory(listOf(a, b)))
    }

    private fun item(
        id: String,
        type: String = "Movie",
        title: String = "Example",
        posterId: String = id,
    ) = MediaItem(
        id = id,
        title = title,
        subtitle = null,
        type = type,
        posterItemId = posterId,
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = 10.0,
        year = 2026,
    )
}
