package com.yfuse.feature.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.core.store.Store
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackOverlay
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftAnchor
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.contentHandoff
import com.yfuse.core.designsystem.disclosureRotation
import com.yfuse.core.designsystem.heroDurationLabel
import com.yfuse.core.designsystem.liftAnchor
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberDisclosureProgress
import com.yfuse.core.designsystem.selectionColor
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.designsystem.waitingPulse
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineDownloadSelection
import com.yfuse.core.offline.OfflineMedia
import com.yfuse.core.offline.OfflineMediaManager
import com.yfuse.core.offline.buildOfflineDownloadRequests
import com.yfuse.feature.library.playedLiftAction
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** 毛玻璃 for a floating list: diffuse enough that the episode strip under it reads as light and colour, not as rows. */
private val SeasonPickerBlurRadius = 28.dp
private val SeasonPickerMinWidth = 220.dp
private val SeasonPickerMaxWidth = 300.dp
private val SeasonPickerMaxHeight = 360.dp
private val SeasonPickerGap = 10.dp

/** Scale the season list grows from; the rest of the way is the settle spring. */
private const val SEASON_PICKER_SCALE_FROM = 0.88f

/** The last season's episodes while the one just picked loads: still there, plainly not current. */
private const val STALE_EPISODES_ALPHA = 0.6f

/**
 * Season header. The season title itself is the picker's trigger — `第 1 季 ⌄` — and the list
 * opens under it, left-aligned, the way the row is read. A separate chip on the far side of the
 * header put the control away from what it changes and a second glass plate on a header that
 * already had one.
 *
 * The list is not drawn here: a lazy item cannot paint over the rows below it, and a popup
 * window cannot blur the page it floats above. The header only reports where the title sits
 * (in root coordinates) through [onPickerAnchor]; [SeasonPickerOverlay] draws the list at the
 * root of the page.
 */
@Composable
private fun EpisodeHeader(
    accent: Color,
    seasonLabel: String,
    availableEpisodeCount: Int,
    seasonCount: Int,
    pickerOpen: Boolean,
    seasonLoading: Boolean,
    staleAlpha: State<Float>,
    onTogglePicker: () -> Unit,
    onPickerAnchor: (Rect) -> Unit,
    onManageProgress: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val chevron = rememberDisclosureProgress(pickerOpen)
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (seasonCount > 1) {
            Row(
                Modifier
                    .onGloballyPositioned { onPickerAnchor(it.boundsInRoot()) }
                    .pressable(
                        onClickLabel = "切换季数",
                        focusShape = AppShapes.chip,
                        onClick = onTogglePicker,
                    ).semantics {
                        this.selected = pickerOpen
                        if (seasonLoading) stateDescription = "正在读取剧集"
                    }.touchTarget()
                    // The season just picked is named at once; this says its episodes are on the way.
                    .waitingPulse(active = seasonLoading, shape = AppShapes.chip, color = accent)
                    .padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    seasonLabel,
                    style = AppTypography.section.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp).disclosureRotation(chevron, degrees = 180f),
                )
            }
        } else {
            Text(seasonLabel, style = AppTypography.section.strong, color = palette.text)
        }
        // Both open the episodes on show, which are the last season's until the new ones land.
        Row(
            Modifier.graphicsLayer { alpha = staleAlpha.value },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // This count comes from Emby, not the official production total.
            Row(
                Modifier
                    .pressable(enabled = !seasonLoading, onClick = onSeeAll)
                    .touchTarget(),
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
                    .pressable(enabled = !seasonLoading, onClickLabel = "管理观看进度", onClick = onManageProgress)
                    .touchTarget()
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
        }
    }
}

/**
 * The floating season list, drawn at the root of the detail page.
 *
 * It is glass in the literal sense: [backdropBlur] samples the page underneath and diffuses it
 * before the translucent fill goes on, so the episode strip shows through as colour and light.
 * That is only possible from inside the page's own window — a `Popup` window has no access to
 * the pixels it floats over — so this is a sibling drawn after the list and the top bar, placed
 * at [anchor]: left-aligned under the season title, or above it when the title sits too close
 * to the bottom of the page.
 *
 * It scales in from the corner nearest the title with the settle spring and back out on a quick
 * tween, staying composed until the exit finishes; both cuts are instant under 减弱动态效果. A
 * tap anywhere else, the back key, or scrolling the page closes it.
 */
@Composable
internal fun SeasonPickerOverlay(
    open: Boolean,
    anchor: Rect?,
    backdrop: BackdropState,
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
    // The back key belongs to the open state, not to whether the list can be drawn. The
    // [BackOverlay] below carries the predictive transform and so lives inside the panel —
    // past both early returns — which left one reachable state with no way out of it: open,
    // but with no anchor yet, so nothing below this line is composed and the key did nothing.
    // This commit-only handler covers exactly that gap and stands down as soon as the
    // overlay itself is composed.
    PlatformBackHandler(enabled = open && anchor == null, onBack = onDismiss)
    // Composed while opening, open, or still animating shut.
    if (!open && progress.value <= 0f) return
    if (anchor == null) return

    val shape = AppShapes.sheet
    // With the page blurred beneath it the fill can be a fill; without the blur (older
    // platforms) the alpha has to keep the rows underneath from reading through the list.
    val blurred = backdrop.active
    val fill =
        when {
            palette.isDark && blurred -> Color(0xFF111A29).copy(alpha = 0.56f)
            palette.isDark -> Color(0xFF111A29).copy(alpha = 0.90f)
            blurred -> Color.White.copy(alpha = 0.50f)
            else -> Color.White.copy(alpha = 0.82f)
        }
    val border = if (palette.isDark) palette.border else Color.White.copy(alpha = 0.62f)
    val placement = remember { SeasonPickerPlacement() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (open) {
            // Everything outside the list closes it; the list consumes its own taps.
            Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        }
        // The return gesture now previews: a drag leans the list away and lets go of it, and
        // releasing it short of the commit point brings the same list back. Only the panel is
        // inside the overlay's transform — [origin] is read from the untransformed box above,
        // because a translated coordinate space would cancel itself out of the placement below.
        BackOverlay(onBack = onDismiss, enabled = open) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Column(
                        Modifier
                            .graphicsLayer {
                                val entered = progress.value
                                alpha = entered
                                val scale =
                                    SEASON_PICKER_SCALE_FROM + (1f - SEASON_PICKER_SCALE_FROM) * entered
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, if (placement.above) 1f else 0f)
                            }.shadow(Shadows.menu, shape)
                            .backdropBlur(backdrop, shape, radius = SeasonPickerBlurRadius)
                            .liquidGlass(shape = shape, fill = fill, border = border, sheen = 0.5f)
                            .heightIn(max = SeasonPickerMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        seasons.forEach { (id, name) ->
                            SeasonRow(
                                name = name,
                                selected = id == selectedSeasonId,
                                accent = accent,
                                onClick = { onSelectSeason(id) },
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val pageWidth = constraints.maxWidth
                val pageHeight = constraints.maxHeight
                val width =
                    (pageWidth * SEASON_PICKER_WIDTH_FRACTION)
                        .toInt()
                        .coerceIn(SeasonPickerMinWidth.roundToPx(), SeasonPickerMaxWidth.roundToPx())
                        .coerceAtMost(pageWidth)
                val panel =
                    measurables.first().measure(
                        constraints.copy(minWidth = width, maxWidth = width, minHeight = 0),
                    )
                val gap = SeasonPickerGap.roundToPx()
                val margin = Dimens.pageHorizontal.roundToPx()
                val anchorLeft = (anchor.left - origin.x).toInt()
                val anchorTop = (anchor.top - origin.y).toInt()
                val anchorBottom = (anchor.bottom - origin.y).toInt()
                // Never off the page, and never inside the page margin while there is room to respect it.
                val maxX = (pageWidth - width - margin).coerceAtLeast(0)
                val x = anchorLeft.coerceIn(margin.coerceAtMost(maxX), maxX)
                val below = anchorBottom + gap
                val fitsBelow = below + panel.height <= pageHeight
                placement.above = !fitsBelow
                val y = if (fitsBelow) below else (anchorTop - gap - panel.height).coerceAtLeast(0)
                layout(pageWidth, pageHeight) { panel.place(x, y) }
            }
        }
    }
}

/** Share of the page width the list takes; wide enough for "第 12 季 · 特别篇" without wrapping. */
private const val SEASON_PICKER_WIDTH_FRACTION = 0.58f

/**
 * Which side of the title the list opened on, written by the layout pass and read by the draw
 * pass of the same frame. A plain field rather than snapshot state: the layout must not
 * invalidate the draw that follows it.
 */
private class SeasonPickerPlacement {
    var above = false
}

/**
 * Where the season title sits, in root coordinates. A plain field rather than snapshot state:
 * the title reports itself on every layout pass while the page scrolls, and as state each report
 * recomposed the page's overlay layer. Only the composition that opens the list reads it.
 */
internal class SeasonPickerAnchor {
    var bounds: Rect? = null
}

@Composable
private fun SeasonRow(
    name: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    // The highlight fades between the two rows rather than jumping across the list. The
    // unselected fill is the same accent at zero alpha rather than Color.Transparent, so the
    // interpolation only moves the alpha and never drifts towards black on the way out.
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .pressable(
                role = Role.RadioButton,
                focusShape = AppShapes.chip,
                onClick = onClick,
            ).background(
                selectionColor(accent.copy(alpha = if (selected) 0.12f else 0f)),
                AppShapes.chip,
            ).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
            color = selectionColor(if (selected) accent else palette.text),
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
    seasonCount: Int,
    /** A newly picked season is on its way; [episodes] still belong to the last one. */
    seasonLoading: Boolean,
    /** The season [episodes] belong to. The rail hands over when it changes. */
    listedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onPickerAnchor: (Rect) -> Unit,
    onManageProgress: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onSeeAll: () -> Unit,
    /** The 浮起菜单's actions and each episode's download; null leaves the cards with their tap alone. */
    rowActions: EpisodeRowActions? = null,
) {
    val listState = rememberLazyListState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val routeVisible = LocalRouteVisible.current
    // Held back for the busy threshold, so a season answered from cache never dims at all.
    val staleDelay = if (seasonLoading) Motion.BUSY_SHOW_AFTER else 0
    val staleAlpha =
        animateFloatAsState(
            targetValue = if (seasonLoading) STALE_EPISODES_ALPHA else 1f,
            animationSpec =
                if (reduceMotion) {
                    snap(delayMillis = staleDelay)
                } else {
                    Motion.tween(Motion.STANDARD, delayMillis = staleDelay)
                },
            label = "staleEpisodes",
        )
    val focusedEpisodeIndex =
        remember(episodes, selectedEpisodeId) {
            episodeFocusIndex(episodes, selectedEpisodeId)
        }
    // The selector now lives outside this section. Scope initial placement to the displayed
    // episode group without referencing the removed selectedSeasonId parameter. Episode identity
    // also separates groups with identical season labels and stays stable across progress updates.
    val firstEpisodeId = episodes.firstOrNull()?.id
    var initiallyPositioned by remember(baseUrl, seasonLabel, firstEpisodeId) { mutableStateOf(false) }

    Column(Modifier.padding(top = Dimens.sectionGap)) {
        EpisodeHeader(
            accent = accent,
            onSeeAll = onSeeAll,
            seasonLabel = seasonLabel,
            availableEpisodeCount = availableEpisodeCount,
            seasonCount = seasonCount,
            pickerOpen = pickerOpen,
            seasonLoading = seasonLoading,
            staleAlpha = staleAlpha,
            onTogglePicker = onTogglePicker,
            onPickerAnchor = onPickerAnchor,
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
                baseUrl,
                seasonLabel,
                firstEpisodeId,
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
                // The last season's cards stay where they are, dimmed and inert, until the new
                // season's land and take their place.
                modifier =
                    Modifier
                        .contentHandoff(listedSeasonId.orEmpty())
                        .graphicsLayer { alpha = staleAlpha.value },
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
                motionItemsIndexed(
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
                        enabled = !seasonLoading,
                        onPlay = { onPlayEpisode(episode) },
                        download = rowActions?.downloads?.get(episode.id),
                        // A press held on a card lifts it into the 单集 menu (5.1). The rail scrolls
                        // sideways, so the card has no swipe of its own; 从这里开始多选 opens the
                        // 管理进度 sheet, whose rows swipe and sweep.
                        liftMenu =
                            rowActions?.takeIf { !seasonLoading }?.let { actions ->
                                {
                                    episodeLiftMenu(
                                        episode = episode,
                                        episodes = episodes,
                                        artworkUrl = episodeStillUrl(baseUrl, accessToken, episode),
                                        downloaded = actions.downloads.containsKey(episode.id),
                                        onOpen = { onPlayEpisode(episode) },
                                        onPlay = { actions.play(episode, onPlayEpisode) },
                                        onMark = actions::mark,
                                        onDownload = { actions.download(listOf(episode)) },
                                        onSelectFrom = { actions.startSelection(episode) },
                                    )
                                }
                            },
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
    enabled: Boolean,
    onPlay: () -> Unit,
    /** This episode's offline copy, finished or on its way. */
    download: OfflineMedia? = null,
    liftMenu: (() -> LiftMenu)? = null,
) {
    val palette = LocalPalette.current
    val stateColors = detailStateColors(accent, palette.background, palette.isDark)
    val selectedHighlight = Color.White
    val watching = (episode.playedPercentage ?: 0.0) > 0.0
    val still = remember { LiftAnchor() }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val selectedScale by
        animateFloatAsState(
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
            }.liftable(menu = liftMenu, anchor = still)
            .pressable(enabled = enabled, onClick = onPlay)
            .solidGlass(
                shape = AppShapes.card,
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
                    Modifier.border(3.dp, selectedHighlight, AppShapes.card)
                } else {
                    Modifier
                },
            ).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(108.dp).liftAnchor(still)) {
            Poster(
                url = episodeStillUrl(baseUrl, accessToken, episode),
                fallbackUrls = listOfNotNull(seriesPosterUrl),
                shape = AppShapes.thumb,
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
                    style = AppTypography.caption.reading,
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
                    episodeDownloadLabel(download?.status)?.let { label ->
                        if (isNotEmpty()) append(" · ")
                        append(label)
                    }
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

/** The episode's own still, as the rail and the 管理进度 sheet draw it. */
internal fun episodeStillUrl(
    baseUrl: String,
    accessToken: String,
    episode: Episode,
): String? = EmbyImages.primary(baseUrl, episode.id, episode.primaryTag, maxHeight = 240, accessToken = accessToken)

/**
 * What an episode can have done to it beyond the tap that picks it: 标记已看 from a swipe or the
 * 浮起菜单, 下载 and 删除下载, 从这里开始多选 and the sweep of 长按拖选. Built from the detail page's
 * store and the offline manager, so the rail and the 管理进度 sheet offer the same things.
 */
@Stable
internal class EpisodeRowActions(
    private val store: Store<DetailIntent, DetailState, DetailLabel>,
    private val offline: OfflineMediaManager,
    /** The listed season's episodes on this device or on their way, by item id. */
    val downloads: Map<String, OfflineMedia>,
) {
    fun mark(
        episodeIds: Set<String>,
        played: Boolean,
    ) {
        store.accept(DetailIntent.MarkEpisodes(episodeIds, played))
    }

    /** 播放: [select] is the rail's own tap, which picks the episode; it then plays once resolved. */
    fun play(
        episode: Episode,
        select: (Episode) -> Unit,
    ) {
        // Tapping the episode already picked plays it; any other is picked first, and 播放 waits.
        val picked = store.state.selectedEpisodeId == episode.id
        select(episode)
        if (!picked) store.accept(DetailIntent.Play)
    }

    /** 从这里开始多选: the sheet opens with [episode] already selected, to sweep on from. */
    fun startSelection(episode: Episode) {
        store.accept(DetailIntent.OpenProgressManager)
        store.accept(DetailIntent.ToggleProgressEpisode(episode.id))
    }

    /** Makes the sheet's selection [episodeIds] — what a 长按拖选 sweep hands over — a toggle per change. */
    fun select(episodeIds: Set<String>) {
        val current = store.state.progressSelection
        ((current - episodeIds) + (episodeIds - current)).forEach {
            store.accept(DetailIntent.ToggleProgressEpisode(it))
        }
    }

    /**
     * 下载 with nothing asked: each episode's first file in 原画, as the 下载 sheet would start it.
     * The sheet is still where versions, subtitles and a whole season are chosen.
     */
    fun download(episodes: List<Episode>) {
        val state = store.state
        val server = state.playServer ?: return
        val requests =
            episodes
                .filter { it.id !in downloads }
                .flatMap { episode ->
                    buildOfflineDownloadRequests(
                        serverId = server.id,
                        currentItemId = episode.id,
                        currentTitle = episode.name,
                        currentRuntimeMinutes = episode.runtimeMinutes,
                        currentVersions = episode.versions,
                        seasonEpisodes = state.episodes,
                        selection = OfflineDownloadSelection(),
                        currentSeriesId = state.playTarget?.seriesId,
                        currentSeasonId = episode.seasonId,
                    )
                }
        if (requests.isNotEmpty()) offline.enqueueAll(requests)
    }

    fun removeDownload(download: OfflineMedia) {
        offline.remove(download.id)
    }
}

/** [EpisodeRowActions] for the detail page, with the downloads of [serverId], the server playing the season. */
@Composable
internal fun rememberEpisodeRowActions(
    component: DetailComponent,
    serverId: String?,
): EpisodeRowActions {
    val offline = component.dependencies.offlineMediaManager
    val items by offline.items.collectAsState()
    return remember(component, items, serverId) {
        EpisodeRowActions(
            store = component.store,
            offline = offline,
            downloads = items.filter { it.serverId == serverId }.associateBy { it.itemId },
        )
    }
}

/**
 * The 浮起菜单 for one episode (5.1 单集): 播放 │ 标记为已看 or 未看, 标记此前全部已看 │ 下载,
 * 从这里开始多选. A row with nothing to do is left out — nothing unwatched before the episode, a
 * copy already downloaded.
 */
internal fun episodeLiftMenu(
    episode: Episode,
    episodes: List<Episode>,
    artworkUrl: String?,
    downloaded: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMark: (Set<String>, Boolean) -> Unit,
    onDownload: () -> Unit,
    onSelectFrom: () -> Unit,
): LiftMenu {
    val earlier = unwatchedBefore(episodes, episode.id)
    return LiftMenu(
        title = listOfNotNull(episode.indexNumber?.let { "第${it}集" }, episode.name).joinToString(" · "),
        meta = episodeLiftMeta(episode),
        artworkUrls = listOfNotNull(artworkUrl),
        backdropUrls = listOfNotNull(artworkUrl),
        progress = episodeLiftProgress(episode),
        progressLabel = if (episode.played) "已看完" else null,
        onOpen = onOpen,
        sections =
            listOf(
                listOf(
                    ItemAction(
                        label = "播放",
                        icon = AppIcons.Play,
                        leavesPage = true,
                        id = "episode.play",
                        onSelect = onPlay,
                    ),
                ),
                listOfNotNull(
                    playedLiftAction(episode.played) { played -> onMark(setOf(episode.id), played) },
                    earlier.takeIf { it.isNotEmpty() }?.let { ids ->
                        ItemAction(
                            label = "标记此前全部已看",
                            icon = AppIcons.EpisodeList,
                            detail = "${ids.size} 集",
                            id = "episode.markEarlier",
                        ) { onMark(ids, true) }
                    },
                ),
                listOfNotNull(
                    if (downloaded) {
                        null
                    } else {
                        ItemAction(
                            label = "下载",
                            icon = AppIcons.Download,
                            id = "episode.download",
                            onSelect = onDownload,
                        )
                    },
                    ItemAction(
                        label = "从这里开始多选",
                        icon = AppIcons.Edit,
                        leavesPage = true,
                        id = "episode.selectFrom",
                        onSelect = onSelectFrom,
                    ),
                ),
            ),
    )
}

/** "45分钟 · 2026-07-30": what the card knows besides the title. */
private fun episodeLiftMeta(episode: Episode): String? =
    listOfNotNull(heroDurationLabel(episode.runtimeMinutes), episode.premiereDate)
        .joinToString(" · ")
        .ifBlank { null }

/** Watched is a full bar; part-watched its share; unstarted draws none. */
private fun episodeLiftProgress(episode: Episode): Float? {
    if (episode.played) return 1f
    val percent = episode.playedPercentage?.takeIf { it > 0.0 } ?: return null
    return (percent / 100.0).toFloat().coerceIn(0f, 1f)
}

/** 标记此前全部已看: the unwatched episodes listed before [episodeId]; none when it is first or not listed. */
internal fun unwatchedBefore(
    episodes: List<Episode>,
    episodeId: String,
): Set<String> {
    val index = episodes.indexOfFirst { it.id == episodeId }
    if (index <= 0) return emptySet()
    return episodes.subList(0, index).filter { !it.played }.mapTo(linkedSetOf()) { it.id }
}

/** How far an episode's offline copy has got, in a word or two; null when there is none. */
internal fun episodeDownloadLabel(status: DownloadStatus?): String? =
    when (status) {
        null -> null
        DownloadStatus.Completed -> "已下载"
        DownloadStatus.Failed -> "下载失败"
        DownloadStatus.Paused -> "下载已暂停"
        DownloadStatus.Queued, DownloadStatus.WaitingForWifi, DownloadStatus.Downloading -> "下载中"
    }

/** 主演 — `gap:14px`; 52px round avatars with `500 10px Manrope` names 6px below. */
