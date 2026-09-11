package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OrbProgressDefaults
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

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
                fadeIn(tween(duration, easing = Motion.Curve)) togetherWith
                    fadeOut(tween(duration, easing = Motion.Curve))
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

        Box(
            Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .align(Alignment.TopStart)
                .pressable(onClickLabel = "返回", onClick = onBack)
                .touchTarget()
                .size(38.dp)
                .glass(
                    shape = CircleShape,
                    fill = Color.Black.copy(alpha = 0.28f),
                    border = Color.White.copy(alpha = 0.32f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // The app's own mark, like every other 返回 in the app. This was the one
                // Material icon left, and it cost a whole icon pack on the dependency list.
                AppIcons.ChevronLeft,
                contentDescription = "返回",
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = 32.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("无法开始播放", color = Color.White)
        Text(
            message,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerLoadErrorAction(label = "返回", primary = false, onClick = onBack)
            PlayerLoadErrorAction(label = "重试", primary = true, onClick = onRetry)
        }
    }
}

@Composable
private fun PlayerLoadErrorAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Box(
        Modifier
            .height(44.dp)
            .widthIn(min = 104.dp)
            .pressable(onClickLabel = label, onClick = onClick)
            .glass(
                shape = CircleShape,
                fill = if (primary) accent.accent else Color.Black.copy(alpha = 0.28f),
                border = if (primary) accent.border else Color.White.copy(alpha = 0.32f),
            ).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) accent.onAccent else Color.White)
    }
}
