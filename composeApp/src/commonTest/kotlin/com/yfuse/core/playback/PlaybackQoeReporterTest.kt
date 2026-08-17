package com.yfuse.core.playback

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.createAccountClient
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeCodecFamily
import com.yfuse.watch.protocol.QoeContainerFamily
import com.yfuse.watch.protocol.QoeDeviceFamily
import com.yfuse.watch.protocol.QoeDynamicRange
import com.yfuse.watch.protocol.QoeEngine
import com.yfuse.watch.protocol.QoePlatformApiBucket
import com.yfuse.watch.protocol.QoePlaybackMethod
import com.yfuse.watch.protocol.QoeResolutionBucket
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQoeReporterTest {
    @Test
    fun opt_out_never_sends_or_persists_a_report() =
        runTest {
            var requests = 0
            val settings = MapSettings()
            val preferences = PlaybackPreferences(settings)
            val client =
                createAccountClient(
                    MockEngine {
                        requests += 1
                        respond("", HttpStatusCode.Accepted)
                    },
                )
            val reporter = PlaybackQoeReporter(settings, preferences, client, "1.0.0")

            try {
                assertFalse(reporter.submit(report()))
                assertEquals(0, requests)
                assertEquals(0, reporter.pendingReports())
            } finally {
                client.close()
            }
        }

    @Test
    fun failed_reports_are_bounded_and_flushed_before_the_next_report() =
        runTest {
            var requests = 0
            val settings = MapSettings()
            val preferences = PlaybackPreferences(settings).also { it.setAnonymousQoeSharing(true) }
            val client =
                createAccountClient(
                    MockEngine { request ->
                        requests += 1
                        assertNull(request.headers[HttpHeaders.Authorization])
                        respond(
                            "",
                            if (requests == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Accepted,
                        )
                    },
                )
            val reporter = PlaybackQoeReporter(settings, preferences, client, "1.0.0")

            try {
                assertFalse(reporter.submit(report()))
                assertEquals(1, reporter.pendingReports())
                assertTrue(reporter.submit(report().copy(engine = QoeEngine.Mpv)))
                assertEquals(3, requests)
                assertEquals(0, reporter.pendingReports())
            } finally {
                client.close()
            }
        }

    @Test
    fun disabling_consent_removes_a_queued_report() =
        runTest {
            val settings = MapSettings()
            val preferences = PlaybackPreferences(settings).also { it.setAnonymousQoeSharing(true) }
            val client = createAccountClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
            val reporter = PlaybackQoeReporter(settings, preferences, client, "1.0.0")

            try {
                assertFalse(reporter.submit(report()))
                assertEquals(1, reporter.pendingReports())
                preferences.setAnonymousQoeSharing(false)
                assertEquals(0, reporter.pendingReports())
            } finally {
                client.close()
            }
        }

    @Test
    fun consent_revoked_during_delivery_cannot_recreate_the_outbox() =
        runTest {
            val settings = MapSettings()
            val preferences = PlaybackPreferences(settings).also { it.setAnonymousQoeSharing(true) }
            val client =
                createAccountClient(
                    MockEngine {
                        preferences.setAnonymousQoeSharing(false)
                        respond("", HttpStatusCode.ServiceUnavailable)
                    },
                )
            val reporter = PlaybackQoeReporter(settings, preferences, client, "1.0.0")

            try {
                assertFalse(reporter.submit(report()))
                assertEquals(0, reporter.pendingReports())
            } finally {
                client.close()
            }
        }

    private fun report() =
        AnonymousPlaybackQoeReport(
            appVersion = "1.0.0",
            engine = QoeEngine.Exo,
            platformApi = QoePlatformApiBucket.Api35Plus,
            deviceFamily = QoeDeviceFamily.Qualcomm,
            videoCodec = QoeCodecFamily.Hevc,
            audioCodec = QoeCodecFamily.Aac,
            container = QoeContainerFamily.Mkv,
            resolution = QoeResolutionBucket.UltraHd,
            dynamicRange = QoeDynamicRange.Hdr10,
            playbackMethod = QoePlaybackMethod.Direct,
            startupUpperBoundMs = 2_500,
            observedUpperBoundSeconds = 60,
            rebufferEventsUpperBound = 1,
            droppedFramesPerMinuteUpperBound = 5,
            avSyncAbsoluteUpperBoundMs = 40,
        )
}
