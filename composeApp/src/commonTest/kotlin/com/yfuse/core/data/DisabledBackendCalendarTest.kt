package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.OfficialScheduleEnvelope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisabledBackendCalendarTest {
    @Test
    fun disabled_calendar_keeps_local_rows_without_refresh_attempts_or_retry_timestamps() =
        runTest {
            val settings = MapSettings()
            val api =
                object : CalendarBackendApi {
                    override val enabled = false

                    override suspend fun fetch(revision: String): OfficialScheduleEnvelope? =
                        error("Unexpected request")
                }
            val catalog = OfficialAiringScheduleCatalog(api, settings)
            val before = catalog.series(272938, "fallback")
            repeat(2) { assertFalse(catalog.refreshIfDue(force = true).getOrThrow()) }
            assertEquals(before, catalog.series(272938, "fallback"))
            assertFalse(catalog.diagnostics().remoteConfigured)
            assertTrue(settings.keys.isEmpty())
        }
}
