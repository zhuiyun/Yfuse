package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.PlaybackSourceRequirements
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackOutputReadinessTest {
    @Test
    fun waiting_is_the_default_and_unknown_withholds_missing_output_judgements() {
        val defaults = PlaybackDiagnostics()

        assertTrue(defaults.videoReadiness == PlaybackOutputReadiness.Waiting)
        assertTrue(defaults.audioReadiness == PlaybackOutputReadiness.Waiting)
        assertTrue(PlaybackOutputReadiness.Waiting.verifiable)
        assertFalse(PlaybackOutputReadiness.Unknown.verifiable)
    }

    @Test
    fun runtime_observation_uses_structured_readiness_instead_of_diagnostic_labels() {
        val state =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        videoOutput = "首帧已经输出",
                        audioOutput = "音频正在渲染",
                        videoReadiness = PlaybackOutputReadiness.Unknown,
                        audioReadiness = PlaybackOutputReadiness.Released,
                    ),
            )

        val observation =
            state.runtimeObservation(
                playbackRequested = true,
                probe = probe(videoExpected = true, audioExpected = true),
                runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
                nowEpochMs = 123L,
            )

        assertFalse(observation.videoReady)
        assertFalse(observation.videoOutputVerifiable)
        assertFalse(observation.audioReady)
        assertTrue(observation.audioOutputVerifiable)
    }

    @Test
    fun selecting_an_audio_track_does_not_claim_that_audio_reached_the_output() {
        val state =
            PlaybackState(
                audioTracks = listOf(EngineTrack("1", "AAC", "zh", selected = true)),
                diagnostics = PlaybackDiagnostics(audioReadiness = PlaybackOutputReadiness.Waiting),
            )

        val observation =
            state.runtimeObservation(
                playbackRequested = true,
                probe = probe(videoExpected = false, audioExpected = true),
                runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
            )

        assertTrue(observation.audioExpected)
        assertFalse(observation.audioReady)
    }

    @Test
    fun parsed_video_dimensions_do_not_claim_that_a_frame_reached_the_surface() {
        val state =
            PlaybackState(
                videoHeight = 2160,
                diagnostics = PlaybackDiagnostics(videoReadiness = PlaybackOutputReadiness.Waiting),
            )

        val observation =
            state.runtimeObservation(
                playbackRequested = true,
                probe = probe(videoExpected = true, audioExpected = false),
                runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
            )

        assertFalse(observation.videoReady)
        assertTrue(observation.videoOutputVerifiable)
    }

    @Test
    fun missing_server_metadata_still_arms_a_conservative_video_watchdog() {
        val observation =
            PlaybackState().runtimeObservation(
                playbackRequested = true,
                probe = probe(videoExpected = false, audioExpected = false),
                runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
            )

        assertTrue(observation.videoExpected)
        assertFalse(observation.audioExpected)
    }

    @Test
    fun real_audio_output_corrects_unknown_metadata_to_audio_only() {
        val observation =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        videoReadiness = PlaybackOutputReadiness.Unknown,
                        audioReadiness = PlaybackOutputReadiness.Rendering,
                    ),
            ).runtimeObservation(
                playbackRequested = true,
                probe = probe(videoExpected = false, audioExpected = false),
                runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
            )

        assertFalse(observation.videoExpected)
        assertTrue(observation.audioExpected)
    }

    @Test
    fun mpv_audio_requires_output_driver_and_output_format() {
        assertTrue(
            mpvAudioOutputReadiness("aaudio", "s16") == PlaybackOutputReadiness.Rendering,
        )
        assertFalse(
            mpvAudioOutputReadiness("", "s16") == PlaybackOutputReadiness.Rendering,
        )
        assertFalse(
            mpvAudioOutputReadiness("aaudio", "") == PlaybackOutputReadiness.Rendering,
        )
    }

    @Test
    fun dolby_badges_are_reported_facts_not_diagnostic_text() {
        val textOnly =
            PlaybackDiagnostics(
                videoOutput = "Dolby Vision 首帧已输出",
                audioOutput = "Dolby Atmos 源码输出",
            )
        val reported =
            PlaybackDiagnostics(
                videoOutput = "任意文案",
                audioOutput = "任意文案",
                dolbyVisionOutput = true,
                dolbyAtmosOutput = true,
            )

        assertFalse(textOnly.hasActiveDolbyVisionOutput())
        assertFalse(textOnly.hasActiveDolbyAtmosOutput())
        assertTrue(reported.hasActiveDolbyVisionOutput())
        assertTrue(reported.hasActiveDolbyAtmosOutput())
    }

    private fun probe(
        videoExpected: Boolean,
        audioExpected: Boolean,
    ): PlaybackMediaProbe =
        PlaybackMediaProbe(
            container = "mkv",
            discSource = false,
            source =
                PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = null,
                    videoCodec = if (videoExpected) com.yfuse.core.playback.PlaybackVideoCodec.H264 else null,
                ),
            audioCodec = if (audioExpected) PlaybackAudioCodec.Aac else null,
            hasServerTranscode = false,
            drmProtected = false,
            usingServerTranscode = false,
        )
}
