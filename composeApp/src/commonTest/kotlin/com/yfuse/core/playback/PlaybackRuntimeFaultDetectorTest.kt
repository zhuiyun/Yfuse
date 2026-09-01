package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The detector is the only thing that decides to move playback to another backend, so every
 * threshold here is a decision a viewer feels: report too early and one dropped frame swaps
 * their player mid-scene, too late and they sit in front of a black picture.
 */
class PlaybackRuntimeFaultDetectorTest {
    @Test
    fun healthy_playback_reports_nothing() {
        val detector = detector()

        assertNull(detector.observe(observation(now = 2_000L, positionMs = 2_000L, videoReady = true)))
        assertNull(detector.observe(observation(now = 30_000L, positionMs = 30_000L, videoReady = true)))
    }

    @Test
    fun a_missing_first_frame_is_reported_after_the_startup_budget() {
        val detector = detector()

        assertNull(detector.observe(observation(now = 14_000L, positionMs = 0L)))
        val fault = detector.observe(observation(now = 15_000L, positionMs = 0L))

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, assertNotNull(fault).kind)
        assertEquals(PlaybackFailureKind.Decoder, fault.kind.failureKind)
    }

    @Test
    fun a_first_frame_that_arrives_cancels_the_startup_clock() {
        val detector = detector()

        detector.observe(observation(now = 14_000L, positionMs = 0L))

        assertNull(
            detector.observe(observation(now = 20_000L, positionMs = 500L, videoReady = true)),
            "video arrived, so the startup budget no longer applies",
        )
    }

    @Test
    fun a_synthetic_clock_does_not_count_as_first_frame_evidence() {
        val detector = detector()

        assertNull(
            detector.observe(
                observation(
                    now = 14_000L,
                    positionMs = 14_000L,
                    playing = false,
                    buffering = true,
                ),
            ),
        )
        val fault =
            detector.observe(
                observation(
                    now = 15_000L,
                    positionMs = 15_000L,
                    playing = false,
                    buffering = true,
                ),
            )

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, assertNotNull(fault).kind)
    }

    @Test
    fun position_stall_is_not_armed_before_verified_output() {
        val detector = detector()

        detector.observe(observation(now = 1_000L, positionMs = 1_000L))

        assertNull(
            detector.observe(observation(now = 13_500L, positionMs = 1_000L)),
            "a parsed format or synthetic clock is not proof that playback started",
        )
    }

    @Test
    fun video_that_disappears_after_verified_output_is_reported_after_the_grace_window() {
        val detector = detector()

        assertNull(detector.observe(observation(now = 1_000L, positionMs = 1_000L, videoReady = true)))
        assertNull(detector.observe(observation(now = 4_000L, positionMs = 4_000L)))
        assertNull(
            detector.observe(observation(now = 7_000L, positionMs = 7_000L)),
            "three seconds of missing output is inside the grace window",
        )
        val fault = detector.observe(observation(now = 8_000L, positionMs = 8_000L))

        assertEquals(PlaybackRuntimeFaultKind.VideoOutputMissing, assertNotNull(fault).kind)
        assertEquals(PlaybackFailureKind.Renderer, fault.kind.failureKind)
    }

    @Test
    fun synthetic_progress_before_first_frame_remains_on_the_startup_budget() {
        val detector = detector()

        assertNull(detector.observe(observation(now = 4_000L, positionMs = 4_000L)))
        assertNull(
            detector.observe(observation(now = 8_000L, positionMs = 8_000L)),
            "a verifiable backend's clock is not proof that its decoder rendered output",
        )
        assertEquals(
            PlaybackRuntimeFaultKind.StartupTimeout,
            assertNotNull(detector.observe(observation(now = 15_000L, positionMs = 15_000L))).kind,
        )
    }

    /**
     * The regression this component was written without.
     *
     * The grace clocks used to start inside the branch that reports and were cleared only by a
     * pause, buffer, error or end. A condition that recovered therefore kept its original
     * timestamp, so the *next* dropout was measured from the first one — already past the grace
     * window — and reported on the very first observation. One brief glitch, then any momentary
     * dropout, and the viewer's engine was swapped underneath them.
     */
    @Test
    fun the_grace_window_restarts_after_output_recovers() {
        val detector = detector()

        detector.observe(observation(now = 4_000L, positionMs = 4_000L))
        assertNull(
            detector.observe(observation(now = 6_000L, positionMs = 6_000L, videoReady = true)),
            "output recovered",
        )
        assertNull(
            detector.observe(observation(now = 24_000L, positionMs = 24_000L, videoReady = true)),
        )

        assertNull(
            detector.observe(observation(now = 26_000L, positionMs = 26_000L)),
            "a fresh dropout starts a fresh grace window instead of reporting immediately",
        )
        assertNull(detector.observe(observation(now = 29_000L, positionMs = 29_000L)))
        assertEquals(
            PlaybackRuntimeFaultKind.VideoOutputMissing,
            assertNotNull(detector.observe(observation(now = 30_500L, positionMs = 30_500L))).kind,
            "and still reports once that fresh window elapses",
        )
    }

    @Test
    fun progress_without_verifiable_audio_is_reported() {
        val detector = detector()

        detector.observe(audioObservation(now = 4_000L, positionMs = 4_000L))
        val fault = detector.observe(audioObservation(now = 9_000L, positionMs = 9_000L))

        assertEquals(PlaybackRuntimeFaultKind.AudioOutputMissing, assertNotNull(fault).kind)
        assertEquals(PlaybackFailureKind.AudioSink, fault.kind.failureKind)
    }

    @Test
    fun a_stalled_position_is_reported_only_once_the_stall_budget_passes() {
        val detector = detector()

        detector.observe(observation(now = 1_000L, positionMs = 1_000L, videoReady = true))
        assertNull(detector.observe(observation(now = 12_000L, positionMs = 1_000L, videoReady = true)))
        val fault = detector.observe(observation(now = 13_500L, positionMs = 1_000L, videoReady = true))

        assertEquals(PlaybackRuntimeFaultKind.PositionStalled, assertNotNull(fault).kind)
    }

    @Test
    fun requested_but_paused_playback_never_becomes_a_position_stall() {
        val detector = detector()

        detector.observe(observation(now = 1_000L, positionMs = 1_000L, videoReady = true))
        assertNull(
            detector.observe(
                observation(
                    now = 20_000L,
                    positionMs = 1_000L,
                    playing = false,
                    videoReady = true,
                ),
            ),
            "audio focus may pause actual output while a backend still exposes requested intent",
        )
        assertNull(
            detector.observe(
                observation(
                    now = 60_000L,
                    positionMs = 1_000L,
                    playing = false,
                    videoReady = true,
                ),
            ),
        )
        assertNull(
            detector.observe(
                observation(
                    now = 60_500L,
                    positionMs = 1_000L,
                    playing = true,
                    videoReady = true,
                ),
            ),
            "resuming starts a fresh stall budget",
        )
        assertNull(
            detector.observe(
                observation(
                    now = 72_000L,
                    positionMs = 1_000L,
                    playing = true,
                    videoReady = true,
                ),
            ),
        )
        assertEquals(
            PlaybackRuntimeFaultKind.PositionStalled,
            assertNotNull(
                detector.observe(
                    observation(
                        now = 73_000L,
                        positionMs = 1_000L,
                        playing = true,
                        videoReady = true,
                    ),
                ),
            ).kind,
        )
    }

    @Test
    fun a_backward_seek_restarts_the_stall_clock() {
        val detector = detector()

        detector.observe(observation(now = 1_000L, positionMs = 60_000L, videoReady = true))
        detector.observe(observation(now = 2_000L, positionMs = 10_000L, videoReady = true))

        assertNull(
            detector.observe(observation(now = 13_000L, positionMs = 10_000L, videoReady = true)),
            "the stall budget runs from the seek, not from the position before it",
        )
    }

    @Test
    fun buffering_and_pause_suppress_reporting_and_clear_the_clocks() {
        val detector = detector()

        detector.observe(observation(now = 4_000L, positionMs = 4_000L))
        assertNull(detector.observe(observation(now = 6_000L, positionMs = 6_000L, buffering = true)))
        assertNull(
            detector.observe(observation(now = 7_000L, positionMs = 7_000L)),
            "the grace window restarts after a buffer, so this is not yet a fault",
        )
    }

    @Test
    fun continuous_initial_buffering_still_times_out_and_enters_fallback() {
        val detector = detector()

        assertNull(
            detector.observe(
                observation(now = 14_000L, positionMs = 0L, buffering = true),
            ),
        )
        val fault =
            detector.observe(
                observation(now = 15_000L, positionMs = 0L, buffering = true),
            )

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, assertNotNull(fault).kind)
    }

    @Test
    fun startup_timeout_does_not_require_a_backend_to_identify_the_output_format() {
        val detector = detector()

        val fault =
            detector.observe(
                observation(
                    now = 15_000L,
                    positionMs = 0L,
                    buffering = true,
                    videoOutputVerifiable = false,
                ),
            )

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, assertNotNull(fault).kind)
    }

    @Test
    fun a_paused_or_ended_session_never_reports() {
        val paused = detector()
        val ended = detector()

        assertNull(paused.observe(observation(now = 60_000L, positionMs = 0L, playbackRequested = false)))
        assertNull(ended.observe(observation(now = 60_000L, positionMs = 0L, ended = true)))
    }

    @Test
    fun an_existing_error_is_left_to_the_backend() {
        val detector = detector()

        assertNull(
            detector.observe(observation(now = 60_000L, positionMs = 0L, errorPresent = true)),
            "the backend reported for itself; this detector only covers silent failures",
        )
    }

    @Test
    fun only_the_first_fault_of_a_binding_is_reported() {
        val detector = detector()

        detector.observe(observation(now = 14_000L, positionMs = 0L))
        assertNotNull(detector.observe(observation(now = 16_000L, positionMs = 0L)))

        assertNull(
            detector.observe(observation(now = 40_000L, positionMs = 0L)),
            "a handover follows the first report; a second would race it",
        )
    }

    @Test
    fun content_without_video_is_never_judged_on_missing_video() {
        val detector = detector()

        assertNull(
            detector.observe(
                observation(now = 30_000L, positionMs = 30_000L, videoExpected = false),
            ),
        )
    }

    @Test
    fun stalled_audio_only_playback_is_reported() {
        val detector = detector()

        detector.observe(
            observation(
                now = 1_000L,
                positionMs = 1_000L,
                videoExpected = false,
                audioExpected = true,
            ),
        )
        val fault =
            detector.observe(
                observation(
                    now = 13_500L,
                    positionMs = 1_000L,
                    videoExpected = false,
                    audioExpected = true,
                ),
            )

        assertEquals(PlaybackRuntimeFaultKind.PositionStalled, assertNotNull(fault).kind)
    }

    @Test
    fun unverifiable_backend_still_reports_a_stalled_position() {
        val detector = detector()

        detector.observe(
            observation(
                now = 1_000L,
                positionMs = 1_000L,
                videoOutputVerifiable = false,
            ),
        )
        val fault =
            detector.observe(
                observation(
                    now = 13_500L,
                    positionMs = 1_000L,
                    videoOutputVerifiable = false,
                ),
            )

        assertEquals(PlaybackRuntimeFaultKind.PositionStalled, assertNotNull(fault).kind)
    }

    private fun detector(
        startedAtEpochMs: Long = 0L,
        initialPositionMs: Long = 0L,
    ) = PlaybackRuntimeFaultDetector(startedAtEpochMs, initialPositionMs)

    private fun observation(
        now: Long,
        positionMs: Long,
        playbackRequested: Boolean = true,
        playing: Boolean = true,
        buffering: Boolean = false,
        videoReady: Boolean = false,
        videoExpected: Boolean = true,
        videoOutputVerifiable: Boolean = true,
        audioExpected: Boolean = false,
        errorPresent: Boolean = false,
        ended: Boolean = false,
    ) = YCoreRuntimeObservation(
        nowEpochMs = now,
        positionMs = positionMs,
        playbackRequested = playbackRequested,
        playing = playing,
        buffering = buffering,
        videoReady = videoReady,
        videoExpected = videoExpected,
        videoOutputVerifiable = videoOutputVerifiable,
        audioExpected = audioExpected,
        errorPresent = errorPresent,
        ended = ended,
        bufferEvents = 0,
        droppedFrames = 0,
    )

    /** Video is fine; only the audio sink is silent. */
    private fun audioObservation(
        now: Long,
        positionMs: Long,
    ) = observation(now = now, positionMs = positionMs, videoReady = true)
        .copy(audioExpected = true, audioReady = false)
}
