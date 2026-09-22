package com.yfuse.watch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
