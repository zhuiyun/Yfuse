package com.yfuse.feature.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.liveStatus
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/** 投屏 — the receivers found on the local network, the session on one of them, and 停止投屏. */
@Composable
internal fun CastPanel(
    castDevices: List<Pair<String, String>>,
    castingDeviceId: String?,
    castDiscovering: Boolean,
    castError: String?,
    castStatus: String?,
    castPosition: String?,
    /** The receiver's own clock, resolved here rather than by whoever opened the panel. */
    castPositionSource: (() -> String?)?,
    castCapabilities: String?,
    onDiscoverCast: () -> Unit,
    onCastTo: (String) -> Unit,
    onStopCast: () -> Unit,
) {
    // Derived, so the receiver's clock invalidates this page when the text
    // it prints changes and never the four pages that do not show it.
    val remotePosition by remember(castPositionSource, castPosition) {
        derivedStateOf { castPositionSource?.invoke() ?: castPosition }
    }
    // Opened with nothing found yet, the first scan starts here instead of
    // waiting behind 重新扫描. Until the manager reports it running, an empty
    // list still means "searching" rather than "nothing found".
    var scanStarting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (castDevices.isEmpty() && !castDiscovering) {
            scanStarting = true
            onDiscoverCast()
            // A scan that never starts (a permission prompt left unanswered)
            // must not leave the page saying it is searching.
            delay(CAST_SCAN_START_GRACE_MS)
            scanStarting = false
        }
    }
    LaunchedEffect(castDiscovering) {
        if (castDiscovering) scanStarting = false
    }
    GroupLabel("局域网投屏设备")
    castStatus?.let { DiagnosticRow("状态", it) }
    remotePosition?.let { DiagnosticRow("远端进度", it) }
    castCapabilities?.let { DiagnosticRow("远端能力", it) }
    // A failed scan reports itself through [castError] below; this line only
    // speaks for a list that is empty without one.
    val emptyState =
        when {
            castDiscovering || scanStarting -> "正在搜索投屏设备…"
            castDevices.isEmpty() && castError == null -> "未发现设备，请确认电视与手机连接同一局域网"
            else -> null
        }
    emptyState?.let { line ->
        Text(
            line,
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(vertical = Dimens.space.sm).liveStatus(),
        )
    }
    castDevices.forEach { (id, name) ->
        OptionRow(name, id == castingDeviceId, onClick = { onCastTo(id) })
    }
    if (castingDeviceId != null) {
        OptionRow("停止投屏", false, onClick = onStopCast)
    }
    if (castError != null) {
        Text(
            castError,
            style = AppTypography.caption.medium,
            color = DarkPalette.error,
            modifier = Modifier.padding(vertical = 8.dp).liveStatus(),
        )
    }
    OptionRow("重新扫描", false, onClick = onDiscoverCast)
}

/** Long enough for the manager to report a scan it has started; see the 投屏 page. */
private const val CAST_SCAN_START_GRACE_MS = 2_000L
