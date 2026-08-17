package com.yfuse.watch.qoe

import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeCodecFamily
import com.yfuse.watch.protocol.QoeContainerFamily
import com.yfuse.watch.protocol.QoeDeviceFamily
import com.yfuse.watch.protocol.QoeDynamicRange
import com.yfuse.watch.protocol.QoeEngine
import com.yfuse.watch.protocol.QoePlatformApiBucket
import com.yfuse.watch.protocol.QoePlaybackMethod
import com.yfuse.watch.protocol.QoeResolutionBucket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class QoeRoutesTest {
    @Test
    fun valid_reports_are_accepted_and_only_aggregate_counts_are_stored() =
        testApplication {
            val backend = QoeAggregateBackend.inMemory()
            val report = report()
            application {
                routing {
                    qoeRoutes(backend = backend, dayUtc = { "2026-08-17" })
                }
            }

            repeat(2) {
                val response =
                    client.post("/api/v1/qoe") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json)
                        setBody(Json.encodeToString(report))
                    }
                assertEquals(HttpStatusCode.Accepted, response.status)
            }

            assertEquals(2L, backend.count("2026-08-17", report))
            backend.close()
        }

    @Test
    fun arbitrary_numeric_values_are_rejected() =
        testApplication {
            val backend = QoeAggregateBackend.inMemory()
            application { routing { qoeRoutes(backend) } }
            val invalid =
                Json
                    .encodeToString(report())
                    .replace(
                        "\"startupUpperBoundMs\":2500",
                        "\"startupUpperBoundMs\":1234",
                    )

            val response =
                client.post("/api/v1/qoe") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(invalid)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            backend.close()
        }

    private fun report() =
        AnonymousPlaybackQoeReport(
            appVersion = "1.2.3",
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
