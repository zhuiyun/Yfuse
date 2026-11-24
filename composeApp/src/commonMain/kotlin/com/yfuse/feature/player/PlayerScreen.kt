package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OrbProgressDefaults
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressableTarget
import com.yfuse.core.designsystem.ThemeIcon as Icon

@Composable
fun PlayerScreen(component: PlayerComponent) {
    PendingPlayerLauncher(
        store = component.store,
        startPlaybackRequested = component.startPlaybackRequested,
        onStoreTransferred = component::transferStoreOwnership,
        onLaunched = component.onBack,
    )
}

/** Loading and retry stay inside the landscape player Activity. */
@Composable
internal fun PlayerPreparationContent(
    state: PlayerState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    loadingOverlay: @Composable () -> Unit = {},
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Spinner and failure occupy the same spot, so one has to hand over to the other rather
        // than be swapped for it: a retry that fails again used to blink between the two states
        // on a black screen with nothing to say which way it had gone. Keyed on the stage, so a
        // changing error message re-renders the copy without restarting the crossfade.
        AnimatedContent(
            targetState = state,
            contentKey = { it.preparationStage() },
            transitionSpec = {
                val duration = if (reduceMotion) 0 else Motion.STATE_HANDOFF
                fadeIn(Motion.tween(duration)) togetherWith fadeOut(Motion.tween(duration)) using
                    Motion.sizeTransform(reduceMotion)
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.Center),
            label = "player-preparation",
        ) { current ->
            val error = current.error
            when {
                current.loading ->
                    OrbProgress(
                        size = OrbProgressDefaults.Page,
                        color = Color.White,
                    )

                error != null ->
                    PlayerLoadError(
                        message = error,
                        onRetry = onRetry,
                        onBack = onBack,
                    )
            }
        }

        // The transition may wait for metadata or the first frame. The way out stays above it.
        loadingOverlay()
        Box(
            Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .align(Alignment.TopStart)
                .pressableTarget(CircleShape, onClickLabel = "关闭播放", onClick = onBack)
                .size(38.dp)
                .glass(
                    shape = CircleShape,
                    fill = Color.Black.copy(alpha = 0.28f),
                    border = Color.White.copy(alpha = 0.32f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Close,
                contentDescription = "关闭播放",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** What the preparation screen is showing; the crossfade switches on this, not on the copy. */
private enum class PreparationStage {
    Loading,
    Error,
    Idle,
}

private fun PlayerState.preparationStage(): PreparationStage =
    when {
        loading -> PreparationStage.Loading
        error != null -> PreparationStage.Error
        else -> PreparationStage.Idle
    }

@Composable
private fun PlayerLoadError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    PlaybackErrorSurface(
        title = "无法开始播放",
        message = message,
        stage = "播放准备",
        advice = "请检查服务器连接或片源可用性后重试。",
        onRetry = onRetry,
        onBack = onBack,
        footer = { PlaybackDiagnosticExportButton() },
        scrimAlpha = 0f,
    )
}
