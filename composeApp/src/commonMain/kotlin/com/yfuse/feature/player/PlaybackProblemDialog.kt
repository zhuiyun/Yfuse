package com.yfuse.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun PlaybackProblemDialog(
    playback: State<PlaybackState>,
    onDismiss: () -> Unit,
) {
    val state = playback.value
    val explanation = explainPlaybackProblem(state)
    val diagnostics = state.diagnostics
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader("播放状态", explanation.stage, onClose = onDismiss)
        Text(explanation.advice, style = AppTypography.body.regular, color = LocalPalette.current.body)
        PlaybackProblemRow("播放器", diagnostics.engine.ifBlank { "尚未创建" })
        PlaybackProblemRow("视频输出", diagnostics.videoOutput)
        PlaybackProblemRow("声音输出", diagnostics.audioOutput)
        PlaybackProblemRow("当前音轨", state.audioTracks.firstOrNull { it.selected }?.label ?: "尚未选定")
        PlaybackProblemRow("前向缓冲", "${diagnostics.bufferedDurationMs.coerceAtLeast(0) / 1000} 秒")
        PlaybackProblemRow(
            "实时读取",
            if (diagnostics.networkBitsPerSecond >
                0
            ) {
                "${diagnostics.networkBitsPerSecond / 1000} Kbps"
            } else {
                "尚未测得有效吞吐"
            },
        )
        PlaybackProblemRow(
            "网络恢复",
            "尝试 ${diagnostics.networkRecoveryAttempts} 次 · 成功 ${diagnostics.networkRecoverySuccesses} 次",
        )
        PlaybackDiagnosticExportButton()
    }
}

@Composable
internal expect fun PlaybackDiagnosticExportButton()

@Composable
private fun PlaybackProblemRow(
    label: String,
    value: String,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(label, style = AppTypography.caption.medium, color = palette.sub)
        Text(value, style = AppTypography.body.regular, color = palette.text)
    }
}
