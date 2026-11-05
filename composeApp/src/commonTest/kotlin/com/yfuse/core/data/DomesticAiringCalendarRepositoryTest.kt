package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.OfficialScheduleEnvelope
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.util.shiftIsoDate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomesticAiringCalendarRepositoryTest {
    @Test
    fun foreground_adds_unfollowed_domestic_metadata_and_retains_the_signed_foreign_publication() =
        runTest {
            val added = episode()
            var calls = 0
            fixture(
                lookup =
                    DomesticAiringScheduleLookup { _, _, _ ->
                        calls++
                        DomesticAiringScheduleResult(listOf(added), complete = true)
                    },
                signedPublication = true,
            ).use { fixture ->
                fixture.catalog.refreshIfDue(force = true).getOrThrow()
                val publishedForeign =
                    fixture.catalog.between("2026-10-29", "2026-11-19").filter { it.origin == ShowOrigin.Foreign }
                assertTrue(publishedForeign.isNotEmpty(), "The signed fixture must exercise foreign preservation")
                val rows =
                    fixture.repository
                        .calendar(today = "2026-11-05")
                        .getOrThrow()
                        .flatMap(CalendarDay::entries)

                assertEquals(1, calls)
                assertEquals(added, rows.single { it.episode.mediaKey == added.mediaKey }.episode)
                assertTrue(publishedForeign.all { published -> rows.any { it.episode == published } })
                assertFalse(rows.single { it.episode.mediaKey == added.mediaKey }.followed)
                assertTrue(
                    fixture.follows.followed.value
                        .isEmpty(),
                )
                assertTrue(fixture.follows.notificationSubscriptions().isEmpty())
            }
        }

    @Test
    fun signed_schedule_wins_the_same_episode_coordinate_and_keeps_its_provenance() =
        runTest {
            var discovered = emptyList<AiringEpisode>()
            fixture(
                lookup = DomesticAiringScheduleLookup { _, _, _ -> DomesticAiringScheduleResult(discovered, true) },
                signedPublication = true,
            ).use { fixture ->
                fixture.catalog.refreshIfDue(force = true).getOrThrow()
                val published = fixture.catalog.series(272938, "师兄太稳健")!!.first()
                discovered =
                    listOf(
                        published.copy(
                            airDate = shiftIsoDate(published.airDate, 1),
                            scheduleAuthority = AiringScheduleAuthority.Tmdb,
                            scheduleProvider = "TMDB",
                            scheduleRevision = null,
                            scheduleEvidence = emptyList(),
                        ),
                    )
                val rows =
                    fixture.repository
                        .calendar(today = published.airDate)
                        .getOrThrow()
                        .flatMap(CalendarDay::entries)
                assertEquals(published, rows.single { it.episode.mediaKey == published.mediaKey }.episode)
                assertTrue(published.scheduleRevision != null)
            }
        }

    @Test
    fun a_signed_date_outside_the_window_cannot_be_reintroduced_by_a_conflicting_metadata_date() =
        runTest {
            var discovered = emptyList<AiringEpisode>()
            fixture(
                lookup = DomesticAiringScheduleLookup { _, _, _ -> DomesticAiringScheduleResult(discovered, true) },
                signedPublication = true,
            ).use { fixture ->
                fixture.catalog.refreshIfDue(force = true).getOrThrow()
                val published = fixture.catalog.series(272938, "师兄太稳健")!!.first()
                val incorrectDay = shiftIsoDate(published.airDate, 1)
                discovered =
                    listOf(published.copy(airDate = incorrectDay, scheduleAuthority = AiringScheduleAuthority.Tmdb))
                val rows =
                    fixture.repository
                        .calendar(pastDays = 0, futureDays = 0, today = incorrectDay)
                        .getOrThrow()
                        .flatMap(CalendarDay::entries)
                assertTrue(rows.none { it.episode.mediaKey == published.mediaKey })
            }
        }

    @Test
    fun reminder_calendar_never_runs_foreground_discovery_even_with_a_manual_subscription() =
        runTest {
            var calls = 0
            fixture(
                lookup =
                    DomesticAiringScheduleLookup { _, _, _ ->
                        calls++
                        error("Background reminders must not discover unrelated shows")
                    },
            ).use { fixture ->
                fixture.follows.follow(FollowedSeries(272938, "师兄太稳健", reminderMode = CalendarReminderMode.AtBroadcast))
                val rows = fixture.repository.followedCalendar(today = "2026-08-25").getOrThrow()
                assertTrue(rows.isNotEmpty())
                assertEquals(0, calls)
            }
        }

    @Test
    fun incomplete_discovery_preserves_previous_domestic_rows_and_prefers_new_coordinates() =
        runTest {
            val old = episode()
            val retained = episode(id = 9_000_002)
            val updated = old.copy(airDate = "2026-11-06")
            fixture(
                lookup =
                    DomesticAiringScheduleLookup {
                            _,
                            _,
                            _,
                        ->
                        DomesticAiringScheduleResult(listOf(updated), false)
                    },
                local = listOf(old, retained),
            ).use { fixture ->
                val rows =
                    fixture.repository
                        .calendar(today = "2026-11-05")
                        .getOrThrow()
                        .flatMap(CalendarDay::entries)
                assertEquals(updated, rows.single { it.episode.mediaKey == old.mediaKey }.episode)
                assertEquals(retained, rows.single { it.episode.mediaKey == retained.mediaKey }.episode)
            }
        }

    @Test
    fun discovery_failure_keeps_locally_known_domestic_rows() =
        runTest {
            val old = episode()
            fixture(
                lookup = DomesticAiringScheduleLookup { _, _, _ -> error("Metadata source is offline") },
                local = listOf(old),
            ).use { fixture ->
                val rows =
                    fixture.repository
                        .calendar(today = "2026-11-05")
                        .getOrThrow()
                        .flatMap(CalendarDay::entries)
                assertEquals(old, rows.single { it.episode.mediaKey == old.mediaKey }.episode)
            }
        }

    @Test
    fun caller_cancellation_is_not_reported_as_an_empty_successful_schedule() =
        runTest {
            fixture(
                lookup = DomesticAiringScheduleLookup { _, _, _ -> throw CancellationException("Calendar closed") },
                local = listOf(episode()),
            ).use { fixture ->
                assertFailsWith<CancellationException> { fixture.repository.calendar(today = "2026-11-05") }
            }
        }

    @Test
    fun background_home_refresh_does_not_discover_or_satisfy_the_later_foreground_cache() =
        runTest {
            var calls = 0
            val known = episode()
            fixture(
                lookup =
                    DomesticAiringScheduleLookup { _, _, _ ->
                        calls++
                        DomesticAiringScheduleResult(listOf(known), true)
                    },
                local = listOf(known),
            ).use { fixture ->
                val background =
                    fixture.repository.homeCalendar(today = "2026-11-05", includeDomesticDiscovery = false).getOrThrow()
                assertEquals(0, calls)
                assertTrue(background.flatMap(CalendarDay::entries).any { it.episode == known })

                val foreground = fixture.repository.calendar(today = "2026-11-05").getOrThrow()
                assertEquals(1, calls, "A background cache hit cannot replace foreground discovery")
                assertTrue(foreground.flatMap(CalendarDay::entries).any { it.episode == known })
            }
        }

    @Test
    fun cancelling_stalled_discovery_never_persists_the_initial_official_only_preview() =
        runTest {
            var discoveryStarted = false
            val known = episode()
            fixture(
                lookup =
                    DomesticAiringScheduleLookup { _, _, _ ->
                        discoveryStarted = true
                        awaitCancellation()
                    },
                signedPublication = true,
                local = listOf(known),
            ).use { fixture ->
                fixture.catalog.refreshIfDue(force = true).getOrThrow()
                val published = fixture.catalog.between("2026-10-29", "2026-11-19")
                assertTrue(published.isNotEmpty())
                val previews = mutableListOf<List<CalendarDay>>()
                val load =
                    async {
                        fixture.repository.calendar(today = "2026-11-05", onPreview = { previews += it })
                    }
                // The same test dispatcher drains initial availability work before cancellation;
                // only the explicitly stalled discovery remains pending.
                runCurrent()
                assertTrue(discoveryStarted)
                assertTrue(load.isActive)
                assertTrue(previews.flatten().flatMap(CalendarDay::entries).any { it.episode in published })
                assertEquals(0, fixture.localStore.writes)
                load.cancelAndJoin()
                assertEquals(0, fixture.localStore.writes)
                assertEquals(listOf(known), fixture.localStore.rows.map { it.episode })
            }
        }

    @Test
    fun an_unchanged_final_publication_is_still_persisted_exactly_once() =
        runTest {
            fixture(
                lookup = DomesticAiringScheduleLookup { _, _, _ -> DomesticAiringScheduleResult(emptyList(), true) },
                signedPublication = true,
            ).use { fixture ->
                fixture.catalog.refreshIfDue(force = true).getOrThrow()
                // A single published row also makes the pre-merge and sorted final lists
                // identical, so this exercises the episodes == cached optimization.
                val published = fixture.catalog.between("2026-12-01", "2026-12-01")
                assertEquals(1, published.size)
                val result =
                    fixture.repository.calendar(pastDays = 0, futureDays = 0, today = "2026-12-01").getOrThrow()
                assertEquals(published.toSet(), result.flatMap(CalendarDay::entries).map { it.episode }.toSet())
                assertEquals(1, fixture.localStore.writes)
                assertEquals(result.flatMap(CalendarDay::entries), fixture.localStore.rows)
            }
        }

    private fun fixture(
        lookup: DomesticAiringScheduleLookup,
        signedPublication: Boolean = false,
        local: List<AiringEpisode> = emptyList(),
    ): Fixture {
        val settings = MapSettings()
        val envelope =
            if (signedPublication) {
                Json { ignoreUnknownKeys = true }.decodeFromString(
                    OfficialScheduleEnvelope.serializer(),
                    File("src/commonTest/fixtures/calendar-production-r12.json").readText(),
                )
            } else {
                null
            }
        val catalog =
            OfficialAiringScheduleCatalog(
                object : CalendarBackendApi {
                    override val enabled = signedPublication

                    override suspend fun fetch(revision: String): OfficialScheduleEnvelope =
                        checkNotNull(envelope) { "Disabled backend must not be requested" }
                },
                settings,
            )
        val localStore = RecordingCalendarStore(local)
        val library = HttpClient(MockEngine { error("No media server is configured") })
        val follows = CalendarFollowStore(settings)
        val repository =
            AiringCalendarRepository(
                EmbyRepository(library),
                ServerRegistry(settings, TestSecureStore()),
                catalog,
                CalendarIdentityResolver(catalog, settings),
                follows,
                localStore = localStore,
                domesticSchedules = lookup,
            )
        return Fixture(repository, catalog, follows, library, localStore)
    }

    private class Fixture(
        val repository: AiringCalendarRepository,
        val catalog: OfficialAiringScheduleCatalog,
        val follows: CalendarFollowStore,
        val library: HttpClient,
        val localStore: RecordingCalendarStore,
    ) : AutoCloseable {
        override fun close() = library.close()
    }

    private class RecordingCalendarStore(
        local: List<AiringEpisode>,
    ) : CalendarLocalStore by NoOpCalendarLocalStore {
        var rows = local.map { CalendarEntry(it, LibraryStatus.Unknown, discoveryOnly = true) }
            private set
        var writes = 0
            private set

        override suspend fun readCalendar(
            fromDate: String,
            toDate: String,
            scope: String,
        ) = CalendarLocalSnapshot(
            rows
                .filter { it.episode.airDate in fromDate..toDate }
                .groupBy { it.episode.airDate }
                .map { (date, entries) -> CalendarDay(date, entries) },
            syncState = null,
        )

        override suspend fun replaceCalendarWindow(
            fromDate: String,
            toDate: String,
            scope: String,
            days: List<CalendarDay>,
            scheduleSyncedAtEpochMs: Long,
            resourcesCheckedAtEpochMs: Long,
            seriesScope: Set<Int>?,
        ) {
            writes++
            rows =
                rows.filterNot {
                    it.episode.airDate in fromDate..toDate &&
                        (seriesScope == null || it.episode.showTmdbId in seriesScope)
                } + days.flatMap(CalendarDay::entries)
        }
    }

    private fun episode(id: Int = 9_000_001) =
        AiringEpisode(id, "未关注国产$id", null, 1, 1, null, "2026-11-05", ShowOrigin.Domestic, scheduleProvider = "TMDB")
}
