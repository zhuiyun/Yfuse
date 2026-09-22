package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackReportOriginTest {
    @Test
    fun old_timeline_entries_remain_unknown_across_repeated_restores() {
        val old = "at=1234 positionMs=90000 engine=YCore2Native"
        val restored = restorePlaybackTimelineOrigin(old)
        assertTrue(
            restored.startsWith("timelineVersion=legacy-unknown appVersion=unknown appBuild=unknown process=unknown "),
        )
        assertTrue(restored.endsWith(old))
        assertEquals(restored, restorePlaybackTimelineOrigin(restored))
        val known = "timelineVersion=2 appVersion=1.0.43_205 appBuild=abcdef123 process=process-a at=1234"
        assertEquals(known, restorePlaybackTimelineOrigin(known))
    }

    @Test
    fun restored_reports_preserve_the_original_build_instead_of_inheriting_the_export_build() {
        val old = "engine.actual=YCore2Native\n"
        val restored = restorePlaybackReportOrigin(old)
        assertTrue(restored.startsWith("session.metadata=legacy-unknown\nsession.appVersion=unknown\n"))
        assertEquals(restored, restorePlaybackReportOrigin(restored))
        val known =
            "session.metadata=2\nsession.appVersion=1.0.43 (205)\n" +
                "session.appBuild=abcdef123\nsession.process=process-a\n$old"
        assertEquals(known, restorePlaybackReportOrigin(known))
    }

    @Test
    fun selected_subtitle_formats_are_recorded_without_track_urls_or_labels() {
        val externalId = "https://private.example/subtitle?token=secret"
        val state =
            PlaybackState(
                subtitleTracks =
                    listOf(
                        EngineTrack("track-0", "Private title", "zh", selected = true, codec = "pgs"),
                        EngineTrack(
                            externalId,
                            "Private filename",
                            "en",
                            selected = false,
                            codec = "application/x-subrip",
                        ),
                    ),
                secondarySubtitleTrackId = externalId,
            )
        val result = playbackSubtitleDiagnosticSelection(state)
        assertEquals("primary[0:zh:pgs],secondary[1:en:application/x-subrip]", result)
        assertFalse(result.contains("private", ignoreCase = true))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("track-0"))
    }
}
