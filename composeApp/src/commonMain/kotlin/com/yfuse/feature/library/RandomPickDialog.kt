package com.yfuse.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.yfuse.core.designsystem.ThemeText as Text

internal enum class RandomPickFilter(
    val label: String,
) {
    All("全部"),
    HighRated("高分 7.5+"),
    Unplayed("未看"),
}

internal fun filterRandomPickCandidates(
    items: List<MediaItem>,
    filter: RandomPickFilter,
): List<MediaItem> =
    when (filter) {
        RandomPickFilter.All -> items
        RandomPickFilter.HighRated -> items.filter { (it.communityRating ?: 0.0) >= 7.5 }
        RandomPickFilter.Unplayed -> items.filter { !it.played }
    }

/**
 * 「片荒抽卡」 — 3D card-flip random picker for large libraries so users can pick something to watch
 * in one tap with tactile feedback.
 */
@Composable
internal fun RandomPickDialog(
    items: List<MediaItem>,
    baseUrl: String,
    accessToken: String,
    onOpen: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val haptics = LocalHaptics.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf(RandomPickFilter.All) }
    val candidates = remember(items, filter) { filterRandomPickCandidates(items, filter) }
    var rollCount by remember { mutableIntStateOf(0) }
    var picked by remember(candidates) {
        mutableStateOf(candidates.randomOrNull(Random(System.currentTimeMillis())))
    }
    val flip = remember { Animatable(0f) }
    val sweep = remember { Animatable(1f) }

    var rollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun reroll() {
        if (candidates.isEmpty()) return
        haptics.play(HapticSignal.Tap)
        rollJob?.cancel()
        rollJob =
            scope.launch {
                if (!reduceMotion) {
                    flip.animateTo(90f, Motion.tween(Motion.QUICK))
                }
                val pool = candidates.filterNot { it.id == picked?.id }.ifEmpty { candidates }
                picked = pool.random(Random(System.currentTimeMillis() + rollCount++))
                if (!reduceMotion) {
                    flip.snapTo(-90f)
                    sweep.snapTo(0f)
                    flip.animateTo(0f, Motion.tween(Motion.STANDARD))
                    sweep.animateTo(1f, Motion.tween(Motion.EMPHASIZED))
                }
                haptics.play(HapticSignal.Confirm)
            }
    }

    LaunchedEffect(candidates) {
        if (picked != null && picked !in candidates) {
            picked = candidates.randomOrNull()
        }
    }

    GlassDialog(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OverlayHeader(
                title = "片荒抽卡",
                subtitle = "从当前媒体库随机推荐一部作品",
                onClose = onDismiss,
            )
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                RandomPickFilter.entries.forEach { option ->
                    val selected = option == filter
                    Text(
                        option.label,
                        style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
                        color = if (selected) accent else palette.sub,
                        modifier =
                            Modifier
                                .clip(AppShapes.pill)
                                .background(
                                    if (selected) {
                                        accent.copy(alpha = if (palette.isDark) 0.22f else 0.12f)
                                    } else {
                                        palette.text.copy(alpha = if (palette.isDark) 0.08f else 0.05f)
                                    },
                                ).border(
                                    1.dp,
                                    if (selected) accent.copy(alpha = 0.5f) else palette.border.copy(alpha = 0.5f),
                                    AppShapes.pill,
                                ).pressable(
                                    role = Role.RadioButton,
                                    haptic = HapticSignal.Select,
                                    focusShape = AppShapes.pill,
                                    onClick = { filter = option },
                                ).semantics { this.selected = selected }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            val current = picked
            if (current == null) {
                Text(
                    "当前筛选下没有可抽取的影片",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(vertical = 36.dp),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            rotationY = flip.value
                            cameraDistance = 12f * density
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Poster(
                        url = EmbyImages.poster(baseUrl, current, maxHeight = 450, accessToken = accessToken),
                        contentDescription = current.title,
                        shape = AppShapes.card,
                        modifier =
                            Modifier
                                .width(156.dp)
                                .aspectRatio(2f / 3f)
                                .border(1.dp, palette.border, AppShapes.card)
                                .drawWithContent {
                                    drawContent()
                                    val p = sweep.value
                                    if (p in 0.001f..0.999f) {
                                        val band = size.width * 0.45f
                                        val startX = -band + (size.width + band * 2f) * p
                                        drawRect(
                                            brush =
                                                Brush.linearGradient(
                                                    colors =
                                                        listOf(
                                                            Color.Transparent,
                                                            Color.White.copy(alpha = 0.28f * (1f - p)),
                                                            accent.copy(alpha = 0.22f * (1f - p)),
                                                            Color.Transparent,
                                                        ),
                                                    start = Offset(startX, 0f),
                                                    end = Offset(startX + band, size.height),
                                                ),
                                        )
                                    }
                                },
                    )
                    Text(
                        current.title,
                        style = AppTypography.section.strong,
                        color = palette.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    val meta =
                        listOfNotNull(
                            current.year?.toString(),
                            current.communityRating?.let { "★ ${"%.1f".format(it)}" },
                            if (current.type.equals("Series", ignoreCase = true)) "剧集" else "电影",
                        ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = AppTypography.caption.medium, color = palette.sub)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            OverlayButtonRow(
                dismissLabel = "换一部",
                confirmLabel = "就看这部",
                confirmEnabled = picked != null,
                onDismiss = ::reroll,
                onConfirm = {
                    val chosen = picked ?: return@OverlayButtonRow
                    onDismiss()
                    onOpen(chosen)
                },
            )
        }
    }
}
