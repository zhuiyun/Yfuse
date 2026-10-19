package com.yfuse.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.yfuse.core.data.ServerHealthStatus
import com.yfuse.core.designsystem.CinemaConnection
import com.yfuse.core.designsystem.CinemaConnectionMark
import com.yfuse.core.designsystem.cinemaMotion

internal fun cinemaServerConnection(
    status: ServerHealthStatus,
    checking: Boolean,
): CinemaConnection =
    when {
        checking -> CinemaConnection.Checking
        status == ServerHealthStatus.Healthy -> CinemaConnection.Connected
        status == ServerHealthStatus.Degraded -> CinemaConnection.Warning
        status == ServerHealthStatus.Offline || status == ServerHealthStatus.AuthRequired -> CinemaConnection.Failed
        else -> CinemaConnection.Idle
    }

@Composable
internal fun ServerConnectionMark(
    status: ServerHealthStatus,
    checking: Boolean,
    color: Color,
    size: Dp,
) {
    if (cinemaMotion()) {
        CinemaConnectionMark(cinemaServerConnection(status, checking), color)
    } else {
        Box(Modifier.size(size).background(color, CircleShape))
    }
}
