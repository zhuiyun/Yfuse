package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.sync.WatchReactionBurst
import kotlinx.coroutines.delay

/** How long a bubble takes to cross the strip it floats up. */
private const val REACTION_MS = 2_600

/** How far a bubble travels before it is gone. */
private val RiseDistance = 180.dp

/**
 * 一起看 reactions, drifting up the right-hand edge.
 *
 * The room already had a text chat, but a chat is a conversation — it wants attention, it
 * has an unread badge, and typing during a film is its own decision. A reaction is the
 * other half: a single tap that everyone sees for two seconds and nobody has to answer.
 *
 * Rendering reuses nothing from the danmaku engine on purpose. Danmaku is a dense
 * horizontal stream with lane allocation; this is a handful of bubbles on one edge, and
 * borrowing that machinery would cost more than the twenty lines it replaces.
 */
@Composable
fun WatchReactionOverlay(
    reactions: List<WatchReactionBurst>,
    onFinished: (Long) -> Unit,
    /**
     * How far in from the right edge the bubbles rise.
     *
     * They float up the bottom-right corner, which is also where the chat panel opens — so
     * with the panel up they rose entirely behind it and a room that was reacting looked like
     * a room that had gone quiet. The caller moves them clear.
     */
    insetEnd: Dp = DefaultInsetEnd,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        reactions.forEach { burst ->
            key(burst.id) {
                ReactionBubble(burst = burst, insetEnd = insetEnd, onFinished = onFinished)
            }
        }
    }
}

/** Clear of the right edge, when nothing else is in the way. */
private val DefaultInsetEnd = 26.dp

@Composable
private fun BoxScope.ReactionBubble(
    burst: WatchReactionBurst,
    insetEnd: Dp,
    onFinished: (Long) -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rise = remember { Animatable(0f) }
    // A stable per-bubble sideways offset so simultaneous reactions do not stack into one
    // illegible column. Derived from the id rather than random, so a recomposition mid-flight
    // does not teleport the bubble sideways.
    val drift = remember(burst.id) { ((burst.id % 5L).toInt() - 2) * 9 }

    LaunchedEffect(burst.id, reduceMotion) {
        if (reduceMotion) {
            // No travel, but still transient: it appears, it is read, it goes.
            delay(REACTION_MS.toLong())
        } else {
            rise.animateTo(1f, tween(REACTION_MS, easing = LinearEasing))
        }
        onFinished(burst.id)
    }

    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = insetEnd, bottom = 150.dp)
            .graphicsLayer {
                val progress = rise.value
                translationY = -RiseDistance.toPx() * progress
                translationX = drift.dp.toPx() * progress
                // Holds full strength for the first third, then thins out.
                alpha = ((1f - progress) * 1.5f).coerceIn(0f, 1f)
            }
            .glass(
                shape = GlassShapes.chip,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(burst.reaction.emoji, style = AppTypography.section.regular)
        if (burst.name.isNotBlank() && !burst.isMine) {
            Text(
                burst.name,
                style = AppTypography.caption.medium,
                color = PlayerTokens.timeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
    }
}
