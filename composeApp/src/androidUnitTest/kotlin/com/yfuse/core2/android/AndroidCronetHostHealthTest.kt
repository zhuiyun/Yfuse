package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-source route state forgets everything when playback ends, so before this memory existed
 * every new playback of a Cloudflare-tunnelled origin re-probed Cronet and paid the whole open
 * timeout before the OkHttp fallback opened the first byte range.
 */
class AndroidCronetHostHealthTest {
    @Test
    fun a_refused_origin_is_skipped_until_the_cooldown_expires() {
        var now = 0L
        val health = AndroidCronetHostHealth(cooldownMs = 1_000L, nowEpochMs = { now })
        val uri = "https://media.example.test/movie.mkv"

        assertTrue(health.isAvailable(uri))
        health.recordFailure(uri)

        now = 999L
        assertFalse(health.isAvailable(uri))
        now = 1_000L
        assertTrue(health.isAvailable(uri))
    }

    @Test
    fun a_refusal_covers_the_whole_origin_but_no_other_one() {
        val health = AndroidCronetHostHealth(cooldownMs = 1_000L, nowEpochMs = { 0L })
        health.recordFailure("https://media.example.test/movie.mkv")

        assertFalse(health.isAvailable("https://media.example.test/other/episode.mkv"))
        assertTrue(health.isAvailable("https://other.example.test/movie.mkv"))
        // A different port is a different origin: only one of them is behind the failing tunnel.
        assertTrue(health.isAvailable("https://media.example.test:8443/movie.mkv"))
        // A non-HTTP source has no origin to remember and must never be blocked by this memory.
        assertTrue(health.isAvailable("smb://media.example.test/share/movie.mkv"))
    }

    @Test
    fun the_route_state_reports_an_origin_refusal_once() {
        val reported = mutableListOf<String>()
        val routeState = AndroidAdaptiveHttpRouteState(onCronetDisabled = reported::add)

        routeState.disableCronet("https://media.example.test/movie.mkv")
        routeState.disableCronet("https://media.example.test/movie.mkv")
        routeState.disableCronet()

        assertEquals(listOf("https://media.example.test/movie.mkv"), reported)
        assertFalse(routeState.cronetAvailable)
    }

    @Test
    fun a_device_wide_refusal_carries_no_origin_to_remember() {
        val reported = mutableListOf<String>()
        val routeState = AndroidAdaptiveHttpRouteState(onCronetDisabled = reported::add)

        routeState.disableCronet()

        assertTrue(reported.isEmpty())
        assertFalse(routeState.cronetAvailable)
    }
}
