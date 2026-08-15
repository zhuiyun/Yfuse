package com.yfuse.feature.detail

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.account.canUseWatchTogether
import com.yfuse.core.data.rankServerSources
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.ArtworkAccent
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.BurstIcon
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DolbyBadge
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassLift
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.HeroInk
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.WindowWidthTier
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.backdropSource
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.heroPanelBrush
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.heroSurface
import com.yfuse.core.designsystem.isSharedMediaArtworkActive
import com.yfuse.core.designsystem.liftOverHero
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberBackdropState
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.sharedMediaArtwork
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.windowWidthTier
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.currentPlaybackNetworkClass
import com.yfuse.core.sync.WatchInvite
import com.yfuse.core.sync.watchKey
import com.yfuse.core.util.rememberShareHandler
import com.yfuse.feature.player.PlaybackSelection
import com.yfuse.feature.player.PlaybackSelectionState
import com.yfuse.feature.watch.WatchInviteShareSheet
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
                    Text("共 $episodeCount 集", style = AppTypography.caption.medium, color = palette.sub2)
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = "查看全部剧集",
                        tint = palette.hint,
                        modifier = Modifier.size(11.dp),
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
                                            Color.White.copy(alpha = 0.52f)
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
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    Column(
        Modifier
            .width(172.dp)
            .pressable(onClick = onPlay)
            .solidGlass(
                shape = GlassShapes.card,
                fill =
                    when {
                        selected -> accent.copy(alpha = 0.14f)
                        watching -> accent.copy(alpha = 0.08f)
                        palette.isDark -> palette.card
                        else -> Color.White.copy(alpha = 0.56f)
                    },
                border =
                    if (selected) {
                        accent.copy(alpha = 0.52f)
                    } else {
                        Color.White.copy(alpha = if (palette.isDark) 0.20f else 0.86f)
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
                color = if (selected || watching) accent else palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 主演 — `gap:14px`; 52px round avatars with `500 10px Manrope` names 6px below. */
