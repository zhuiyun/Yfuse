package com.yfuse.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.touchTarget
import kotlin.math.ceil
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** How long before the end 下一集 announces itself. */
internal const val NEXT_UP_WINDOW_MS = 10_000L

/** From here on the key says the next episode is about to start rather than that it will. */
private const val NEXT_UP_SOON_SECONDS = 3

/** Whole seconds of real time until 下一集 starts: the remaining media sped up by [speed], rounded up. */
internal fun nextUpSecondsLeft(
    remainingMs: Long,
    speed: Float,
): Int {
    val rate = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
    return ceil(remainingMs.coerceAtLeast(0L) / rate / 1_000f).toInt()
}

/**
 * What the next-up key says about its countdown, or null when there is none to speak of: with
 * 自动播放下一集 off nothing starts on its own, so neither a draining ring nor 即将自动播放 has
 * anything true to say, and the card becomes a plain 下一集 key ([NextUpKey]).
 *
 * Coarse on purpose: TalkBack reads a state change on the key it rests on, and a figure that
 * changed every second was read every second. The ring still counts the seconds down.
 */
internal fun nextUpCountdownLabel(
    autoAdvance: Boolean,
    remainingMs: Long,
    speed: Float,
): String? =
    when {
        !autoAdvance -> null
        nextUpSecondsLeft(remainingMs, speed) > NEXT_UP_SOON_SECONDS -> "即将自动播放"
        else -> "马上自动播放"
    }

/**
 * 片尾自动连播 — the countdown the spec drew and nobody built.
 *
 * [PlayerTokens.nextUpFill], `nextUpRing`, `nextUpRingTrack` and `nextUpCore` were all
 * declared for this card and referenced nowhere in the app; what shipped instead was one
 * line of text reading 「下一集将在 N 秒后播放」, with no way to start it early and no way
 * to stop it. The ring drains as the episode does, the core starts the next one on tap,
 * and 取消 leaves the credits alone.
 */
@Composable
internal fun NextUpCard(
    title: String,
    remainingMs: Long,
    /** From [nextUpCountdownLabel]; this card only exists while there is a countdown. */
    countdown: String,
    playbackKey: Any,
    advancing: Boolean,
    speed: Float,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val remaining = rememberNextUpRemaining(playbackKey, remainingMs, advancing, speed)
    Row(
        modifier
            .shadow(Shadows.tabBar, GlassShapes.card)
            .glass(
                shape = GlassShapes.card,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            ).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Said once as the card arrives; the seconds live on the key below, where a spoken
        // cursor can ask for them without every tick being read out.
        Column(
            Modifier.semantics(mergeDescendants = true) {}.liveStatus(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("即将播放", style = AppTypography.caption.strong, color = PlayerTokens.footerText)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            }
        }
        Text(
            "取消",
            style = AppTypography.caption.medium,
            color = PlayerTokens.timeText,
            modifier =
                Modifier
                    .pressable(onClickLabel = "取消自动播放", onClick = onDismiss)
                    .touchTarget()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .semantics { stateDescription = countdown }
                .pressable(
                    haptic = HapticSignal.Confirm,
                    onClickLabel = "立即播放下一集",
                    onClick = onPlayNow,
                ).touchTarget()
                .size(38.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val progress = (remaining.value / NEXT_UP_WINDOW_MS).coerceIn(0f, 1f)
                val stroke = 2.5.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f
                drawCircle(
                    color = PlayerTokens.nextUpCore,
                    radius = radius - stroke / 2f,
                )
                drawCircle(
                    color = PlayerTokens.nextUpRingTrack,
                    radius = radius,
                    style = Stroke(width = stroke),
                )
                // Drains clockwise from the top as the episode runs out.
                drawArc(
                    color = accent.accent,
                    startAngle = -90f,
                    sweepAngle = -360f * (1f - progress),
                    useCenter = false,
                    topLeft =
                        Offset(
                            (size.width - radius * 2f) / 2f,
                            (size.height - radius * 2f) / 2f,
                        ),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Icon(
                AppIcons.Play,
                contentDescription = "立即播放下一集",
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 下一集 with 自动播放下一集 off: the same card in the same corner, as one key instead of a
 * countdown. Nothing is going to start on its own, so there is no ring to drain, no 即将自动播放
 * and no 取消 — only the way on, for whoever wants it before the credits have run out.
 */
@Composable
internal fun NextUpKey(
    title: String,
    onPlayNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .pressable(
                haptic = HapticSignal.Confirm,
                focusShape = GlassShapes.card,
                onClickLabel = "播放下一集",
                onClick = onPlayNow,
            ).shadow(Shadows.tabBar, GlassShapes.card)
            .glass(
                shape = GlassShapes.card,
                fill = PlayerTokens.nextUpFill,
                border = PlayerTokens.hairline,
            ).padding(horizontal = Dimens.space.lg, vertical = Dimens.space.md),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text("下一集", style = AppTypography.caption.strong, color = PlayerTokens.footerText)
            if (title.isNotBlank()) {
                Spacer(Modifier.height(Dimens.space.xs))
                Text(
                    title,
                    style = AppTypography.body.strong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
            }
        }
        Box(
            Modifier
                .size(38.dp)
                .background(PlayerTokens.nextUpCore, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Next,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
