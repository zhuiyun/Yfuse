package com.yfuse.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackDiagnosticTraceTest {
    @Test
    fun startup_stages_can_be_joined_after_diagnostic_redaction_without_exposing_the_session() {
        val trace = playbackDiagnosticTrace("private-playback-session")
        assertEquals(trace, playbackDiagnosticTrace("private-playback-session"))
        assertNotEquals(trace, playbackDiagnosticTrace("another-playback-session"))
        assertTrue(trace.matches(Regex("p[0-9a-f]{24}")))
        assertEquals(trace, redactDiagnosticAttributes(mapOf("playbackTrace" to trace))["playbackTrace"])
        assertEquals(
            "<redacted>",
            redactDiagnosticAttributes(mapOf("sessionId" to "private-playback-session"))["sessionId"],
        )
    }

    @Test
    fun unknown_sessions_do_not_share_a_fabricated_trace() {
        assertEquals("", playbackDiagnosticTrace(null))
        assertEquals("", playbackDiagnosticTrace(""))
    }
}
