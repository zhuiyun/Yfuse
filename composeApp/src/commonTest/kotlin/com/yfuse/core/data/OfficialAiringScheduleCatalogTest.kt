package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.OfficialScheduleEnvelope
import com.yfuse.core.model.AiringAccessTier
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.security.verifyEd25519Signature
import com.yfuse.core.util.scheduledEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialAiringScheduleCatalogTest {
    private val catalog =
        OfficialAiringScheduleCatalog(
            client = HttpClient(MockEngine { error("Unexpected remote refresh") }),
            settings = MapSettings(),
        )

    @Test
    fun live_action_schedule_matches_the_revised_official_member_calendar() {
        val episodes = catalog.series(272938, "fallback").orEmpty()

        assertEquals(21, episodes.size)
        assertEquals((1..21).toList(), episodes.map { it.episodeNumber })
        assertEquals(listOf("2026-08-19", "2026-08-19", "2026-08-19"), episodes.take(3).map { it.airDate })
        assertEquals("2026-08-24", episodes.single { it.episodeNumber == 12 }.airDate)
        assertEquals("2026-08-25", episodes.single { it.episodeNumber == 13 }.airDate)
        assertEquals("2026-08-25", episodes.single { it.episodeNumber == 14 }.airDate)
        assertEquals("2026-08-30", episodes.last().airDate)
        assertEquals(setOf(AiringScheduleAuthority.Official), episodes.map { it.scheduleAuthority }.toSet())
        assertEquals(setOf("12:00"), episodes.mapNotNull { it.airTime }.toSet())
        assertEquals(listOf("优酷", "爱奇艺"), episodes.first().platforms)
        assertEquals(setOf(100), episodes.mapNotNull { it.scheduleConfidence }.toSet())
        assertEquals(setOf("师兄太稳健官微"), episodes.flatMap { it.scheduleEvidence }.map { it.publisher }.toSet())
        assertEquals(setOf("/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg"), episodes.mapNotNull { it.posterPath }.toSet())
    }

    @Test
    fun beauty_schedule_contains_only_the_published_tencent_member_dates() {
        val episodes = catalog.series(294446, "fallback").orEmpty()
        val expected =
            mapOf(
                "2026-10-09" to listOf(1, 2, 3),
                "2026-10-10" to listOf(4, 5),
                "2026-10-11" to listOf(6, 7),
                "2026-10-12" to listOf(8, 9),
                "2026-10-13" to listOf(10, 11),
                "2026-10-14" to listOf(12, 13),
                "2026-10-15" to listOf(14, 15),
                "2026-10-16" to listOf(16, 17),
            )

        assertEquals(
            expected,
            episodes.groupBy(AiringEpisode::airDate).mapValues { (_, rows) ->
                rows.map {
                    it.episodeNumber
                }
            },
        )
        assertEquals((1..17).toList(), episodes.map { it.episodeNumber })
        assertEquals(setOf("美人余"), episodes.map { it.showTitle }.toSet())
        assertEquals(setOf(1), episodes.map { it.seasonNumber }.toSet())
        assertEquals(setOf(AiringAccessTier.Member), episodes.map { it.accessTier }.toSet())
        assertTrue(episodes.all { it.platforms == listOf("腾讯视频") })
        assertEquals(setOf("18:00"), episodes.map { it.airTime }.toSet())
        assertEquals(setOf("Asia/Shanghai"), episodes.map { it.timeZoneId }.toSet())
        assertEquals(setOf(AiringScheduleAuthority.Official), episodes.map { it.scheduleAuthority }.toSet())
        assertEquals(setOf(100), episodes.map { it.scheduleConfidence }.toSet())
        assertEquals(2026, catalog.premiereYear(294446))
        assertEquals(294446, catalog.identityCandidates("美人余").single().tmdbId)
        assertTrue(catalog.between("2026-10-17", "9999-12-31").none { it.showTmdbId == 294446 })
    }

    @Test
    fun beauty_future_window_uses_the_official_calendar_and_exact_beijing_release_time() {
        val future = catalog.between("2026-10-12", "2026-10-16").filter { it.showTmdbId == 294446 }

        assertEquals((8..17).toList(), future.map { it.episodeNumber })
        assertEquals(setOf("/m3r695bVVi5y4UjbqIdkSkz060f.jpg"), future.map { it.posterPath }.toSet())
        future.forEach { episode ->
            assertEquals("${episode.airDate}T10:00:00Z", episode.releaseAtUtc)
            assertEquals("${episode.airDate}T18:00:00+08:00", episode.releaseAtBeijing)
            assertEquals(
                episode.releaseAtUtc,
                Instant
                    .ofEpochMilli(
                        requireNotNull(scheduledEpochMillis(episode.airDate, "18:00", "Asia/Shanghai")),
                    ).toString(),
            )
            val evidence = episode.scheduleEvidence.single { it.type == "VerifiedAccount" }
            assertEquals("美人余官微", evidence.publisher)
            assertEquals("https://www.sina.cn/news/detail/5352063342217226.html", episode.sourceUrl)
            assertEquals(episode.sourceUrl, evidence.sourceUrl)
            assertEquals("d76ae7f1f9f41e1c75715585e4e54407ba5dd504e5bc0e6a72e345a6aa3254f0", evidence.contentHash)
            assertEquals("reviewed-official-calendar", evidence.extractionMethod)
        }
    }

    @Test
    fun signed_publication_without_beauty_retains_bundled_dates_after_refresh_and_cache_reload() =
        runTest {
            val envelope = signedPublication()
            val settings = MapSettings()
            val remote = catalogWithPublication(envelope, settings)
            val bundledBeauty = remote.series(294446, "美人余")
            val payload = Json { ignoreUnknownKeys = true }.decodeFromString<OfficialSchedulePayload>(envelope.payload)
            assertTrue(payload.schedules.none { it.tmdbId == 294446 })

            assertTrue(remote.refreshIfDue(force = true).getOrThrow())
            assertEquals(bundledBeauty, remote.series(294446, "美人余"))
            val published =
                remote
                    .validate(payload, envelope.revision)
                    .single { it.tmdbId == 272938 }
                    .let { schedule -> schedule.episodes.map { schedule.toEpisode(it, schedule.title) } }
            assertEquals(published, remote.series(272938, "师兄太稳健"))
            assertEquals(envelope.revision, remote.series(272938, "师兄太稳健")?.first()?.scheduleRevision)

            val cached = catalogWithPublication(envelope, settings)
            assertEquals(bundledBeauty, cached.series(294446, "美人余"))
            assertEquals(published, cached.series(272938, "师兄太稳健"))
            assertEquals(envelope.revision, cached.diagnostics().revision)
        }

    @Test
    fun tampered_signed_publications_cannot_replace_bundled_or_cached_schedules() =
        runTest {
            val envelope = signedPublication()
            val corruptions =
                listOf(
                    envelope.copy(payload = envelope.payload + " "),
                    envelope.copy(
                        signature =
                            (if (envelope.signature.first() == 'A') "B" else "A") + envelope.signature.drop(1),
                    ),
                )
            for (corrupted in corruptions) {
                val settings = MapSettings()
                val valid = catalogWithPublication(envelope, settings)
                assertTrue(valid.refreshIfDue(force = true).getOrThrow())
                val verifiedSeries = valid.series(272938, "师兄太稳健")
                val verifiedCache = settings.getStringOrNull("calendar.official.envelope")
                val remote = catalogWithPublication(corrupted, settings)
                val before = remote.series(294446, "美人余")
                assertTrue(remote.refreshIfDue(force = true).isFailure)
                assertEquals(before, remote.series(294446, "美人余"))
                assertEquals(verifiedSeries, remote.series(272938, "师兄太稳健"))
                assertEquals(verifiedCache, settings.getStringOrNull("calendar.official.envelope"))

                settings.putString(
                    "calendar.official.envelope",
                    Json.encodeToString(OfficialScheduleEnvelope.serializer(), corrupted),
                )
                settings.putString("calendar.official.revision", corrupted.revision)
                val cached = catalogWithPublication(corrupted, settings)
                assertEquals(before, cached.series(294446, "美人余"))
                assertEquals("2026-08-23-r2", cached.series(272938, "师兄太稳健")?.first()?.scheduleRevision)
                assertFalse("calendar.official.envelope" in settings.keys)
                assertFalse("calendar.official.revision" in settings.keys)
            }
        }

    @Test
    fun a_valid_production_signature_accepts_wrapped_base64_and_rejects_malformed_input() {
        val envelope = signedPublication()
        assertTrue(
            verifyEd25519Signature(
                CALENDAR_PUBLIC_KEY.chunked(16).joinToString("\r\n "),
                envelope.payload.encodeToByteArray(),
                envelope.signature.chunked(16).joinToString("\n\t"),
            ),
        )
        assertFalse(verifyEd25519Signature("invalid key!", envelope.payload.encodeToByteArray(), envelope.signature))
        assertFalse(
            verifyEd25519Signature(CALENDAR_PUBLIC_KEY, envelope.payload.encodeToByteArray(), "invalid signature!"),
        )
    }

    private fun signedPublication(): OfficialScheduleEnvelope =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            OfficialScheduleEnvelope.serializer(),
            File("src/commonTest/fixtures/calendar-production-r12.json").readText(),
        )

    private fun catalogWithPublication(
        envelope: OfficialScheduleEnvelope,
        settings: MapSettings,
    ): OfficialAiringScheduleCatalog =
        OfficialAiringScheduleCatalog(
            api =
                object : CalendarBackendApi {
                    override val enabled = true

                    override suspend fun fetch(revision: String) = envelope
                },
            settings = settings,
        )

    @Test
    fun animation_with_a_similar_name_is_not_matched() {
        assertNull(catalog.series(218642, "师兄啊师兄"))
    }

    @Test
    fun official_coordinate_replaces_tmdb_date() {
        val tmdb = episode(number = 13, date = "2026-08-26", poster = "/poster.jpg")
        val official = catalog.series(272938, "师兄太稳健").orEmpty().single { it.episodeNumber == 13 }

        val merged = mergeAiringSchedules(listOf(tmdb), listOf(official)).single()

        assertEquals("2026-08-25", merged.airDate)
        assertEquals("/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg", merged.posterPath)
        assertEquals(AiringScheduleAuthority.Official, merged.scheduleAuthority)
    }

    @Test
    fun an_undecorated_official_row_reuses_another_row_poster_from_the_same_show() {
        val tmdb = episode(number = 12, date = "2026-08-24", poster = "/poster.jpg")
        val official =
            catalog
                .series(272938, "师兄太稳健")
                .orEmpty()
                .single { it.episodeNumber == 13 }
                .copy(posterPath = null)

        val merged = mergeAiringSchedules(listOf(tmdb), listOf(official))

        assertEquals("/poster.jpg", merged.single { it.episodeNumber == 13 }.posterPath)
    }

    @Test
    fun homepage_merge_prioritizes_tracked_rows_without_dropping_official_titles() {
        val tracked =
            CalendarEntry(
                episode = episode(number = 16, date = "2026-08-27", poster = "/tracked.jpg"),
                status = LibraryStatus.InProgress,
                followed = true,
            )
        val sameOfficial = tracked.copy(status = LibraryStatus.Unknown, availabilityStale = true)
        val otherOfficial =
            CalendarEntry(
                episode =
                    episode(number = 1, date = "2026-08-27", poster = "/other.jpg").copy(
                        showTmdbId = 287496,
                        showTitle = "花开锦绣",
                    ),
                status = LibraryStatus.Missing,
            )

        val merged =
            mergeCalendarDaysPreferFirst(
                preferred = listOf(CalendarDay("2026-08-27", listOf(tracked))),
                additional = listOf(CalendarDay("2026-08-27", listOf(sameOfficial, otherOfficial))),
            )

        assertEquals(listOf("师兄太稳健", "花开锦绣"), merged.single().entries.map { it.episode.showTitle })
        assertEquals(
            LibraryStatus.InProgress,
            merged
                .single()
                .entries
                .first()
                .status,
        )
    }

    @Test
    fun homepage_merge_keeps_distinct_official_titles_while_tmdb_identity_is_pending() {
        val first =
            CalendarEntry(
                episode =
                    episode(number = 1, date = "2026-08-27", poster = null).copy(
                        showTmdbId = 0,
                        showTitle = "第一部新剧",
                    ),
                status = LibraryStatus.Unknown,
            )
        val second =
            CalendarEntry(
                episode =
                    episode(number = 1, date = "2026-08-27", poster = null).copy(
                        showTmdbId = 0,
                        showTitle = "第二部新剧",
                    ),
                status = LibraryStatus.Unknown,
            )

        val merged =
            mergeCalendarDaysPreferFirst(
                preferred = emptyList(),
                additional = listOf(CalendarDay("2026-08-27", listOf(first, second))),
            )

        assertEquals(listOf("第一部新剧", "第二部新剧"), merged.single().entries.map { it.episode.showTitle })
    }

    private fun episode(
        number: Int,
        date: String,
        poster: String?,
    ) = AiringEpisode(
        showTmdbId = 272938,
        showTitle = "师兄太稳健",
        posterPath = poster,
        seasonNumber = 1,
        episodeNumber = number,
        episodeTitle = null,
        airDate = date,
        origin = ShowOrigin.Domestic,
    )

    @Test
    fun double_digit_revisions_are_newer_than_single_digit_revisions() {
        assertTrue(calendarRevisionIsAtLeast("2026-08-23-r10", "2026-08-23-r2"))
        assertTrue(calendarRevisionIsAtLeast("2026-08-24-r1", "2026-08-23-r99"))
        assertFalse(calendarRevisionIsAtLeast("2026-08-23-r1", "2026-08-23-r2"))
    }

    @Test
    fun a_row_this_build_cannot_read_is_skipped_without_rejecting_the_publication() {
        val readable = publishedSchedule(tmdbId = 1)
        val unreadable = publishedSchedule(tmdbId = 2, releaseMode = "Surprise")

        val kept = catalog.validate(OfficialSchedulePayload(listOf(readable, unreadable)), PUBLISHED_REVISION)

        assertEquals(listOf(1), kept.map { it.tmdbId })
    }

    @Test
    fun a_slashless_fixed_zone_is_a_valid_broadcast_zone() {
        val utc = publishedSchedule(tmdbId = 3, timeZoneId = "UTC")

        val kept = catalog.validate(OfficialSchedulePayload(listOf(utc)), PUBLISHED_REVISION)

        assertEquals(listOf(3), kept.map { it.tmdbId })
    }

    @Test
    fun removed_broadcast_time_is_announced_as_pending_instead_of_null() {
        assertEquals(
            "播出时间由 22:00 调整为 待公布",
            scheduleBroadcastTimeChangeMessage("22:00", "Asia/Shanghai", null, null),
        )
    }

    @Test
    fun newly_announced_broadcast_time_has_a_readable_previous_value() {
        assertEquals(
            "播出时间由 待公布 调整为 20:00",
            scheduleBroadcastTimeChangeMessage(null, null, "20:00", "Asia/Shanghai"),
        )
    }

    @Test
    fun changed_zone_is_visible_even_when_the_clock_time_stays_the_same() {
        assertEquals(
            "播出时间由 22:00（UTC） 调整为 22:00（Asia/Shanghai）",
            scheduleBroadcastTimeChangeMessage("22:00", "UTC", "22:00", "Asia/Shanghai"),
        )
    }

    @Test
    fun unchanged_time_and_zone_do_not_generate_a_change() {
        assertNull(scheduleBroadcastTimeChangeMessage("22:00", "UTC", "22:00", "UTC"))
        assertNull(scheduleBroadcastTimeChangeMessage(null, null, null, null))
    }

    @Test
    fun cached_old_change_messages_also_replace_literal_null() {
        val settings = MapSettings()
        settings.putString(
            "calendar.official.changes.v1",
            Json.encodeToString(
                ListSerializer(OfficialScheduleChange.serializer()),
                listOf(OfficialScheduleChange(1, "测试剧", "播出时间由 22:00 调整为 null", "2026-10-10-r1", 1L)),
            ),
        )
        val cachedCatalog =
            OfficialAiringScheduleCatalog(
                client = HttpClient(MockEngine { error("Unexpected remote refresh") }),
                settings = settings,
            )

        val change = cachedCatalog.recentChanges().single()
        assertEquals("播出时间由 22:00 调整为 待公布", change.displayMessage)
        assertEquals("播出时间由 22:00 调整为 null", change.message)
    }

    private fun publishedSchedule(
        tmdbId: Int,
        timeZoneId: String? = "Asia/Shanghai",
        releaseMode: String = "Scheduled",
    ) = OfficialAiringScheduleCatalog.OfficialSeriesSchedule(
        tmdbId = tmdbId,
        title = "剧集$tmdbId",
        seasonNumber = 1,
        posterPath = null,
        airTime = timeZoneId?.let { "20:00" },
        timeZoneId = timeZoneId,
        platforms = listOf("优酷"),
        accessTier = AiringAccessTier.Free,
        releaseMode = releaseMode,
        sourceUrl = "https://example.com/$tmdbId",
        revision = PUBLISHED_REVISION,
        updatedAt = "2026-09-06T12:00:00+08:00",
        authority = AiringScheduleAuthority.Official,
        confidence = 100,
        evidence =
            listOf(
                OfficialAiringScheduleCatalog.OfficialScheduleEvidence(
                    type = "PlatformPage",
                    publisher = "优酷",
                    sourceUrl = "https://example.com/$tmdbId",
                    capturedAt = "2026-09-06T12:00:00+08:00",
                    contentHash = "0".repeat(64),
                    extractionMethod = "structured-provider-json",
                ),
            ),
        episodes = listOf(OfficialAiringScheduleCatalog.OfficialEpisodeSlot(1, "2026-09-07")),
    )

    private companion object {
        const val PUBLISHED_REVISION = "2026-09-06-r1"
        const val CALENDAR_PUBLIC_KEY = "MCowBQYDK2VwAyEALedMp5RHVd/Cw9v26cP50e0F8hFqKQ/SXm9J9T3oU/4="
    }
}
