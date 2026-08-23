package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages

@Composable
private fun EpisodeHeader(
    accent: Color,
    seasonLabel: String,
    episodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
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
    Column(
        modifier.animateContentSize(
            animationSpec = if (reduceMotion) snap() else Motion.settle(),
        ),
    ) {
        SectionHeader(seasonLabel) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 共 N 集 is the label and the way in: a rail shows four of them, and the
                // count is exactly the promise the full list keeps.
                Row(
                    Modifier
                        .pressable(onClick = onSeeAll)
                        .heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("共 $episodeCount 集", style = AppTypography.caption.strong, color = palette.body)
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "查看全部剧集",
                        tint = palette.sub2,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (seasons.size > 1) {
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
                }
            }
        }
        if (pickerOpen && seasons.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                itemsIndexed(
                    seasons,
                    key = { index, season -> "season-${season.first}-$index" },
                ) { _, (id, name) ->
                    val selected = id == selectedSeasonId
                    Text(
                        name,
                        style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
                        color = if (selected) accent else palette.body,
                        maxLines = 1,
                        modifier =
                            Modifier
                                .pressable { onSelectSeason(id) }
                                // No lift: these read as one picker's options, and a shadow under
                                // each would break the row into a scatter of separate keys.
                                .liquidGlass(
                                    shape = GlassShapes.thumb,
                                    fill =
                                        if (selected) {
                                            accent.copy(alpha = 0.14f)
                                        } else if (palette.isDark) {
                                            palette.card2
                                        } else {
                                            Color.White.copy(alpha = 0.30f)
                                        },
                                    border =
                                        if (selected) {
                                            accent.copy(alpha = 0.30f)
                                        } else {
                                            palette.border
                                        },
                                    sheen = 0.7f,
                                ).padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
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
    episodeCount: Int,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
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
            episodeCount = episodeCount,
            seasons = seasons,
            selectedSeasonId = selectedSeasonId,
            pickerOpen = pickerOpen,
            onTogglePicker = onTogglePicker,
            onSelectSeason = onSelectSeason,
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val centeredOffset =
                with(density) {
                    -((maxWidth - 172.dp) / 2f).coerceAtLeast(0.dp).roundToPx()
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
                contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
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
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    Column(
        Modifier
            .width(172.dp)
            .pressable(onClick = onPlay)
            .solidGlass(
                shape = GlassShapes.card,
                fill =
                    when {
                        selected -> stateColors.surface
                        palette.isDark -> palette.card
                        else -> Color.White.copy(alpha = 0.24f)
                    },
                border = Color.Transparent,
            ).then(
                if (selected) {
                    Modifier.border(1.75.dp, stateColors.border, GlassShapes.card)
                } else {
                    Modifier
                },
            ).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(86.dp)) {
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
                        append("已选中 · 再次点击播放")
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
