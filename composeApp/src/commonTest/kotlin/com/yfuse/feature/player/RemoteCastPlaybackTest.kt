package com.yfuse.feature.player

import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastOutputEvidence
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.feature.player.contract.PlaybackEvidenceConfidence
import com.yfuse.feature.player.contract.PlaybackOutputEvidence
import com.yfuse.feature.player.contract.PlaybackOutputReadiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCastPlaybackTest {
    private val receiver = CastDevice("chromecast:lounge", "客厅电视")

    @Test
    fun confirmed_receiver_truth_replaces_local_transport_truth() {
        val local =
            PlaybackState(
                playing = false,
                buffering = false,
                positionMs = 2_000L,
                durationMs = 100_000L,
                bufferedPositionMs = 3_000L,
            )
        val cast =
            CastState(
                status = CastPlaybackStatus.Playing,
                activeDevice = receiver,
                sessionConfirmed = true,
                positionMs = 50_000L,
                positionConfirmed = true,
                durationMs = 120_000L,
                lastRemoteWasPlaying = true,
            )

        val remote = local.withRemoteCast(cast, playMethod = "服务器转码")

        assertTrue(remote.playing)
        assertFalse(remote.buffering)
        assertEquals(50_000L, remote.positionMs)
        assertEquals(120_000L, remote.durationMs)
        assertEquals(50_000L, remote.bufferedPositionMs)
        assertEquals("远程投屏 · 客厅电视", remote.diagnostics.engine)
        assertEquals("服务器转码", remote.diagnostics.playMethod)
    }

    @Test
    fun non_fatal_cast_command_error_never_becomes_player_fatal_error() {
        val local = PlaybackState(error = "旧的本地解码错误")
        val cast =
            CastState(
                status = CastPlaybackStatus.Error,
                activeDevice = receiver,
                sessionConfirmed = true,
                positionMs = 8_000L,
                positionConfirmed = true,
                lastRemoteWasPlaying = true,
                error = "接收端拒绝调节音量",
            )

        val remote = local.withRemoteCast(cast, playMethod = "直播放")

        assertNull(remote.error)
        assertTrue(remote.playing)
        assertEquals(8_000L, remote.positionMs)
    }

    @Test
    fun unconfirmed_receiver_position_does_not_replace_local_position() {
        val local = PlaybackState(positionMs = 12_000L, bufferedPositionMs = 13_000L)
        val cast =
            CastState(
                status = CastPlaybackStatus.Connecting,
                activeDevice = receiver,
                positionMs = 50_000L,
                positionConfirmed = false,
            )

        val projected = local.withRemoteCast(cast, playMethod = "直播放")

        assertEquals(12_000L, projected.positionMs)
        assertEquals(13_000L, projected.bufferedPositionMs)
        assertTrue(projected.buffering)
    }

    @Test
    fun local_dolby_evidence_is_cleared_until_this_receiver_load_reports_output() {
        val local =
            PlaybackState(
                diagnostics =
                    PlaybackDiagnostics(
                        videoOutput = "本地 Dolby Vision",
                        audioOutput = "本地 Atmos passthrough",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                        audioReadiness = PlaybackOutputReadiness.Rendering,
                        dolbyVisionOutput = true,
                        dolbyAtmosOutput = true,
                        dolbyVisionRpuApplied = true,
                        dolbyVisionEnhancementLayerComposed = true,
                        immersiveAudioCarrierOutput = true,
                        spatialAudioOutput = true,
                        headTrackingAvailable = true,
                        outputEvidence =
                            PlaybackOutputEvidence(
                                sessionRevision = 8L,
                                videoReadiness = PlaybackOutputReadiness.Rendering,
                                audioReadiness = PlaybackOutputReadiness.Rendering,
                                videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                                audioConfidence = PlaybackEvidenceConfidence.Confirmed,
                            ),
                    ),
            )
        val cast =
            CastState(
                sessionRevision = 3L,
                status = CastPlaybackStatus.Playing,
                activeDevice = receiver,
                sessionConfirmed = true,
            )

        val projected = local.withRemoteCast(cast, playMethod = "直播放")

        assertFalse(projected.diagnostics.dolbyVisionOutput)
        assertFalse(projected.diagnostics.dolbyAtmosOutput)
        assertFalse(projected.diagnostics.dolbyVisionRpuApplied)
        assertFalse(projected.diagnostics.dolbyVisionEnhancementLayerComposed)
        assertFalse(projected.diagnostics.immersiveAudioCarrierOutput)
        assertFalse(projected.diagnostics.spatialAudioOutput)
        assertFalse(projected.diagnostics.headTrackingAvailable)
        assertEquals(3L, projected.diagnostics.outputEvidence.sessionRevision)
    }

    @Test
    fun matching_playing_receipt_projects_receiver_dolby_output() {
        val cast =
            CastState(
                sessionRevision = 4L,
                status = CastPlaybackStatus.Playing,
                activeDevice = receiver,
                sessionConfirmed = true,
                outputEvidence =
                    CastOutputEvidence(
                        sessionRevision = 4L,
                        receiverConfirmed = true,
                        playbackConfirmed = true,
                        dolbyVisionOutput = true,
                        dolbyAtmosOutput = true,
                        detail = "PLAYING",
                    ),
            )

        val projected = PlaybackState().withRemoteCast(cast, playMethod = "直播放")

        assertTrue(projected.diagnostics.dolbyVisionOutput)
        assertTrue(projected.diagnostics.dolbyAtmosOutput)
        assertFalse(projected.diagnostics.hasNativeDualDolbyOutput())
        assertEquals(
            PlaybackEvidenceConfidence.Confirmed,
            projected.diagnostics.outputEvidence.videoConfidence,
        )
    }
}
