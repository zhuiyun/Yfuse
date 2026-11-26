package com.yfuse.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.SavedServer
import kotlin.math.abs
import kotlin.math.exp
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Low-pass filtered horizontal roll tilt in `[-1f, 1f]` from the device gravity/accelerometer sensor
 * when [enabled] is true; returns constant `0f` when disabled or on platforms without sensors.
 */
@Composable
internal expect fun rememberDockTiltX(enabled: Boolean): State<Float>

/**
 * Computes smooth Gaussian magnification (`1.0f..1.16f`) for a tab icon at [tabIndex] while
 * the user drags the liquid lens across the dock.
 */
internal fun dockLensHoverScale(
    tabIndex: Int,
    lensCenterIndex: Float,
    dragging: Boolean,
): Float {
    if (!dragging) return 1f
    val distance = abs(tabIndex - lensCenterIndex)
    val influence = exp(-(distance * distance) * 2.8f)
    return 1f + 0.16f * influence
}

/**
 * Draws a subtle gyroscope-reactive crystal specular rim + moving sheen band across the dock
 * capsule so tilting the phone produces a living liquid-glass refraction glint.
 */
internal fun Modifier.dockGyroSpecularSheen(
    tiltX: State<Float>,
    dark: Boolean,
    enabled: Boolean,
): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithContent {
            drawContent()
            val t = tiltX.value.coerceIn(-1f, 1f)
            val centerX = size.width * (0.5f + t * 0.36f)
            val halfSpan = size.width * 0.28f
            val peakAlpha = if (dark) 0.24f else 0.34f
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            // Soft internal prism sweep
            drawRoundRect(
                brush =
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = peakAlpha * 0.38f),
                                1f to Color.Transparent,
                            ),
                        startX = centerX - halfSpan,
                        endX = centerX + halfSpan,
                    ),
                cornerRadius = corner,
            )
            // Razor-thin specular rim glint reacting to tilt
            drawRoundRect(
                brush =
                    Brush.horizontalGradient(
                        colorStops =
                            arrayOf(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = peakAlpha),
                                1f to Color.Transparent,
                            ),
                        startX = centerX - halfSpan * 0.9f,
                        endX = centerX + halfSpan * 0.9f,
                    ),
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }

/**
 * Ambient breathing halo around the dock capsule while Cast, WatchTogether, or background playback
 * is active.
 */
internal fun Modifier.dockActiveSessionHalo(
    active: Boolean,
    accent: Color,
    reduceMotion: Boolean,
): Modifier =
    if (!active) {
        this
    } else {
        composed {
            val haloAlpha =
                if (reduceMotion) {
                    0.26f
                } else {
                    val transition = rememberInfiniteTransition(label = "dockSessionHalo")
                    val animated by transition.animateFloat(
                        initialValue = 0.14f,
                        targetValue = 0.42f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMillis = 2200, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "dockSessionHaloAlpha",
                    )
                    animated
                }
            drawBehind {
                val expand = 3.dp.toPx()
                val corner = CornerRadius((size.height + expand * 2f) / 2f)
                drawRoundRect(
                    color = accent.copy(alpha = haloAlpha),
                    topLeft = Offset(-expand, -expand),
                    size = Size(size.width + expand * 2f, size.height + expand * 2f),
                    cornerRadius = corner,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
    }

/** Expanding circular glass refraction ripple played when re-tapping the active tab to scroll to top. */
internal fun Modifier.dockReselectRipple(
    reselectTick: Int,
    accent: Color,
    reduceMotion: Boolean,
): Modifier =
    if (reduceMotion || reselectTick <= 0) {
        this
    } else {
        composed {
            val progress = remember { Animatable(1f) }
            LaunchedEffect(reselectTick) {
                if (reselectTick > 0) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(durationMillis = 420, easing = Motion.Curve))
                }
            }
            drawWithContent {
                drawContent()
                val p = progress.value
                if (p in 0.001f..0.999f) {
                    val maxRadius = minOf(size.width, size.height) * 0.52f
                    val radius = maxRadius * (0.28f + 0.72f * p)
                    val alpha = (1f - p) * 0.55f
                    drawCircle(
                        color = accent.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = (2.dp * (1f - p * 0.5f)).toPx()),
                    )
                }
            }
        }
    }

internal sealed interface DockQuickAction {
    data object Servers : DockQuickAction
    data object History : DockQuickAction
    data object Search : DockQuickAction
}

/**
 * Floating Liquid Glass quick-action popover anchored right above the bottom dock when long-pressing
 * 服务器 (quick server switch), 历史 (jump to history/resume), or 搜索 (quick search).
 */
@Composable
internal fun DockQuickActionPopover(
    action: DockQuickAction?,
    servers: List<SavedServer>,
    activeServerId: String?,
    onSelectServer: (String) -> Unit,
    onOpenServers: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    AnimatedVisibility(
        visible = action != null,
        modifier = modifier,
        enter =
            fadeIn(tween(180)) +
                slideInVertically(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)) { it / 3 } +
                scaleIn(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium), initialScale = 0.92f),
        exit = fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 4 } + scaleOut(tween(120), targetScale = 0.95f),
        label = "dockQuickActionPopover",
    ) {
        val current = action ?: return@AnimatedVisibility
        Column(
            Modifier
                .widthIn(min = 210.dp, max = 300.dp)
                .glass(
                    shape = AppShapes.card,
                    fill = if (palette.isDark) Color(0xE6141824) else Color(0xF0FFFFFF),
                    border = if (palette.isDark) Color.White.copy(alpha = 0.16f) else palette.border,
                ).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (current) {
                DockQuickAction.Servers -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("快速切换服务器", style = AppTypography.caption.strong, color = palette.sub)
                        Text(
                            "全部 ›",
                            style = AppTypography.caption.strong,
                            color = accent.accent,
                            modifier =
                                Modifier.pressable(haptic = HapticSignal.Select) {
                                    onDismiss()
                                    onOpenServers()
                                },
                        )
                    }
                    if (servers.isEmpty()) {
                        Text(
                            "暂未添加服务器",
                            style = AppTypography.caption.regular,
                            color = palette.sub2,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    } else {
                        servers.take(5).forEach { server ->
                            val isCurrent = server.id == activeServerId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(AppShapes.chip)
                                    .background(if (isCurrent) accent.container else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (isCurrent) accent.border.copy(alpha = 0.35f) else Color.Transparent,
                                        AppShapes.chip,
                                    ).pressable(haptic = HapticSignal.Confirm) {
                                        onSelectServer(server.id)
                                        onDismiss()
                                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrent) Brand.Online else palette.border),
                                )
                                Text(
                                    server.serverName,
                                    style = AppTypography.body.strong,
                                    color = if (isCurrent) accent.accent else palette.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrent) {
                                    Icon(AppIcons.Check, null, tint = accent.accent, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }

                DockQuickAction.History -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.chip)
                            .background(accent.container)
                            .pressable(haptic = HapticSignal.Confirm) {
                                onDismiss()
                                onOpenHistory()
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(AppIcons.Play, null, tint = accent.accent, modifier = Modifier.size(15.dp))
                        Text("直达最近观看记录", style = AppTypography.body.strong, color = accent.accent)
                    }
                }

                DockQuickAction.Search -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.chip)
                            .background(accent.container)
                            .pressable(haptic = HapticSignal.Confirm) {
                                onDismiss()
                                onOpenSearch()
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(AppIcons.SearchTab, null, tint = accent.accent, modifier = Modifier.size(15.dp))
                        Text("立即搜索全库影片", style = AppTypography.body.strong, color = accent.accent)
                    }
                }
            }
        }
    }
}
