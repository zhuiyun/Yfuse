package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.OrbProgressDefaults
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.motionAwareScrollToItem
import com.yfuse.core.designsystem.motionItem
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * 查看全部 — one season, every episode, laid out to be read rather than skimmed.
 *
 * The rail on the detail page is a shelf: four thumbnails, a title each, one line of
 * synopsis. That is the right shape for "what's next" and the wrong one for "which episode
 * was the one with the tunnel" — which needs the synopses long enough to recognise and
 * stacked so the eye runs down them.
 *
 * A layer over the detail page rather than a pushed route. The page it covers is the one
 * that owns this season, its artwork and its state; a route would mean the same
 * configuration threaded through three navigation stacks (库 / 首页 / 搜索 each own a
 * detail child) to show a list the detail store has already loaded. The detail page remains
 * composed underneath, so dismissing this layer returns to that exact page.
 *
 * With more than one season in [seasons] and an [onSelectSeason] to ask for another, the page
 * swipes sideways between seasons (Apple TV's 全部剧集): the season tabs at the top follow the
 * finger and settle with the page, and a season the store has not loaded yet shows its title
 * while its episodes arrive.
 *
 * Shown through [com.yfuse.core.designsystem.OverlayPage], which gives it a route's push and
 * pop and the predictive back.
 */
@Composable
internal fun SeasonEpisodesPage(
    seasonLabel: String,
    seriesName: String,
    episodes: List<Episode>,
    heroUrls: List<String?>,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
    accent: Color,
    currentEpisodeId: String?,
    onPlayEpisode: (Episode) -> Unit,
    onDismiss: () -> Unit,
    /** Every season of the series, id to name. */
    seasons: List<Pair<String, String>> = emptyList(),
    /** The season asked for; the tabs and the page follow it, whoever asked. */
    selectedSeasonId: String? = null,
    /** The season [episodes] belong to: the previous one until [selectedSeasonId] has loaded. */
    listedSeasonId: String? = selectedSeasonId,
    onSelectSeason: ((String) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val style =
        EpisodeListStyle(
            seriesName = seriesName,
            heroUrls = heroUrls,
            baseUrl = baseUrl,
            accessToken = accessToken,
            seriesPosterUrl = seriesPosterUrl,
            accent = accent,
            currentEpisodeId = currentEpisodeId,
            onPlayEpisode = onPlayEpisode,
        )
    val selectedIndex = seasons.indexOfFirst { it.first == selectedSeasonId }
    val select = onSelectSeason?.takeIf { seasons.size > 1 && selectedIndex >= 0 }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (select == null) {
            SeasonEpisodeList(label = seasonLabel, episodes = episodes, loading = false, style = style)
            SeasonTopBar(onDismiss = onDismiss)
        } else {
            SeasonPages(
                seasons = seasons,
                selectedIndex = selectedIndex,
                selectedSeasonId = selectedSeasonId,
                listedSeasonId = listedSeasonId,
                episodes = episodes,
                style = style,
                onSelectSeason = select,
                onDismiss = onDismiss,
            )
        }
    }
}

/** What every season's list draws alike. */
private class EpisodeListStyle(
    val seriesName: String,
    val heroUrls: List<String?>,
    val baseUrl: String,
    val accessToken: String,
    val seriesPosterUrl: String?,
    val accent: Color,
    val currentEpisodeId: String?,
    val onPlayEpisode: (Episode) -> Unit,
)

/**
 * One page per season. A settled swipe asks for its season, and the page fills in when the store
 * has it; a season chosen anywhere else — the detail page's season list — brings the pager along.
 */
@Composable
private fun SeasonPages(
    seasons: List<Pair<String, String>>,
    selectedIndex: Int,
    selectedSeasonId: String?,
    listedSeasonId: String?,
    episodes: List<Episode>,
    style: EpisodeListStyle,
    onSelectSeason: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = selectedIndex, pageCount = { seasons.size })
    val latestSeasons by rememberUpdatedState(seasons)
    val latestSelected by rememberUpdatedState(selectedSeasonId)
    val latestSelect by rememberUpdatedState(onSelectSeason)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val id = latestSeasons.getOrNull(page)?.first ?: return@collect
            if (id != latestSelected) {
                haptics.play(HapticSignal.Select)
                latestSelect(id)
            }
        }
    }
    LaunchedEffect(selectedIndex) {
        if (pagerState.settledPage != selectedIndex && !pagerState.isScrollInProgress) {
            if (still) {
                pagerState.scrollToPage(selectedIndex)
            } else {
                pagerState.animateScrollToPage(selectedIndex, animationSpec = Motion.tween(Motion.EMPHASIZED))
            }
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { page -> latestSeasons.getOrNull(page)?.first ?: page },
    ) { page ->
        val season = seasons.getOrNull(page)
        if (season != null) {
            val listed = season.first == listedSeasonId
            SeasonEpisodeList(
                label = season.second,
                episodes = if (listed) episodes else emptyList(),
                loading = !listed,
                style = style,
            )
        }
    }
    SeasonTopBar(onDismiss = onDismiss) {
        SeasonTabs(
            seasons = seasons,
            pagerState = pagerState,
            accent = style.accent,
            onTab = { index ->
                scope.launch {
                    if (still) {
                        pagerState.scrollToPage(index)
                    } else {
                        pagerState.animateScrollToPage(index, animationSpec = Motion.tween(Motion.EMPHASIZED))
                    }
                }
            },
        )
    }
}

/** A season's hero and its episodes, or its hero and a wait while they load. */
@Composable
private fun SeasonEpisodeList(
    label: String,
    episodes: List<Episode>,
    loading: Boolean,
    style: EpisodeListStyle,
) {
    val palette = LocalPalette.current
    val listState = rememberLazyListState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val focusedEpisodeIndex =
        remember(episodes, style.currentEpisodeId) {
            episodeFocusIndex(episodes, style.currentEpisodeId)
        }
    var initialSelectionConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(style.currentEpisodeId, focusedEpisodeIndex, reduceMotion) {
        if (focusedEpisodeIndex < 0) return@LaunchedEffect
        if (!initialSelectionConsumed) {
            // Keep the season hero in the first frame. A later episode choice may move the
            // list, but merely opening this page must not fly past the title artwork.
            initialSelectionConsumed = true
        } else {
            listState.motionAwareScrollToItem(
                index = focusedEpisodeIndex + 1,
                reduceMotion = reduceMotion,
            )
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = Dimens.contentBottom),
    ) {
        motionItem(key = "season-hero") {
            Box(Modifier.fillMaxWidth().height(268.dp)) {
                FallbackImage(
                    urls = style.heroUrls,
                    contentDescription = style.seriesName,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(heroScrim(palette.background)))
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = Dimens.pageHorizontal)
                        .padding(bottom = 18.dp),
                ) {
                    Text(
                        label,
                        style = AppTypography.display.strong,
                        color = palette.text,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        style.seriesName,
                        style = AppTypography.body.medium,
                        color = palette.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (loading) "正在加载剧集…" else "${episodes.size} 剧集",
                        style = AppTypography.caption.strong,
                        color = palette.sub2,
                        maxLines = 1,
                    )
                }
            }
        }

        if (loading) {
            motionItem(key = "season-loading") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    OrbProgress(size = OrbProgressDefaults.Page, contentDescription = "正在加载剧集")
                }
            }
        } else {
            motionItemsIndexed(
                episodes,
                key = { index, episode -> "all-ep-${episode.id}-$index" },
            ) { _, episode ->
                EpisodeRow(
                    episode = episode,
                    baseUrl = style.baseUrl,
                    accessToken = style.accessToken,
                    seriesPosterUrl = style.seriesPosterUrl,
                    accent = style.accent,
                    current = episode.id == style.currentEpisodeId,
                    onPlay = { style.onPlayEpisode(episode) },
                    modifier =
                        Modifier.padding(
                            horizontal = Dimens.pageHorizontal,
                            vertical = 7.dp,
                        ),
                )
            }
        }
    }
}

/**
 * The close chip — the detail page's own, in the same corner, so backing out of this reads as
 * one gesture rather than two different ones a screen apart — and, beside it, [tabs].
 */
@Composable
private fun SeasonTopBar(
    onDismiss: () -> Unit,
    tabs: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Dimens.pageHorizontal, end = Dimens.pageHorizontal, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .pressable(onClickLabel = "关闭剧集列表", onClick = onDismiss)
                .touchTarget()
                .size(34.dp)
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = palette.text,
                modifier = Modifier.size(15.dp),
            )
        }
        tabs()
    }
}

/**
 * The seasons as tabs. The highlight is drawn from the pager's own position, so it slides with
 * the finger between two tabs and lands with the page; the text only changes when the page does.
 */
@Composable
private fun RowScope.SeasonTabs(
    seasons: List<Pair<String, String>>,
    pagerState: PagerState,
    accent: Color,
    onTab: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val scroll = rememberScrollState()
    val bounds = remember(seasons.size) { SeasonTabBounds(seasons.size) }
    val current by remember(pagerState) { derivedStateOf { pagerState.currentPage } }
    // The tab the pager is heading for stays in view.
    LaunchedEffect(pagerState, bounds, still) {
        snapshotFlow { pagerState.targetPage }.collect { page ->
            val target =
                seasonTabScrollTarget(
                    left = bounds.lefts.getOrElse(page) { 0f },
                    width = bounds.widths.getOrElse(page) { 0f },
                    viewport = scroll.viewportSize.toFloat(),
                    maxScroll = scroll.maxValue,
                )
            if (still) scroll.scrollTo(target) else scroll.animateScrollTo(target)
        }
    }
    Row(
        Modifier
            .weight(1f, fill = false)
            .glass(AppShapes.pill, palette.glassStrong, palette.tabbarBorder)
            .horizontalScroll(scroll)
            .padding(3.dp)
            .drawBehind {
                val (left, width) =
                    seasonTabIndicator(
                        position = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                        lefts = bounds.lefts,
                        widths = bounds.widths,
                    )
                if (width > 0f) {
                    drawRoundRect(
                        color = accent.copy(alpha = SEASON_TAB_HIGHLIGHT_ALPHA),
                        topLeft = Offset(left, 0f),
                        size = Size(width, size.height),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        seasons.forEachIndexed { index, season ->
            val selected = index == current
            Text(
                season.second,
                style = AppTypography.body.strong,
                color = if (selected) palette.text else palette.sub,
                maxLines = 1,
                modifier =
                    Modifier
                        .onPlaced {
                            bounds.lefts[index] = it.positionInParent().x
                            bounds.widths[index] = it.size.width.toFloat()
                        }.semantics { this.selected = selected }
                        .pressable(onClickLabel = "查看${season.second}") { onTab(index) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** How strongly the tab highlight shows the page's accent. */
private const val SEASON_TAB_HIGHLIGHT_ALPHA = 0.26f

/** Where each season tab sits in its row, as laid out; read while drawing the highlight. */
private class SeasonTabBounds(
    count: Int,
) {
    val lefts = FloatArray(count)
    val widths = FloatArray(count)
}

/**
 * The tab highlight at pager [position] (page plus offset): between two tabs it is part of the
 * way from one to the other, in both place and width. Returns left and width; zero width before
 * the tabs are laid out.
 */
internal fun seasonTabIndicator(
    position: Float,
    lefts: FloatArray,
    widths: FloatArray,
): Pair<Float, Float> {
    val count = min(lefts.size, widths.size)
    if (count == 0 || !position.isFinite()) return 0f to 0f
    val clamped = position.coerceIn(0f, (count - 1).toFloat())
    val from = floor(clamped).toInt()
    val to = min(from + 1, count - 1)
    val fraction = clamped - from
    val left = lefts[from] + (lefts[to] - lefts[from]) * fraction
    val width = widths[from] + (widths[to] - widths[from]) * fraction
    return left to width
}

/** The scroll that centres a tab [left]..[left]+[width] in a [viewport]-wide row, within 0..[maxScroll]. */
internal fun seasonTabScrollTarget(
    left: Float,
    width: Float,
    viewport: Float,
    maxScroll: Int,
): Int = (left + width / 2f - viewport / 2f).roundToInt().coerceIn(0, maxScroll.coerceAtLeast(0))

@Composable
private fun EpisodeRow(
    episode: Episode,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
    accent: Color,
    current: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val stateColors = detailStateColors(accent, palette.background, palette.isDark)
    val selectedHighlight = Color.White
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onPlay)
            .clip(AppShapes.card)
            .background(
                if (current) {
                    selectedHighlight.copy(alpha = if (palette.isDark) 0.24f else 0.30f)
                } else {
                    Color.Transparent
                },
            ).then(
                if (current) {
                    Modifier.border(3.dp, selectedHighlight, AppShapes.card)
                } else {
                    Modifier
                },
            ).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(148.dp).height(84.dp)) {
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
                shape = AppShapes.thumb,
                progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                modifier = Modifier.fillMaxSize(),
            )
            // Watched and part-watched are different states and only one of them has a
            // number: a check for "done", the time left for "you stopped here".
            if (episode.played) {
                EpisodeWatchedBadge(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            } else {
                episode.remainingLabel()?.let { remaining ->
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(AppShapes.chip)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(8.dp),
                        )
                        Text(
                            remaining,
                            style = AppTypography.caption.strong,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (current) {
                EpisodeSelectionBadge(
                    accent = accent,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "E$it." }, episode.name)
                    .joinToString(" "),
                style = AppTypography.body.strong,
                color = if (current) stateColors.foreground else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts =
                listOfNotNull(
                    episode.runtimeMinutes?.let { "$it 分钟" },
                    episode.premiereDate,
                )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    facts.joinToString(" · "),
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                    maxLines = 1,
                )
            }
            if (!episode.overview.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    episode.overview,
                    style = AppTypography.caption.reading,
                    color = palette.sub,
                    // Three lines: enough to recognise an episode by, short enough that
                    // ten of them still scan as a list.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The selected episode wins; progress is a safe fallback while selection is still loading. */
internal fun episodeFocusIndex(
    episodes: List<Episode>,
    currentEpisodeId: String?,
): Int {
    val selectedIndex =
        currentEpisodeId
            ?.let { id -> episodes.indexOfFirst { it.id == id } }
            ?: -1
    if (selectedIndex >= 0) return selectedIndex
    return episodes.indexOfFirst { episode ->
        !episode.played &&
            (
                (episode.resumePositionTicks ?: 0L) > 0L ||
                    (episode.playedPercentage ?: 0.0) > 0.0
            )
    }
}

@Composable
internal fun EpisodeWatchedBadge(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (palette.isDark) Color.Black.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.68f))
            .border(Dimens.hairline, palette.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Check,
            contentDescription = "已看完",
            tint = palette.sub,
            modifier = Modifier.size(9.dp),
        )
    }
}

/** Current selection uses a filled theme state plus a check; never a border-only cue. */
@Composable
internal fun EpisodeSelectionBadge(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Check,
            contentDescription = "当前剧集",
            tint = Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** `20:01` — how much of this episode is left, for something already started. */
private fun Episode.remainingLabel(): String? {
    val runtimeMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L } ?: return null
    val watchedMs = resumePositionTicks?.takeIf { it > 0 }?.let { it / 10_000L } ?: return null
    val leftMs = (runtimeMs - watchedMs).takeIf { it > 0 } ?: return null
    val totalSeconds = leftMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
