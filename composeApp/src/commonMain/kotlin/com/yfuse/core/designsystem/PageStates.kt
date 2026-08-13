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

@Composable
private fun AccentChipButton(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        label,
        style = AppTypography.body.strong,
        color = accent.accent,
        modifier =
            Modifier
                .pressable(onClick = onClick)
                .touchTarget()
                .liquidGlass(
                    shape = GlassShapes.chip,
                    fill = palette.glassStrong,
                    border = accent.border,
                    over = palette.background,
                    sheen = 0.68f,
                ).padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
fun skeletonFill(): Color = if (LocalPalette.current.isDark) Color.White.copy(alpha = 0.08f) else Color(0x2996A0B4)

private const val SKELETON_PULSE_MS = 1_600f
private const val SKELETON_PULSE_FLOOR = 0.45f

@Stable
private class SkeletonPulseClock {
    var consumerCount by mutableIntStateOf(0)
        private set

    val alpha = mutableFloatStateOf(1f)

    fun registerConsumer() {
        consumerCount += 1
    }

    fun unregisterConsumer() {
        consumerCount = (consumerCount - 1).coerceAtLeast(0)
    }
}

private val LocalSkeletonPulseClock = staticCompositionLocalOf<SkeletonPulseClock?> { null }

private fun skeletonPulseAt(millis: Long): Float {
    val phase = (millis % SKELETON_PULSE_MS.toLong()) / SKELETON_PULSE_MS
    val wave = (1f - cos(phase * 2f * PI.toFloat())) / 2f
    return SKELETON_PULSE_FLOOR + (1f - SKELETON_PULSE_FLOOR) * wave
}

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

@Composable
fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape = AppShapes.micro,
) {
    val clock = LocalSkeletonPulseClock.current
    DisposableEffect(clock) {
        clock?.registerConsumer()
        onDispose { clock?.unregisterConsumer() }
    }
    Box(
        modifier
            .clip(shape)
            .graphicsLayer { alpha = clock?.alpha?.floatValue ?: 1f }
            .background(skeletonFill()),
    )
}

@Composable
fun SkeletonPosterTile(
    modifier: Modifier = Modifier,
    posterHeight: Dp = 150.dp,
) {
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
