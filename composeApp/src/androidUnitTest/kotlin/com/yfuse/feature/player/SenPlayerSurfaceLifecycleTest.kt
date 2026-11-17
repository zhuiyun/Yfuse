package com.yfuse.feature.player

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenPlayerSurfaceLifecycleTest {
    @Test
    fun rejected_valid_output_retires_once_and_requests_recreation() {
        var closed = 0
        assertFalse(bindSenPlayerSurface({ throw IOException("SenPlayer command failed (-6)") }, { closed++ }))
        assertEquals(1, closed)
        assertTrue(bindSenPlayerSurface({}, { error("accepted output must retain session") }))
    }

    @Test
    fun surface_rebuild_preserves_audio_primary_secondary_and_explicit_pending_off() {
        val state =
            PlaybackState(
                audioTracks = listOf(EngineTrack("3", "Audio", "en", true)),
                subtitleTracks = listOf(EngineTrack("4", "Primary", "en", true)),
                secondarySubtitleTrackId = "external:2",
            )
        val selected = senPlayerSurfaceTracks("source-a", state, emptyMap())
        assertEquals("source-a", selected.itemId)
        assertEquals("3", selected.audioId)
        assertEquals(mapOf(1 to "4", 2 to "external:2"), selected.subtitles)
        val pending = senPlayerSurfaceTracks("source-a", state, mapOf(1 to EngineTrack.OFF, 2 to "external:5"))
        assertEquals(mapOf(1 to EngineTrack.OFF, 2 to "external:5"), pending.subtitles)
        assertEquals("7", senPlayerSurfaceTracks("source-a", state, emptyMap(), "7").audioId)
    }

    @Test
    fun supported_detach_pauses_before_removing_output_and_keeps_the_session() {
        val calls = mutableListOf<String>()
        assertFalse(
            detachSenPlayerSurface(
                pause = { calls += "pause" },
                detach = { calls += "detach" },
                close = { calls += "close" },
            ),
        )
        assertEquals(listOf("pause", "detach"), calls)
    }

    @Test
    fun rejected_empty_output_releases_the_session_in_order() {
        val calls = mutableListOf<String>()
        assertTrue(
            detachSenPlayerSurface(
                pause = { calls += "pause" },
                detach = {
                    calls += "detach"
                    throw IOException("SenPlayer command failed (-6)")
                },
                close = { calls += "close" },
            ),
        )
        assertEquals(listOf("pause", "detach", "close"), calls)
    }

    @Test
    fun other_failures_and_failed_release_are_not_hidden() {
        listOf(IOException("network"), IOException("SenPlayer command failed (-3)"), IllegalStateException("license"))
            .forEach { failure ->
                val actual =
                    assertFailsWith<Exception> {
                        detachSenPlayerSurface({}, { throw failure }, { error("must not recreate") })
                    }
                assertTrue(actual === failure)
            }
        assertFailsWith<IllegalStateException> {
            detachSenPlayerSurface(
                {},
                { throw IOException("SenPlayer command failed (-6)") },
                { error("release failed") },
            )
        }
    }
}
