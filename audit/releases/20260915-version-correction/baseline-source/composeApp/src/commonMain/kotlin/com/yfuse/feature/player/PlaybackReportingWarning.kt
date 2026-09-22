package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.designsystem.ConfirmDialog

/** Durable warning shown on return to the app; progress playback itself is never interrupted. */
@Composable
fun PlaybackReportingWarning(coordinator: PlaybackReportingCoordinator) {
    val lost by coordinator.droppedTerminalEvents.collectAsState()
    var deferred by remember { mutableLongStateOf(0L) }
    if (lost <= 0L || lost == deferred) return
    ConfirmDialog(
        title = "部分观看进度未同步",
        message = "离线上报队列已满，$lost 条播放结束记录未能保留。对应的服务器观看进度可能不完整。",
        confirmLabel = "知道了",
        dismissLabel = "稍后提醒",
        onConfirm = { coordinator.acknowledgeDroppedReports(lost) },
        onDismiss = { deferred = lost },
    )
}
