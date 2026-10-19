package com.yfuse.feature.servers

import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.CinemaConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CinemaServerFeedbackTest {
    @Test
    fun unknown_and_degraded_servers_never_show_success_or_a_fabricated_check() {
        assertEquals(CinemaConnection.Idle, cinemaServerConnection(ServerHealthStatus.Unknown, false))
        assertEquals(CinemaConnection.Warning, cinemaServerConnection(ServerHealthStatus.Degraded, false))
        for (status in ServerHealthStatus.entries.filter { it != ServerHealthStatus.Healthy }) {
            assertNotEquals(CinemaConnection.Connected, cinemaServerConnection(status, false))
        }
    }

    @Test
    fun actual_check_temporarily_replaces_cached_status_then_exposes_its_result() {
        for (status in ServerHealthStatus.entries) {
            assertEquals(CinemaConnection.Checking, cinemaServerConnection(status, true))
        }
        assertEquals(CinemaConnection.Connected, cinemaServerConnection(ServerHealthStatus.Healthy, false))
        assertEquals(CinemaConnection.Failed, cinemaServerConnection(ServerHealthStatus.Offline, false))
        assertEquals(CinemaConnection.Failed, cinemaServerConnection(ServerHealthStatus.AuthRequired, false))
    }
}
