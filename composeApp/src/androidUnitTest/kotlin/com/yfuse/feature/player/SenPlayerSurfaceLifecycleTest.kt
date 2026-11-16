package com.yfuse.feature.player

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenPlayerSurfaceLifecycleTest {
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
