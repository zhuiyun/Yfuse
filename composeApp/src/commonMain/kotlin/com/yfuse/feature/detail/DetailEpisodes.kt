package com.yfuse.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages

/** Width of the floating season list; wide enough for "第 12 季 · 特别篇" without wrapping. */
private val SeasonPickerWidth = 236.dp
private val SeasonPickerMaxHeight = 360.dp
private val SeasonPickerGap = 8.dp

/** Scale the season list grows from; the rest of the way is the settle spring. */
private const val SEASON_PICKER_SCALE_FROM = 0.82f

/**
 * Season header with the `切换季数 ▾` chip.
 *
 * The season list is a floating glass panel anchored to the chip. It is a real [Popup]: an
 * overlay drawn inline from a lazy item would be painted under the rows that follow it, and
 * a separate window is also what lets the panel scale in over the episode strip instead of
 * pushing it down the page.
 */
@Composable
private fun EpisodeHeader(
    accent: Color,
    seasonLabel: String,
    availableEpisodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onManageProgress: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rotation by animateFloatAsState(
        targetValue = if (pickerOpen) 180f else 0f,
        animationSpec = Motion.settle(reduceMotion),
        label = "seasonChevron",
    )
    Column(modifier) {
        SectionHeader(seasonLabel) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // This count comes from Emby, not the official production total.
                Row(
                    Modifier
                        .pressable(onClick = onSeeAll)
                        .heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已入库 $availableEpisodeCount 集",
                        style = AppTypography.caption.strong,
                        color = palette.body,
                    )
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "查看全部剧集",
                        tint = palette.sub2,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Row(
                    Modifier
                        .pressable(onClickLabel = "管理观看进度", onClick = onManageProgress)
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Text("管理进度", style = AppTypography.caption.strong, color = accent)
                }
                if (seasons.size > 1) {
                    // The popup is a child of this box so its position provider receives the
                    // chip's bounds as the anchor.
                    Box {
                        Row(
                            Modifier
                                .pressable(onClick = onTogglePicker)
                                .heightIn(min = 44.dp)
                                .shadow(GlassLift.control, GlassShapes.thumb)
                                .liquidGlass(
                                    shape = GlassShapes.thumb,
                                    fill = accent.copy(alpha = 0.13f),
                                    border = accent.copy(alpha = 0.28f),
                                    sheen = 0.7f,
                                ).padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("切换季数", style = AppTypography.caption.medium, color = accent)
                            Icon(
                                AppIcons.ChevronDown,
                                null,
                                tint = accent,
                                modifier = Modifier.size(9.dp).graphicsLayer { rotationZ = rotation },
                            )
                        }
                        SeasonPickerPopup(
                            open = pickerOpen,
                            accent = accent,
                            seasons = seasons,
                            selectedSeasonId = selectedSeasonId,
                            onSelectSeason = onSelectSeason,
                            onDismiss = onDismissPicker,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The floating season list. Glass over the page, right-aligned under the chip, scaling in
 * from its top-right corner and back out again; it stays composed while the exit runs so the
 * close is as deliberate as the open.
 */
@Composable
private fun SeasonPickerPopup(
    open: Boolean,
    accent: Color,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    onSelectSeason: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open, reduceMotion) {
        if (open) {
            progress.animateTo(1f, Motion.settle(reduceMotion))
        } else {
            progress.animateTo(
                0f,
                if (reduceMotion) snap() else tween(Motion.QUICK, easing = Motion.Curve),
            )
        }
    }
    // Composed while opening, open, or still animating shut.
    if (!open && progress.value <= 0f) return
    val gapPx = with(LocalDensity.current) { SeasonPickerGap.roundToPx() }
    val positionProvider = remember(gapPx) { SeasonPickerPositionProvider(gapPx) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    val entered = progress.value
                    alpha = entered
                    val scale = SEASON_PICKER_SCALE_FROM + (1f - SEASON_PICKER_SCALE_FROM) * entered
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0f)
                }.width(SeasonPickerWidth)
                .shadow(Shadows.menu, GlassShapes.sheet)
                .liquidGlass(
                    shape = GlassShapes.sheet,
                    fill =
                        if (palette.isDark) {
                            Color(0xFF111A29).copy(alpha = 0.90f)
                        } else {
                            Color.White.copy(alpha = 0.80f)
                        },
                    border = palette.border,
                ).heightIn(max = SeasonPickerMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            seasons.forEach { (id, name) ->
                val selected = id == selectedSeasonId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics { this.selected = selected }
                        .pressable(
                            role = Role.RadioButton,
                            focusShape = GlassShapes.chip,
                            onClick = { onSelectSeason(id) },
                        )
                        .then(
                            if (selected) {
                                Modifier.background(accent.copy(alpha = 0.12f), GlassShapes.chip)
                            } else {
                                Modifier
                            },
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
                        color = if (selected) accent else palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                AppIcons.Check,
                                contentDescription = "当前季",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Right edge on the chip's right edge, opening below it; above it when the chip is too close
 * to the bottom of the window. Never off-screen horizontally.
 */
private class SeasonPickerPositionProvider(
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
        val below = anchorBounds.bottom + gapPx
        val y =
            if (below + popupContentSize.height <= windowSize.height) {
                below
            } else {
                (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
            }
        return IntOffset(x, y)
    }
}

@Composable
internal fun EpisodeSection(
    baseUrl: String,
    accessToken: String,
    episodes: List<Episode>,
    seriesPosterUrl: String?,
    selectedEpisodeId: String?,
    accent: Color,
    seasonLabel: String,
    availableEpisodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onManageProgress: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onSeeAll: () -> Unit,
) {
    val listState = rememberLazyListState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    val focusedEpisodeIndex =
        remember(episodes, selectedEpisodeId) {
            episodeFocusIndex(episodes, selectedEpisodeId)
        }
    var initiallyPositioned by remember(selectedSeasonId) { mutableStateOf(false) }

    Column(Modifier.padding(top = Dimens.sectionGap)) {
        EpisodeHeader(
            accent = accent,
            onSeeAll = onSeeAll,
            seasonLabel = seasonLabel,
            availableEpisodeCount = availableEpisodeCount,
            seasons = seasons,
            selectedSeasonId = selectedSeasonId,
            pickerOpen = pickerOpen,
            onTogglePicker = onTogglePicker,
            onDismissPicker = onDismissPicker,
            onSelectSeason = onSelectSeason,
            onManageProgress = onManageProgress,
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val centeredOffset =
                with(density) {
                    -((maxWidth - 210.dp) / 2f).coerceAtLeast(0.dp).roundToPx()
                }
            LaunchedEffect(
                selectedEpisodeId,
                focusedEpisodeIndex,
                reduceMotion,
                routeVisible,
                centeredOffset,
            ) {
                if (!routeVisible || focusedEpisodeIndex < 0) return@LaunchedEffect
                if (!initiallyPositioned || reduceMotion) {
                    listState.scrollToItem(focusedEpisodeIndex, centeredOffset)
                    initiallyPositioned = true
                } else {
                    listState.animateScrollToItem(focusedEpisodeIndex, centeredOffset)
                }
            }
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
                contentPadding =
                    PaddingValues(
                        start = Dimens.pageHorizontal,
                        top = 10.dp,
                        end = Dimens.pageHorizontal,
                        bottom = 0.dp,
                    ),
            ) {
                itemsIndexed(
                    episodes,
                    key = { index, episode -> "ep-${episode.id}-$index" },
                ) { _, episode ->
                    EpisodeCard(
                        baseUrl = baseUrl,
                        accessToken = accessToken,
                        episode = episode,
                        seriesPosterUrl = seriesPosterUrl,
                        accent = accent,
                        selected = episode.id == selectedEpisodeId,
                        onPlay = { onPlayEpisode(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    baseUrl: String,
    accessToken: String,
    episode: Episode,
    seriesPosterUrl: String?,
    accent: Color,
    selected: Boolean,
    onPlay: () -> Unit,
) {
    val palette = LocalPalette.current
    val stateColors = detailStateColors(accent, palette.background, palette.isDark)
    val selectedHighlight = Color.White
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val selectedScale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = Motion.settle(reduceMotion),
        label = "episodeCardSelectionScale",
    )
    Column(
        Modifier
            .width(210.dp)
            .graphicsLayer {
                scaleX = selectedScale
                scaleY = selectedScale
                transformOrigin = TransformOrigin(0.5f, 1f)
            }.pressable(onClick = onPlay)
            .solidGlass(
                shape = GlassShapes.card,
                fill =
                    if (selected) {
                        selectedHighlight.copy(alpha = if (palette.isDark) 0.24f else 0.30f)
                    } else if (palette.isDark) {
                        palette.card
                    } else {
                        Color.White.copy(alpha = 0.24f)
                    },
                border = Color.Transparent,
            ).then(
                if (selected) {
                    Modifier.border(3.dp, selectedHighlight, GlassShapes.card)
                } else {
                    Modifier
                },
            ).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(108.dp)) {
            Poster(
                url =
                    EmbyImages.primary(
                        baseUrl,
                        episode.id,
                        episode.primaryTag,
                        maxHeight = 240,
                        accessToken = accessToken,
                    ),
                fallbackUrls = listOfNotNull(seriesPosterUrl),
                shape = GlassShapes.thumb,
                progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                modifier = Modifier.fillMaxSize(),
            )
            if (episode.played) {
                EpisodeWatchedBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Column {
            Text(
                listOfNotNull(episode.indexNumber?.let { "第${it}集" }, episode.name)
                    .joinToString(" · "),
                style = AppTypography.body.strong,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!episode.overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    episode.overview,
                    style = AppTypography.caption.regular.copy(lineHeight = 16.5.sp),
                    color = palette.sub2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (selected) {
                        append("当前剧集 · 点击播放")
                    } else if (watching) {
                        append("正在观看")
                    }
                    val runtime = episode.runtimeMinutes?.let { "$it 分钟" }
                    if ((selected || watching) && runtime != null) append(" · ")
                    if (runtime != null) append(runtime)
                },
                style = AppTypography.caption.medium,
                color =
                    when {
                        selected -> stateColors.onPage
                        watching -> stateColors.mutedOnPage
                        else -> palette.sub2
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 主演 — `gap:14px`; 52px round avatars with `500 10px Manrope` names 6px below. */
