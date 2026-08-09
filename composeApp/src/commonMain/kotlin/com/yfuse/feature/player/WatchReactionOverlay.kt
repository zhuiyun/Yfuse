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
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.sync.WatchReactionBurst
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How long a reaction owns attention before leaving the picture. */
private const val REACTION_MS = 2_450

/** How far a bubble travels before it is gone. */
private val RiseDistance = 196.dp

/**
 * 一起看 reactions, drifting up the right-hand edge.
 *
 * Reactions should read as an acknowledgement, not another chat bubble. The motion therefore
 * has three stages: a short pop when it is born, a calm floating middle, then a soft fade.
 * One progress value drives the whole layer so this stays cheap even when several people tap
 * at once over video playback.
 */
@Composable
fun WatchReactionOverlay(
    reactions: List<WatchReactionBurst>,
    onFinished: (Long) -> Unit,
    /** Move the lane left while the chat drawer occupies the right edge. */
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

private val DefaultInsetEnd = 26.dp

@Composable
private fun BoxScope.ReactionBubble(
    burst: WatchReactionBurst,
    insetEnd: Dp,
    onFinished: (Long) -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(0f) }

    // Stable variation stops simultaneous reactions becoming one vertical column while also
    // preventing a recomposition from teleporting an in-flight bubble.
    val lane = remember(burst.id) { ((burst.id % 5L).toInt() - 2) }
    val driftPx = lane * 10f
    val tilt = remember(burst.id) { (((burst.id % 7L).toInt() - 3) * 1.35f) }

    LaunchedEffect(burst.id, reduceMotion) {
        if (reduceMotion) {
            delay(REACTION_MS.toLong())
        } else {
            progress.animateTo(1f, tween(REACTION_MS, easing = LinearEasing))
        }
        onFinished(burst.id)
    }

    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = insetEnd, bottom = 146.dp)
            .graphicsLayer {
                if (reduceMotion) {
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    return@graphicsLayer
                }

                val p = progress.value.coerceIn(0f, 1f)
                val arc = sin(p * PI).toFloat()
                translationY = -RiseDistance.toPx() * p
                translationX = driftPx.dp.toPx() * (0.35f + p) + arc * lane * 2.2f

                // 0..12%: 0.82 -> 1.08 -> 1.00. The overshoot is deliberately tiny; the
                // film remains the focal point and the reaction just acknowledges the tap.
                val pop = when {
                    p < 0.055f -> 0.82f + (p / 0.055f) * 0.26f
                    p < 0.12f -> 1.08f - ((p - 0.055f) / 0.065f) * 0.08f
                    else -> 1f
                }
                scaleX = pop
                scaleY = pop
                rotationZ = tilt * arc

                // Stay fully legible through most of the flight, fade only near the end.
                alpha = when {
                    p < 0.10f -> (p / 0.10f).coerceIn(0f, 1f)
                    p < 0.68f -> 1f
                    else -> (1f - (p - 0.68f) / 0.32f).coerceIn(0f, 1f)
                }
            }
            .glass(
                shape = GlassShapes.chip,
                fill = PlayerTokens.nextUpFill,
                border = Color.White.copy(alpha = 0.24f),
            )
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(burst.reaction.emoji, style = sc(18f, 400))
        if (burst.name.isNotBlank() && !burst.isMine) {
            Text(
                burst.name,
                style = mr(10f, 650),
                color = PlayerTokens.timeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
    }
}
