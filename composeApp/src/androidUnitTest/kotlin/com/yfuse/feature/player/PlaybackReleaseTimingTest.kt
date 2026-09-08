package com.yfuse.feature.player

import com.yfuse.core.logging.DiagnosticLevel
import com.yfuse.core.logging.prepareDiagnosticLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackReleaseTimingTest {
    @Test
    fun diagnostic_export_preserves_release_evidence_under_normalized_attribute_names() {
        var now = 0L
        val timing = PlaybackReleaseTiming { now }
        assertFailsWith<IllegalStateException> {
            timing.stage("nativeDestroy") {
                now += 8_000_000L
                error("native failure")
            }
        }
        val prepared =
            prepareDiagnosticLog(
                level = DiagnosticLevel.Info,
                category = "player.release",
                event = "release_finished",
                message = "Playback release stage timings",
                throwable = null,
                attributes = timing.attributes() + mapOf("mainThread" to "true", "completed" to "false"),
                threadName = "main",
            )
        assertEquals("true", prepared.attributes["mainthread"])
        assertEquals("8", prepared.attributes["nativedestroyms"])
        assertEquals("nativeDestroy", prepared.attributes["failedstages"])
        assertEquals("false", prepared.attributes["completed"])
        assertEquals("main", prepared.attributes["thread"])
    }

    @Test
    fun native_failure_keeps_elapsed_stage_evidence_and_propagates_to_the_existing_error_handler() {
        var now = 0L
        val timing = PlaybackReleaseTiming { now }
        timing.stage("stop") { now += 3_000_000L }
        assertFailsWith<IllegalStateException> {
            timing.stage("nativeDestroy") {
                now += 290_000_000L
                error("native failure")
            }
        }
        assertEquals(
            mapOf("stopMs" to "3", "nativeDestroyMs" to "290", "totalMs" to "293", "failedStages" to "nativeDestroy"),
            timing.attributes(),
        )
    }
}
