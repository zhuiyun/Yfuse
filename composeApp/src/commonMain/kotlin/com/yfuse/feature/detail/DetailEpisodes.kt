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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.BackdropState
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.backdropBlur
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages

/** 毛玻璃 for a floating list: diffuse enough that the episode strip under it reads as light and colour, not as rows. */
private val SeasonPickerBlurRadius = 28.dp
private val SeasonPickerMinWidth = 220.dp
private val SeasonPickerMaxWidth = 300.dp
private val SeasonPickerMaxHeight = 360.dp
private val SeasonPickerGap = 10.dp

/** Scale the season list grows from; the rest of the way is the settle spring. */
private const val SEASON_PICKER_SCALE_FROM = 0.88f

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
    onTogglePicker: () -> Unit,
    onPickerAnchor: (Rect) -> Unit,
    onManageProgress: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val rotation by
        animateFloatAsState(
            targetValue = if (pickerOpen) 180f else 0f,
            animationSpec = Motion.settle(reduceMotion),
            label = "seasonChevron",
        )
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
                        focusShape = GlassShapes.chip,
                        onClick = onTogglePicker,
                    ).semantics { this.selected = pickerOpen }
                    .heightIn(min = 44.dp)
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
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                )
            }
        } else {
            Text(seasonLabel, style = AppTypography.section.strong, color = palette.text)
        }
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
    PlatformBackHandler(enabled = open, onBack = onDismiss)
    // Composed while opening, open, or still animating shut.
    if (!open && progress.value <= 0f) return
    if (anchor == null) return

    val shape = GlassShapes.sheet
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
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Column(
                    Modifier
                        .graphicsLayer {
                            val entered = progress.value
                            alpha = entered
                            val scale = SEASON_PICKER_SCALE_FROM + (1f - SEASON_PICKER_SCALE_FROM) * entered
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

@Composable
private fun SeasonRow(
    name: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .pressable(
                role = Role.RadioButton,
                focusShape = GlassShapes.chip,
                onClick = onClick,
            ).then(
                if (selected) {
                    Modifier.background(accent.copy(alpha = 0.12f), GlassShapes.chip)
                } else {
                    Modifier
                },
            ).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onPickerAnchor: (Rect) -> Unit,
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
