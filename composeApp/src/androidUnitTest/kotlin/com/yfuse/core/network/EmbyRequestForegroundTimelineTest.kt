package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [appLeftForegroundSince] backs the `appBackgrounded` attribute on `api_request_timing`
 * (see [EmbyApiRequestTiming] in HttpClientFactory.android.kt) and the analogous one on
 * `LibraryStore`'s `load_completed`. Both exist because a frozen background process can make a
 * request "take" 41-86 s, or a library load 92 s, that were mostly a frozen gap rather than a
 * real stall - and that gap can start and end in the foreground, so only a transition timestamp
 * (not the state sampled at either end) catches it.
 */
class EmbyRequestForegroundTimelineTest {
    @Test
    fun stays_in_the_foreground_throughout_is_not_flagged() {
        assertFalse(
            appLeftForegroundSince(
                sinceEpochMs = 10_000L,
                currentlyForeground = true,
                // The last transition (app originally opened) happened well before this interval.
                lastTransitionEpochMs = 1_000L,
            ),
        )
    }

    @Test
    fun currently_backgrounded_is_always_flagged() {
        assertTrue(
            appLeftForegroundSince(
                sinceEpochMs = 10_000L,
                currentlyForeground = false,
                lastTransitionEpochMs = 5_000L,
            ),
        )
    }

    @Test
    fun a_round_trip_to_background_and_back_during_the_interval_is_flagged() {
        // The exact bug: foreground when the request started AND when it finished, but the most
        // recent transition (back to foreground) happened after the interval began - meaning it
        // must have left the foreground for some of it, most likely a frozen process.
        assertTrue(
            appLeftForegroundSince(
                sinceEpochMs = 10_000L,
                currentlyForeground = true,
                lastTransitionEpochMs = 15_000L,
            ),
        )
    }

    @Test
    fun a_transition_before_the_interval_started_does_not_flag_it() {
        assertFalse(
            appLeftForegroundSince(
                sinceEpochMs = 10_000L,
                currentlyForeground = true,
                lastTransitionEpochMs = 9_999L,
            ),
        )
    }

    @Test
    fun a_transition_exactly_at_the_interval_start_flags_it() {
        // The transition into foreground could be what the interval's own start raced against;
        // treat the boundary as inside the interval rather than risk the common off-by-one miss.
        assertTrue(
            appLeftForegroundSince(
                sinceEpochMs = 10_000L,
                currentlyForeground = true,
                lastTransitionEpochMs = 10_000L,
            ),
        )
    }
}
