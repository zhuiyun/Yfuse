package com.yfuse.feature.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.performance.rememberPageFrameRate
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeText as Text

/** Isolated from routing/chrome: diagnostics repaint only this small, non-interactive readout. */
@Composable
internal fun PlayerFrameRateOverlay(
    playback: State<PlaybackState>,
    preferences: PlaybackPreferences,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val enabled by preferences.showFrameRate.collectAsState()
    if (!enabled || !visible) return
    val page by rememberPageFrameRate()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var output by remember(playback) { mutableStateOf("实时输出 — FPS · 暂无数据") }
    var source by remember(playback) { mutableStateOf("片源 — FPS") }
    LaunchedEffect(playback, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val current = playback.value
                output = current.outputFrameRateLabel(SystemClock.elapsedRealtime())
                source = current.sourceFrameRateLabel()
                delay(1_000L)
            }
        }
    }
    Column(modifier.background(Color.Black.copy(alpha = 0.72f), AppShapes.chip).padding(8.dp)) {
        Text(page.label, color = Color.White, style = AppTypography.caption.regular)
        Text(output, color = Color.White, style = AppTypography.caption.regular)
        Text(source, color = Color.White, style = AppTypography.caption.regular)
    }
}
