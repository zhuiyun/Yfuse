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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.sync.WatchReactionBurst
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

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
                val motion = reactionMotion(progress, burst.id)
                translationY = -RiseDistance.toPx() * motion.riseFraction
                translationX = motion.horizontalDp.dp.toPx()
                scaleX = if (reduceMotion) 1f else motion.scale
                scaleY = if (reduceMotion) 1f else motion.scale
                rotationZ = if (reduceMotion) 0f else motion.rotationDegrees
                alpha = if (reduceMotion) 1f else motion.alpha
            }.glass(
                shape = GlassShapes.chip,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            ).padding(horizontal = 10.dp, vertical = 6.dp),
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

internal data class ReactionMotion(
    val riseFraction: Float,
    val horizontalDp: Float,
    val scale: Float,
    val rotationDegrees: Float,
    val alpha: Float,
)

/** Deterministic, recomposition-safe ballistic path for a room reaction. */
internal fun reactionMotion(
    progress: Float,
    id: Long,
): ReactionMotion {
    val t = progress.coerceIn(0f, 1f)
    val bucket = (((id % 7L) + 7L) % 7L).toInt() - 3
    val drift = bucket * REACTION_DRIFT_STEP_DP
    val swayDirection = if ((id and 1L) == 0L) 1f else -1f
    val sway = swayDirection * REACTION_SWAY_DP * sin((2f * PI * t).toFloat()) * (1f - t)
    val scale =
        1f -
            REACTION_SCALE_AMPLITUDE *
            exp((-REACTION_SCALE_DECAY * t).toDouble()).toFloat() *
            cos((REACTION_SCALE_FREQUENCY * t).toDouble()).toFloat()
    val fadeProgress = ((t - REACTION_FADE_START) / (1f - REACTION_FADE_START)).coerceIn(0f, 1f)
    return ReactionMotion(
        riseFraction = REACTION_RISE_LINEAR * t - REACTION_RISE_GRAVITY * t * t,
        horizontalDp = drift * t + sway,
        scale = scale,
        rotationDegrees = bucket * REACTION_TILT_DEGREES * sin((PI * t).toFloat()) * (1f - t),
        alpha = 1f - fadeProgress,
    )
}

private const val REACTION_DRIFT_STEP_DP = 5f
private const val REACTION_SWAY_DP = 8f
private const val REACTION_SCALE_AMPLITUDE = 0.28f
private const val REACTION_SCALE_DECAY = 7f
private const val REACTION_SCALE_FREQUENCY = 13f
private const val REACTION_FADE_START = 0.35f
private const val REACTION_RISE_LINEAR = 1.5f
private const val REACTION_RISE_GRAVITY = 0.5f
private const val REACTION_TILT_DEGREES = 2.2f
