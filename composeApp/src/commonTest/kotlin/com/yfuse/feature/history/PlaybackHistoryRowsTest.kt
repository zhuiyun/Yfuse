package com.yfuse.feature.history

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.LocalViewingSession
import com.yfuse.core.personal.PersonalAccessPolicy
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHistoryRowsTest {
    @Test
    fun loadingWindowPrioritizesVisibleCardsAndAddsOnlyAdjacentPrefetch() {
        val rows =
            playbackHistoryRows(
                (1..10).map { episode("tmdb:$it/s1e1", "$it", it.toLong()) },
                emptyList(),
                listOf(first),
                policy,
            )
        assertEquals(
            listOf(rows[4], rows[5], rows[3], rows[6]),
            playbackHistoryLoadWindow(rows, listOf("watching-entries", rows[4].entry.identity, rows[5].entry.identity)),
        )
        assertTrue(playbackHistoryLoadWindow(rows, emptyList()).isEmpty())
    }

    private val first = SavedServer("first", "https://first", "一号服务器", "u", "User", "token")
    private val second = first.copy(id = "second", baseUrl = "https://second")
    private val policy = PersonalAccessPolicy()

    private fun episode(
        key: String,
        id: String,
        watchedAt: Long,
        serverId: String = "first",
    ) = PersonalEntry(
        "default",
        PersonalCollection.History,
        PersonalMediaRef(key, "单集 $id", "Episode", serverId = serverId, serverItemId = id),
        PersonalStamp(watchedAt, "device"),
        watchedAtEpochMs = watchedAt,
        positionMs = 60_000,
        durationMs = 240_000,
    )

    @Test
    fun differentSeasonsAndServersCollapseToTheLatestExactEpisodeAndProgress() {
        val old = episode("tmdb:7/s1e1", "old", 10).copy(completed = true)
        val latest = episode("tmdb:7/s2e3", "latest", 30, "second")
        val rows = playbackHistoryRows(listOf(old, latest), emptyList(), listOf(first, second), policy)
        val row = rows.single()
        assertEquals("latest", row.entry.media.serverItemId)
        assertEquals("second", row.server?.id)
        assertEquals("第 2 季 · 第 3 集", row.episodeLabel)
        assertEquals(0.25f, row.progress)
        assertEquals(false, row.entry.completed)
    }

    @Test
    fun localSeriesIdsDeduplicateEpisodesWithoutProviderIdsButNeverOtherServers() {
        val a = episode("emby:a", "a", 10)
        val b = episode("emby:b", "b", 20)
        val c = episode("emby:c", "c", 30, "second")
        val sessions =
            listOf(a, b, c).map {
                LocalViewingSession(
                    it.media.serverItemId!!,
                    it.media,
                    seriesTitle = "同名剧",
                    posterItemId = "series",
                    startedAtEpochMs = 1,
                )
            }
        val rows = playbackHistoryRows(listOf(a, b, c), sessions, listOf(first, second), policy)
        assertEquals(listOf("c", "b"), rows.map { it.entry.media.serverItemId })
        assertEquals("同名剧", rows.last().title)
    }

    @Test
    fun olderMetadataCanBridgeProviderAliasesWithoutReplacingTheNewestTarget() {
        val a = episode("tmdb:7/s1e1", "a", 30)
        val b = episode("tvdb:70/s1e2", "b", 20, "second")
        val bridge = episode("tmdb:7/s1e3", "c", 10, "second")
        val metadata =
            PlaybackHistoryMetadata(
                BaseItemDto(Id = "c", Type = "Episode", SeriesId = "s").toMediaDetail(),
                BaseItemDto(
                    Id = "s",
                    Type = "Series",
                    ProviderIds = mapOf("Tmdb" to "7", "Tvdb" to "70"),
                ).toMediaDetail(),
            )
        val row =
            playbackHistoryRows(
                listOf(bridge, b, a),
                emptyList(),
                listOf(first, second),
                policy,
                mapOf(
                    ("second" to "c") to metadata,
                ),
            ).single()
        assertEquals("a", row.entry.media.serverItemId)
        assertEquals("first", row.server?.id)
    }

    @Test
    fun sameTitlesWithDifferentSeriesIdsAndMoviesRemainSeparate() {
        val a = episode("tmdb:7/s1e1", "a", 10)
        val b = episode("tmdb:8/s1e1", "b", 20)
        val movie = a.copy(media = a.media.copy(mediaKey = "tmdb:7", mediaType = "Movie"), watchedAtEpochMs = 30)
        assertEquals(3, playbackHistoryRows(listOf(a, b, movie), emptyList(), listOf(first), policy).size)
    }

    @Test
    fun profilePermissionsAndTombstonesAreAppliedBeforeSeriesDeduplication() {
        val allowed = episode("tmdb:7/s1e1", "allowed", 10)
        val denied = episode("tmdb:7/s1e2", "denied", 40, "second")
        val otherProfile = allowed.copy(profileId = "child", watchedAtEpochMs = 50)
        val deleted = allowed.copy(deleted = true, watchedAtEpochMs = 60)
        val favorite = allowed.copy(collection = PersonalCollection.Favorite, watchedAtEpochMs = 70)
        val rows =
            playbackHistoryRows(
                listOf(denied, otherProfile, deleted, favorite, allowed),
                emptyList(),
                listOf(first, second),
                policy.copy(child = true, serverIds = setOf("first")),
            )
        assertEquals(listOf(allowed), rows.map { it.entry })
    }

    @Test
    fun synopsisArtworkAndEpisodeCoordinatesComeFromTheRecordedSource() {
        val entry = episode("emby:one", "one", 10).copy(durationMs = 0)
        val detail =
            BaseItemDto(
                Id = "one",
                Type = "Episode",
                SeriesId = "series",
                IndexNumber = 4,
                ParentIndexNumber = 2,
                Overview = "单集简介",
                RunTimeTicks = 2_400_000_000L,
            ).toMediaDetail()
        val series =
            BaseItemDto(
                Id = "series",
                Name = "测试剧",
                Type = "Series",
                Overview = "剧集简介",
                ImageTags =
                    mapOf(
                        "Primary" to "poster",
                    ),
            ).toMediaDetail()
        val row =
            playbackHistoryRows(
                listOf(entry),
                emptyList(),
                listOf(first),
                policy,
                mapOf(
                    ("first" to "one") to PlaybackHistoryMetadata(detail, series),
                ),
            ).single()
        assertEquals("测试剧", row.title)
        assertEquals("剧集简介", row.overview)
        assertEquals("第 2 季 · 第 4 集", row.episodeLabel)
        assertEquals(0.25f, row.progress)
        assertTrue(playbackHistoryPosterUrls(row).first()!!.contains("/Items/series/Images/Primary"))
    }

    @Test
    fun renamedServersResolveByAliasAndPlexArtworkNeverUsesAnEmbyEndpoint() {
        val renamed = first.copy(id = "new", previousIds = setOf("first"), kind = MediaServerKind.Plex)
        val entry = episode("tmdb:7/s1e1", "a", 10)
        val row = playbackHistoryRows(listOf(entry), emptyList(), listOf(renamed), policy).single()
        assertEquals("new", row.server?.id)
        assertNull(playbackHistoryPosterUrls(row).first())
        val session =
            LocalViewingSession(
                "s",
                entry.media,
                posterItemId = "series",
                posterTag = "plex:/library/metadata/9/thumb",
                startedAtEpochMs = 1,
            )
        val plexRow = row.copy(session = session)
        assertTrue(playbackHistoryPosterUrls(plexRow).first()!!.contains("/photo/:/transcode"))
    }

    @Test
    fun completedAndInvalidPositionsKeepProgressAndTimeLabelsWithinBounds() {
        val row = PlaybackHistoryRow(episode("tmdb:7/s1e1", "a", 10), null, first)
        assertEquals(1f, row.copy(entry = row.entry.copy(completed = true, positionMs = 0)).progress)
        assertEquals(0f, row.copy(entry = row.entry.copy(positionMs = -1)).progress)
        assertEquals(1f, row.copy(entry = row.entry.copy(positionMs = Long.MAX_VALUE)).progress)
        assertEquals("0:00", historyPositionLabel(-1))
        assertEquals("1:02:03", historyPositionLabel(3_723_000))
    }
}
