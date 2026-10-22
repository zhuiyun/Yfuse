package com.yfuse.watch.qoe

import com.yfuse.watch.calendarFailureCategory
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeCodecFamily
import com.yfuse.watch.protocol.QoeContainerFamily
import com.yfuse.watch.protocol.QoeDeviceFamily
import com.yfuse.watch.protocol.QoeDynamicRange
import com.yfuse.watch.protocol.QoeEngine
import com.yfuse.watch.protocol.QoePlatformApiBucket
import com.yfuse.watch.protocol.QoePlaybackMethod
import com.yfuse.watch.protocol.QoeResolutionBucket
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QoeAggregateBackendTest {
    @Test
    fun free_form_versions_share_one_bucket() {
        QoeAggregateBackend.inMemory().use { backend ->
            backend.record(DAY, report("1.1.5"))
            backend.record(DAY, report("x-crafted-1"))
            backend.record(DAY, report("x-crafted-2"))
            assertEquals(1L, backend.count(DAY, report("1.1.5")))
            // Both crafted versions landed in the same `other` row instead of one row each.
            assertEquals(2L, backend.count(DAY, report(QoeAggregateBackend.OTHER_APP_VERSION)))
            assertEquals(2L, backend.count(DAY, report("anything-else")))
        }
        assertEquals("1.10.200", QoeAggregateBackend.boundedAppVersion("1.10.200"))
        assertEquals("other", QoeAggregateBackend.boundedAppVersion("1.1.5-debug"))
    }

    @Test
    fun a_full_day_keeps_counting_known_rows_and_drops_new_ones() {
        QoeAggregateBackend.inMemory(maxRowsPerDay = 2).use { backend ->
            assertTrue(backend.record(DAY, report("1.0.0")))
            assertTrue(backend.record(DAY, report("1.0.1")))
            assertFalse(backend.record(DAY, report("1.0.2")))
            assertTrue(backend.record(DAY, report("1.0.0")))
            assertEquals(2L, backend.count(DAY, report("1.0.0")))
            assertEquals(0L, backend.count(DAY, report("1.0.2")))
            // The cap is per day.
            assertTrue(backend.record("2026-10-08", report("1.0.2")))
        }
    }

    @Test
    fun ingestion_status_names_a_category_never_the_exception_text() {
        val secretUrl = "https://example.invalid/schedule?api_key=secret"
        assertEquals("network", calendarFailureCategory(IOException("GET $secretUrl failed")))
        assertEquals("timeout", calendarFailureCategory(IllegalStateException("x", HttpTimeoutException(secretUrl))))
        assertEquals("invalid_data", calendarFailureCategory(IllegalArgumentException(secretUrl)))
        assertEquals("internal", calendarFailureCategory(RuntimeException(secretUrl)))
    }

    private fun report(appVersion: String) =
        AnonymousPlaybackQoeReport(
            appVersion = appVersion,
            engine = QoeEngine.Exo,
            platformApi = QoePlatformApiBucket.Api35Plus,
            deviceFamily = QoeDeviceFamily.GoogleTensor,
            videoCodec = QoeCodecFamily.Hevc,
            audioCodec = QoeCodecFamily.Other,
            container = QoeContainerFamily.Mkv,
            resolution = QoeResolutionBucket.UltraHd,
            dynamicRange = QoeDynamicRange.Hdr10,
            playbackMethod = QoePlaybackMethod.Direct,
            startupUpperBoundMs = 2_500,
            observedUpperBoundSeconds = 60,
            rebufferEventsUpperBound = 1,
            droppedFramesPerMinuteUpperBound = 5,
            avSyncAbsoluteUpperBoundMs = 40,
            networkRecoveryAttemptsUpperBound = 1,
            networkRecoverySuccessesUpperBound = 1,
        )

    private companion object {
        const val DAY = "2026-10-07"
    }
}
