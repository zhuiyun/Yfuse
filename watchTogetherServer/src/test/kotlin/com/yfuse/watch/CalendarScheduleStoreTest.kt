package com.yfuse.watch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CalendarScheduleStoreTest {
    @Test
    fun sqlite_round_trips_current_revision_across_reopen() {
        val directory = Files.createTempDirectory("yfuse-calendar-store").toFile()
        val database = directory.resolve("calendar.db")
        val publication = publication("2026-08-27-r1")

        try {
            CalendarScheduleStore.sqlite(database).use { store ->
                assertTrue(store.replace(publication))
                assertFalse(store.replace(publication))
                assertEquals(publication, store.current())
            }
            CalendarScheduleStore.sqlite(database).use { reopened ->
                assertEquals(publication, reopened.current())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejects_revision_rollback_without_replacing_current_data() {
        CalendarScheduleStore.inMemory().use { store ->
            val current = publication("2026-08-27-r2")
            assertTrue(store.replace(current))

            assertFailsWith<IllegalArgumentException> {
                store.replace(publication("2026-08-27-r1"))
            }
            assertEquals(current, store.current())
        }
    }

    @Test
    fun unchanged_revision_reuses_an_immutable_snapshot() {
        CalendarScheduleStore.inMemory().use { store ->
            val publication = publication("2026-08-27-r1")
            store.replace(publication)
            val first = assertNotNull(store.current())

            assertSame(first, store.current())
            assertImmutable(first.schedules)
            first.schedules.forEach { series ->
                assertImmutable(series.platforms)
                assertImmutable(series.evidence)
                assertImmutable(series.episodes)
            }
            assertEquals(publication, store.current())
        }
    }

    @Test
    fun published_data_does_not_retain_callers_mutable_lists() {
        val original = publication("2026-08-27-r1")
        val series = original.schedules.single()
        val platforms = series.platforms.toMutableList()
        val evidence = series.evidence.toMutableList()
        val episodes = series.episodes.toMutableList()
        val schedules = mutableListOf(series.copy(platforms = platforms, evidence = evidence, episodes = episodes))

        CalendarScheduleStore.inMemory().use { store ->
            store.replace(original.copy(schedules = schedules))
            platforms.clear()
            evidence.clear()
            episodes.clear()
            schedules.clear()

            assertEquals(original, store.current())
        }
    }

    @Test
    fun open_reader_detects_another_connections_publications_and_pruning() {
        val directory = Files.createTempDirectory("yfuse-calendar-store-external").toFile()
        val database = directory.resolve("calendar.db")
        try {
            CalendarScheduleStore.sqlite(database).use { reader ->
                CalendarScheduleStore.sqlite(database).use { writer ->
                    assertNull(reader.current())
                    writer.replace(publication("2026-08-27-r1"))
                    val first = assertNotNull(reader.current())
                    assertEquals("2026-08-27-r1", first.revision)
                    assertSame(first, reader.current())

                    // Advance beyond the retention window so the first cached revision is removed.
                    for (revision in 2..14) {
                        writer.replace(publication("2026-08-27-r$revision"))
                    }
                    val newest = assertNotNull(reader.current())
                    assertEquals(publication("2026-08-27-r14"), newest)
                    assertSame(newest, reader.current())
                    assertEquals(publication("2026-08-27-r1"), first)

                    val empty = publication("2026-08-27-r15").copy(schedules = emptyList())
                    writer.replace(empty)
                    assertEquals(empty, reader.current())
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cold_snapshot_groups_episode_and_evidence_rows_without_mixing_series() {
        val directory = Files.createTempDirectory("yfuse-calendar-store-bulk").toFile()
        val database = directory.resolve("calendar.db")
        val base = publication("2026-08-27-r1")
        val first = base.schedules.single()
        val second =
            first.copy(
                tmdbId = first.tmdbId + 1,
                title = "Second series",
                seasonNumber = 2,
                platforms = listOf("Other platform"),
                evidence =
                    listOf(
                        first.evidence.single().copy(publisher = "Second publisher"),
                        first.evidence.single().copy(publisher = "Third publisher"),
                    ),
                episodes =
                    listOf(
                        CalendarEpisode(1, "2026-08-28"),
                        CalendarEpisode(3, "2026-08-30"),
                    ),
            )
        val expected = base.copy(schedules = listOf(first, second))
        val unordered =
            base.copy(
                schedules = listOf(second.copy(episodes = second.episodes.reversed()), first),
            )
        try {
            CalendarScheduleStore.sqlite(database).use { writer -> writer.replace(unordered) }
            CalendarScheduleStore.sqlite(database).use { reader ->
                val snapshot = assertNotNull(reader.current())
                assertEquals(expected, snapshot)
                assertSame(snapshot, reader.current())
                snapshot.schedules.forEach { series ->
                    assertImmutable(series.platforms)
                    assertImmutable(series.evidence)
                    assertImmutable(series.episodes)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun <T> assertImmutable(items: List<T>) {
        assertFailsWith<UnsupportedOperationException> {
            (items as MutableList<T>).clear()
        }
    }

    private fun publication(revision: String): CalendarPublication =
        CalendarPublication(
            revision = revision,
            generatedAt = "2026-08-27T04:00:00Z",
            schedules =
                DEFAULT_CALENDAR_SCHEDULES.map { schedule ->
                    schedule.copy(revision = revision)
                },
        )
}
