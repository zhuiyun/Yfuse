package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.pressable
import kotlinx.coroutines.flow.distinctUntilChanged
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * A-B segment loop controller scoped to a single queue item.
 *
 * Sets an optional start ([pointAMs]) and end ([pointBMs]) boundary; once both are set with
 * `pointBMs > pointAMs + 500ms`, reaching [pointBMs] automatically seeks back to [pointAMs].
 */
@Stable
internal class PlayerAbLoopController {
    var pointAMs: Long? by mutableStateOf(null)
        private set

    var pointBMs: Long? by mutableStateOf(null)
        private set

    val active: Boolean
        get() {
            val a = pointAMs ?: return false
            val b = pointBMs ?: return false
            return b - a >= MIN_LOOP_SPAN_MS
        }

    val summary: String
        get() =
            when {
                active -> "${pointAMs!!.asClock()} → ${pointBMs!!.asClock()}"
                pointAMs != null -> "起点 ${pointAMs!!.asClock()} · 待设终点 B"
                else -> "未开启"
            }

    fun setA(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        pointAMs = clamped
        val currentB = pointBMs
        if (currentB != null && currentB - clamped < MIN_LOOP_SPAN_MS) {
            pointBMs = null
        }
    }

    fun setB(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        val currentA = pointAMs
        if (currentA == null) {
            pointAMs = (clamped - DEFAULT_BACK_SPAN_MS).coerceAtLeast(0L)
            pointBMs = clamped
        } else if (clamped - currentA >= MIN_LOOP_SPAN_MS) {
            pointBMs = clamped
        } else {
            // Reversed order: swap so A is always earlier than B.
            pointBMs = currentA
            pointAMs = clamped
        }
    }

    fun clear() {
        pointAMs = null
        pointBMs = null
    }

    fun shouldWrap(positionMs: Long): Boolean {
        val a = pointAMs ?: return false
        val b = pointBMs ?: return false
        return b - a >= MIN_LOOP_SPAN_MS && positionMs >= b
    }

    private companion object {
        const val MIN_LOOP_SPAN_MS = 500L
        const val DEFAULT_BACK_SPAN_MS = 5_000L
    }
}

internal val LocalPlayerAbLoop = staticCompositionLocalOf<PlayerAbLoopController?> { null }

@Composable
internal fun rememberPlayerAbLoop(
    itemKey: Any,
    playback: State<PlaybackState>,
    onSeek: (Long) -> Unit,
): PlayerAbLoopController {
    val controller = remember(itemKey) { PlayerAbLoopController() }
    LaunchedEffect(controller, playback) {
        var lastWrapEpochMs = 0L
        snapshotFlow {
            val s = playback.value
            Triple(s.playing, s.positionMs, controller.active)
        }.distinctUntilChanged().collect { (playing, pos, active) ->
            if (playing && active && controller.shouldWrap(pos)) {
                val now = System.currentTimeMillis()
                if (now - lastWrapEpochMs >= 400L) {
                    lastWrapEpochMs = now
                    controller.pointAMs?.let(onSeek)
                }
            }
        }
    }
    return controller
}

@Composable
internal fun AbLoopPanelSection(
    controller: PlayerAbLoopController,
    currentPositionMs: () -> Long,
) {
    val accent = LocalAccentColors.current.accent
    val haptics = LocalHaptics.current
    GroupLabel("A-B 片段循环 · ${controller.summary}")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbLoopChip(
            label = controller.pointAMs?.let { "A ${it.asClock()}" } ?: "设为起点 A",
            highlighted = controller.pointAMs != null,
            accent = accent,
            modifier = Modifier.weight(1f),
        ) {
            haptics.play(HapticSignal.Select)
            controller.setA(currentPositionMs())
        }
        AbLoopChip(
            label = controller.pointBMs?.let { "B ${it.asClock()}" } ?: "设为终点 B",
            highlighted = controller.active,
            accent = accent,
            modifier = Modifier.weight(1f),
        ) {
            haptics.play(HapticSignal.Confirm)
            controller.setB(currentPositionMs())
        }
        if (controller.pointAMs != null || controller.pointBMs != null) {
            AbLoopChip(
                label = "清除",
                highlighted = false,
                accent = accent,
            ) {
                haptics.play(HapticSignal.Tap)
                controller.clear()
            }
        }
    }
}

@Composable
private fun AbLoopChip(
    label: String,
    highlighted: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = AppTypography.caption.strong,
        color = if (highlighted) Color.White else Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        modifier =
            modifier
                .clip(AppShapes.chip)
                .background(if (highlighted) accent.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.12f))
                .pressable(focusShape = AppShapes.chip, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
