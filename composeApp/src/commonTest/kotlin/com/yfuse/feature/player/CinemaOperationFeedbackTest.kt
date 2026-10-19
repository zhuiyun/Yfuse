package com.yfuse.feature.player

import com.yfuse.core.cast.CastDevice
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.designsystem.CinemaConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CinemaOperationFeedbackTest {
    private val receiver = CastDevice("tv", "客厅")

    @Test
    fun selecting_a_device_does_not_confirm_a_connection() {
        val selected = CastState(activeDevice = receiver)
        assertEquals(CinemaConnection.Idle, cinemaCastConnection(selected))
        assertEquals(
            CinemaConnection.Checking,
            cinemaCastConnection(selected.copy(status = CastPlaybackStatus.Connecting)),
        )
        assertNotEquals(
            CinemaConnection.Connected,
            cinemaCastConnection(selected.copy(status = CastPlaybackStatus.Playing)),
        )
    }

    @Test
    fun receiver_acknowledgement_is_required_and_survives_pause_and_buffering() {
        for (status in listOf(
            CastPlaybackStatus.Playing,
            CastPlaybackStatus.Paused,
            CastPlaybackStatus.Buffering,
            CastPlaybackStatus.Ended,
        )) {
            val session = CastState(activeDevice = receiver, sessionConfirmed = true, status = status)
            assertEquals(CinemaConnection.Connected, cinemaCastConnection(session))
            assertNotEquals(
                CinemaConnection.Connected,
                cinemaCastConnection(session.copy(termination = CastTermination.UserStop)),
            )
            assertNotEquals(CinemaConnection.Connected, cinemaCastConnection(session.copy(activeDevice = null)))
        }
    }

    @Test
    fun failed_session_cannot_leave_a_success_icon_even_without_an_error_message() {
        val session = CastState(activeDevice = receiver, sessionConfirmed = true)
        assertEquals(CinemaConnection.Failed, cinemaCastConnection(session.copy(status = CastPlaybackStatus.Error)))
        assertEquals(
            CinemaConnection.Failed,
            cinemaCastConnection(session.copy(status = CastPlaybackStatus.Disconnected)),
        )
        assertEquals(
            CinemaConnection.Failed,
            cinemaCastConnection(session.copy(status = CastPlaybackStatus.Playing, error = "接收端错误")),
        )
    }

    @Test
    fun radar_only_runs_during_discovery_or_connecting() {
        assertEquals(CinemaConnection.Checking, cinemaCastConnection(CastState(discovering = true)))
        assertEquals(CinemaConnection.Idle, cinemaCastConnection(CastState()))
    }
}
