package com.yfuse.core.designsystem

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos

/**
 * The two things a page shows instead of content: a message it can do nothing about, and
 * a failure it can retry.
 *
 * Five screens had written the failure state out by hand — 首页, 媒体库, 媒体库网格, 搜索
 * and 影视详情页 — and no two agreed. The label ran at 13sp in three of them and 12.5sp in
 * search; 首页's sat in a stock `TextButton` with no colour at all, so it fell through to
 * Material's default primary and ignored the palette entirely. The retry chip is also the
 * place the invisible-hairline problem showed up: `palette.border` is a 70% white on a
 * light theme whose detail pages are white, so the button's edge simply was not there.
 * Carrying the accent on both fill and border fixes that on either theme.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
) {
    val palette = LocalPalette.current
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            message,
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.error,
            textAlign = TextAlign.Center,
        )
        AccentChipButton(label = retryLabel, onClick = onRetry)
    }
}

/**
 * Centred note with nothing to act on — no server configured, no results, empty library.
 *
 * [actionLabel] turns it into somewhere to go. An empty 我的收藏 or 稍后观看 is the state a
 * new user is in most often, and without the chip those pages are a dead end: the text
 * names what is missing and offers no way to fix it.
 *
 * [icon] gives the state a shape. A single line of grey text centred in an empty page is
 * indistinguishable from a page that failed to render — the eye has nothing to land on and
 * no signal that this *is* the content. A quiet glyph above the copy is what turns "nothing
 * here" from an absence into a statement, which is the whole of Apple's
 * `ContentUnavailableView`: symbol, then sentence, then the one thing to do about it.
 */
@Composable
fun PageHint(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = AppIcons.Info,
) {
    val palette = LocalPalette.current
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                // Quieter than the copy it introduces: it is orientation, not information.
                tint = palette.sub2.copy(alpha = 0.55f),
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            text,
            style = AppTypography.body.regular.copy(lineHeight = 21.sp),
            color = palette.sub,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            AccentChipButton(label = actionLabel, onClick = onAction)
        }
    }
}

/** The accent-tinted chip both page states use to offer their one action. */
@Composable
private fun AccentChipButton(label: String, onClick: () -> Unit) {
    val accent = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.body.strong,
        color = accent.accent,
        modifier = Modifier
            .pressable(onClick = onClick)
            .touchTarget()
            .solidGlass(
                shape = GlassShapes.chip,
                fill = accent.container,
                border = accent.border,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/**
 * Fill for loading placeholders, matched to the palette.
 *
 * A skeleton is only worth drawing if it is quieter than the content it stands in for;
 * these two values are the ones 媒体库's rail skeleton already used.
 */
@Composable
fun skeletonFill(): Color =
    if (LocalPalette.current.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)

/** A full breath of the skeleton pulse, in milliseconds. */
private const val SKELETON_PULSE_MS = 1_600f

/** How far down the breath goes. Perceptible as motion, quiet enough not to flash. */
private const val SKELETON_PULSE_FLOOR = 0.45f

@Stable
private class SkeletonPulseClock {
    var consumerCount by mutableIntStateOf(0)
        private set

    /** Read from the graphics layer so frame updates do not recompose the provider tree. */
    val alpha = mutableFloatStateOf(1f)

    fun registerConsumer() {
        consumerCount += 1
    }

    fun unregisterConsumer() {
        consumerCount = (consumerCount - 1).coerceAtLeast(0)
    }
}

/** Null is the intentional no-provider fallback: a still, fully opaque skeleton. */
private val LocalSkeletonPulseClock = staticCompositionLocalOf<SkeletonPulseClock?> { null }

/**
 * The breath every placeholder shares.
 *
 * A static skeleton is the thing worth avoiding: a screen of motionless grey blocks is
 * exactly what a page that has failed to load looks like, and the user has no way to tell
 * the two apart. The pulse is the part that says the app is still working.
 *
 * Phase comes from the animation clock's absolute time rather than from a per-block
 * animation, so every placeholder breathes together no matter when it was composed. Blocks
 * that each started their own transition would drift apart within seconds and the page would
 * shimmer at random, which reads as noise rather than as waiting.
 *
 * `withInfiniteAnimationFrameMillis` also honours the platform's animator scale, so a device
 * with animations turned off in developer options gets a still skeleton for free; 减弱动态
 * 效果 is handled explicitly above it.
 */
private fun skeletonPulseAt(millis: Long): Float {
    // A cosine is its own easing — smooth at both ends, no curve to apply and no reversal
    // to schedule.
    val phase = (millis % SKELETON_PULSE_MS.toLong()) / SKELETON_PULSE_MS
    val wave = (1f - cos(phase * 2f * PI.toFloat())) / 2f
    return SKELETON_PULSE_FLOOR + (1f - SKELETON_PULSE_FLOOR) * wave
}

/**
 * Provides one animation clock to every skeleton below it. Install once at an app or preview
 * root; without a provider skeletons remain safely static instead of starting per-block loops.
 */
@Composable
fun SkeletonPulseProvider(content: @Composable () -> Unit) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val clock = remember { SkeletonPulseClock() }
    val hasConsumers = clock.consumerCount > 0

    LaunchedEffect(clock, reduceMotion, hasConsumers) {
        if (reduceMotion || !hasConsumers) {
            clock.alpha.floatValue = 1f
            return@LaunchedEffect
        }
        while (true) {
            withInfiniteAnimationFrameMillis { millis ->
                clock.alpha.floatValue = skeletonPulseAt(millis)
            }
        }
    }

    CompositionLocalProvider(LocalSkeletonPulseClock provides clock, content = content)
}

/** One rounded placeholder block. Sized by the caller so it matches what it replaces. */
@Composable
fun SkeletonBlock(modifier: Modifier, shape: Shape = AppShapes.micro) {
    val clock = LocalSkeletonPulseClock.current
    DisposableEffect(clock) {
        clock?.registerConsumer()
        onDispose { clock?.unregisterConsumer() }
    }
    Box(
        modifier
            .clip(shape)
            .graphicsLayer {
                // Snapshot reads in this layer callback invalidate only the layer, not the
                // provider or the content tree on every animation frame.
                alpha = clock?.alpha?.floatValue ?: 1f
            }
            .background(skeletonFill()),
    )
}

/**
 * A poster tile placeholder: artwork, title line, caption line.
 *
 * Shared so the grid, the rails and the detail page all reserve the same shapes — a
 * spinner tells the user only that something is happening, while these hold the layout
 * still so nothing jumps when the real posters land.
 */
@Composable
fun SkeletonPosterTile(modifier: Modifier = Modifier, posterHeight: Dp = 150.dp) {
    Column(modifier) {
        SkeletonBlock(
            Modifier.fillMaxWidth().height(posterHeight),
            shape = AppShapes.card,
        )
        Spacer(Modifier.height(7.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(12.dp), shape = AppShapes.micro)
        Spacer(Modifier.height(5.dp))
        SkeletonBlock(Modifier.width(42.dp).height(9.dp), shape = AppShapes.micro)
    }
}

/** A shelf placeholder: heading, then a row of poster tiles. */
@Composable
fun SkeletonRail(
    modifier: Modifier = Modifier,
    posterWidth: Dp = 104.dp,
    posterHeight: Dp = 150.dp,
    count: Int = 3,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(Modifier.width(90.dp).height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(count) {
                SkeletonPosterTile(Modifier.width(posterWidth), posterHeight = posterHeight)
            }
        }
    }
}
