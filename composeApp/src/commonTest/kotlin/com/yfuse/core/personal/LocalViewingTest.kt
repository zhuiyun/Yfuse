package com.yfuse.core.personal

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalViewingTest {
    private val movie = PersonalMediaRef("tmdb:603", "黑客帝国", "Movie", tmdbId = 603)

    @Test
    fun actualElapsedTimeExcludesPauseBufferingSeeksAndFrozenPlayback() {
        val counter = ViewingTimeCounter()
        assertEquals(0L, counter.sample(0L, 0L, true))
        assertEquals(1_000L, counter.sample(1_000L, 1_000L, true))
        assertEquals(0L, counter.sample(2_000L, 1_000L, true))
        assertEquals(0L, counter.sample(3_000L, 1_000L, false))
        assertEquals(0L, counter.sample(10_000L, 1_000L, false))
        assertEquals(0L, counter.sample(11_000L, 1_000L, true))
        assertEquals(1_000L, counter.sample(12_000L, 2_000L, true))
        assertEquals(0L, counter.sample(13_000L, 600_000L, true))
        assertEquals(1_000L, counter.sample(14_000L, 601_000L, true))
        assertEquals(0L, counter.sample(15_000L, 20_000L, true))
        assertEquals(0L, counter.sample(60_000L, 65_000L, true))
    }

    @Test
    fun speedChangesCountWallTimeRatherThanDoubleMediaTime() {
        val counter = ViewingTimeCounter()
        counter.sample(0L, 0L, true, 2f)
        assertEquals(1_000L, counter.sample(1_000L, 2_000L, true, 2f))
        assertEquals(0L, counter.sample(2_000L, 4_000L, true, 0.5f))
        assertEquals(1_000L, counter.sample(3_000L, 4_500L, true, 0.5f))
        assertEquals(0L, counter.sample(2_000L, 5_000L, true, 0.5f))
    }

    @Test
    fun midnightAndDstAllocateTimeToTheActualCivilDays() {
        val end = Instant.parse("2026-10-09T16:00:02Z").toEpochMilli()
        assertEquals(
            mapOf("2026-10-09" to 3_000L, "2026-10-10" to 2_000L),
            viewingDayDurations(end, 5_000L, ZoneId.of("Asia/Shanghai")),
        )
        val dstEnd = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()
        assertEquals(
            mapOf("2026-03-08" to 23 * 3_600_000L),
            viewingDayDurations(dstEnd, 23 * 3_600_000L, ZoneId.of("America/New_York")),
        )
        assertTrue(viewingDayDurations(end, 0L, ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun framePositionRoundingDoesNotLoseTimeAcrossConsecutiveSamples() {
        val counter = ViewingTimeCounter()
        counter.sample(0L, 0L, true)
        val first = counter.sample(1_000L, 967L, true)
        val second = counter.sample(2_000L, 2_000L, true)
        assertEquals(2_000L, first + second)
        assertEquals(0L, counter.sample(3_000L, 2_000L, true))
        assertEquals(1_000L, counter.sample(4_000L, 3_000L, true))
    }

    @Test
    fun summariesDeduplicateReplaysAndShowsButRetainEpisodeAndDayCounts() {
        val film = session("film", movie, mapOf("2026-10-09" to 1_000L, "2026-10-10" to 2_000L))
        val replay = session("replay", movie, mapOf("2026-10-10" to 4_000L))
        val first =
            session(
                "ep1",
                movie.copy(mediaKey = "ep1", mediaType = "Episode"),
                mapOf("2026-10-10" to 8_000L),
            ).copy(seriesKey = "show")
        val second =
            session(
                "ep2",
                movie.copy(mediaKey = "ep2", mediaType = "Episode"),
                mapOf("2026-11-01" to 16_000L),
            ).copy(seriesKey = "show")
        val all = listOf(film, replay, first, second)
        assertEquals(ViewingSummary(31_000L, 1, 1, 2, 3), viewingSummary(all))
        assertEquals(
            ViewingSummary(15_000L, 1, 1, 1, 2),
            viewingSummary(all, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")),
        )
        assertEquals(
            ViewingSummary(14_000L, 1, 1, 1, 1),
            viewingSummary(all, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")),
        )
        assertEquals(ViewingSummary(0L, 0, 0, 0, 0), viewingSummary(emptyList()))
    }

    @Test
    fun mondayFirstCalendarsCoverLeapDaysAndMonthBoundaries() {
        val october = viewingMonthCells(YearMonth.of(2026, 10))
        assertEquals(listOf(null, null, null, LocalDate.of(2026, 10, 1)), october.take(4))
        assertEquals(35, october.size)
        val leap = viewingMonthCells(YearMonth.of(2024, 2))
        assertTrue(LocalDate.of(2024, 2, 29) in leap)
        assertEquals(0, leap.size % 7)
        assertEquals(LocalDate.of(2026, 6, 1), viewingMonthCells(YearMonth.of(2026, 6)).first())
    }

    @Test
    fun cumulativePersistenceIsIdempotentAndRejectsRegressionsAndInvalidDates() {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        val store = LocalViewingStore(settings, personal)
        val token = store.scopeToken
        val first = session("session", movie, mapOf("2026-10-10" to 1_000L))
        assertTrue(store.record(first, token))
        assertTrue(store.record(first, token))
        val grown = first.copy(watchedByDay = mapOf("2026-10-10" to 2_000L), completed = true)
        assertTrue(store.record(grown, token))
        assertFalse(store.record(first, token))
        assertFalse(store.record(session("bad", movie, mapOf("2026-02-30" to 1_000L)), token))
        assertFalse(store.record(session("negative", movie, mapOf("2026-10-10" to -1L)), token))
        assertEquals(listOf(grown), LocalViewingStore(settings, PersonalLibraryRepository(settings)).sessions.value)
        assertTrue(personal.snapshot().entries.isEmpty())
    }

    @Test
    fun profileAndAccountSwitchRejectLatePlayerWritesWithoutSharingLocalStatistics() =
        runTest {
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            personal.bindAccount("alice")
            val store = LocalViewingStore(settings, personal)
            val oldToken = store.scopeToken
            val film = session("main", movie, mapOf("2026-10-10" to 5_000L))
            assertTrue(store.record(film, oldToken))
            personal.saveProfile(name = "另一位", child = false, serverIds = emptySet()).getOrThrow()
            val other =
                personal.state.value.profiles
                    .last()
                    .id
            personal.switchProfile(other).getOrThrow()
            assertTrue(store.sessions.value.isEmpty())
            assertFalse(store.record(film, oldToken))
            assertTrue(store.record(film.copy(id = "other"), store.scopeToken))
            personal.bindAccount("bob")
            assertTrue(store.sessions.value.isEmpty())
            personal.bindAccount("alice")
            assertEquals(
                "other",
                store.sessions.value
                    .single()
                    .id,
            )
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE).getOrThrow()
            assertEquals(listOf(film), store.sessions.value)
            assertFalse(store.record(film, oldToken))
        }

    @Test
    fun removedServerPermissionsHideEarlierViewingWithoutDestroyingItsRecords() =
        runTest {
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            val store = LocalViewingStore(settings, personal)
            val film = session("private", movie.copy(serverId = "restricted"), mapOf("2026-10-10" to 1_000L))
            assertTrue(store.record(film, store.scopeToken))
            personal
                .saveProfile(
                    id = DEFAULT_PERSONAL_PROFILE,
                    name = "个人",
                    child = false,
                    serverIds = setOf("allowed"),
                ).getOrThrow()
            assertTrue(store.sessions.value.isEmpty())
            assertFalse(store.record(film.copy(watchedByDay = mapOf("2026-10-10" to 2_000L)), store.scopeToken))
            personal
                .saveProfile(
                    id = DEFAULT_PERSONAL_PROFILE,
                    name = "个人",
                    child = false,
                    serverIds = emptySet(),
                ).getOrThrow()
            assertEquals(listOf(film), store.sessions.value)
        }

    @Test
    fun aCorruptRecordCannotDiscardOtherLocalHistoryDuringRestart() {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        val store = LocalViewingStore(settings, personal)
        val film = session("good", movie, mapOf("2026-10-10" to 1_000L))
        assertTrue(store.record(film, store.scopeToken))
        settings.putString("personal.scope.${personal.storageNamespace}.viewing.session.bad", "{corrupt")
        assertEquals(listOf(film), LocalViewingStore(settings, personal).sessions.value)
    }

    @Test
    fun failedPersistenceDoesNotPublishUnsavedViewingOrThrowIntoThePlayer() {
        val delegate = MapSettings()
        val settings =
            object : Settings by delegate {
                override fun putString(
                    key: String,
                    value: String,
                ) {
                    if (key.contains("viewing.session.")) error("Storage unavailable")
                    delegate.putString(key, value)
                }
            }
        val personal = PersonalLibraryRepository(settings)
        val store = LocalViewingStore(settings, personal)
        assertFalse(store.record(session("session", movie, mapOf("2026-10-10" to 1_000L)), store.scopeToken))
        assertTrue(store.sessions.value.isEmpty())
    }

    private fun session(
        id: String,
        media: PersonalMediaRef,
        days: Map<String, Long>,
    ) = LocalViewingSession(id, media, startedAtEpochMs = 1L, watchedByDay = days)
}
