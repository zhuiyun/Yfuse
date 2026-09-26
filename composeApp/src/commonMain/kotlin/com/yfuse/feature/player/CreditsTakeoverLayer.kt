package com.yfuse.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Where 片尾接管 stands, followed along the timeline in a derived state: the chrome recomposes when
 * the phase changes, not on every position sample through the credits.
 */
@Composable
internal fun rememberCreditsTakeoverPhase(
    playback: State<PlaybackState>,
    credits: PlaybackSegment?,
    blocked: Boolean,
): State<CreditsTakeoverPhase> {
    val latestCredits by rememberUpdatedState(credits)
    val latestBlocked by rememberUpdatedState(blocked)
    return remember(playback) {
        derivedStateOf {
            val live = playback.value
            creditsTakeoverPhase(
                positionMs = live.positionMs,
                durationMs = live.durationMs,
                credits = latestCredits,
                hasNext = live.hasNext,
                finished = live.ended || live.error != null,
                blocked = latestBlocked,
            )
        }
    }
}

/**
 * The card beside the credits: which episode is next, 看完片尾 to have the picture back and stay,
 * and the play-next action the ordinary card has. The countdown stays with that card, which takes
 * over for the file's last seconds; this one only offers.
 */
@Composable
internal fun CreditsTakeoverCard(
    title: String,
    onWatchCredits: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = CreditsCardMaxWidth)
            .shadow(Shadows.tabBar, GlassShapes.card)
            .glass(
                shape = GlassShapes.card,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Said once as the card arrives.
        Column(Modifier.semantics(mergeDescendants = true) {}.liveStatus()) {
            Text("下一集", style = AppTypography.caption.strong, color = PlayerTokens.footerText)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "看完片尾",
                style = AppTypography.caption.medium,
                color = PlayerTokens.timeText,
                modifier =
                    Modifier
                        .pressable(onClickLabel = "看完片尾，恢复全屏画面", onClick = onWatchCredits)
                        .touchTarget()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "播放下一集",
                style = AppTypography.caption.strong,
                color = PlayerTokens.onPlay,
                modifier =
                    Modifier
                        .pressable(haptic = HapticSignal.Confirm, onClickLabel = "立即播放下一集", onClick = onPlayNext)
                        .touchTarget()
                        .glass(shape = AppShapes.pill, fill = PlayerTokens.playFill)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * The picture's side of 片尾接管, for the surface each engine draws into: drawn back into the
 * top-start corner while [active], full size otherwise, on [Motion.creditsTakeover].
 *
 * Under 减弱动态效果 and 静息 nothing shrinks: the picture dips to black, moves while it cannot be
 * seen, and comes back, each half [Motion.QUICK]. The dip is drawn over the surface rather than as
 * the surface's own alpha, which a SurfaceView does not honour.
 *
 * Only layer properties and a draw pass read the animation, so a frame of it recomposes nothing.
 */
@Composable
internal fun Modifier.creditsTakeoverPicture(
    active: Boolean,
    /** Jump rather than move — the window itself is changing shape, as it does entering 画中画. */
    immediate: Boolean = false,
): Modifier {
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val shrink = remember { Animatable(if (active) 1f else 0f) }
    val veil = remember { Animatable(0f) }
    LaunchedEffect(active, still, immediate) {
        val target = if (active) 1f else 0f
        if (shrink.value == target || immediate) {
            veil.snapTo(0f)
            shrink.snapTo(target)
            return@LaunchedEffect
        }
        if (still) {
            veil.animateTo(1f, Motion.tween(Motion.QUICK))
            shrink.snapTo(target)
            veil.animateTo(0f, Motion.tween(Motion.QUICK))
        } else {
            veil.snapTo(0f)
            shrink.animateTo(target, Motion.creditsTakeover())
        }
    }
    val marginPx = with(LocalDensity.current) { CreditsPictureMargin.toPx() }
    // Outside a takeover and its way back, the surface gets no layer at all: ordinary playback
    // keeps exactly the drawing path it had.
    val engaged by remember { derivedStateOf { shrink.value > 0f || veil.value > 0f } }
    if (!active && !engaged) return this
    return this
        .graphicsLayer {
            val amount = shrink.value
            if (amount > 0f) {
                val scale = 1f - (1f - CREDITS_PICTURE_SCALE) * amount
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = marginPx * amount
                translationY = marginPx * amount
            }
        }.drawWithContent {
            drawContent()
            val dim = veil.value
            if (dim > 0f) drawRect(Color.Black, alpha = dim)
        }
}

private val CreditsCardMaxWidth = 320.dp

/** Clear of the corner's own curve on a rounded screen, and of the status strip's edge. */
private val CreditsPictureMargin = 16.dp
