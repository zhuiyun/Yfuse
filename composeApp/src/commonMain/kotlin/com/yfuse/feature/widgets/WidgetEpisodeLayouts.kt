package com.yfuse.feature.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.glass
import com.yfuse.core.widget.WidgetEpisodeResult
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.widget.confirmedNextWidgetEpisode
import com.yfuse.core.widget.widgetEpisodeData
import com.yfuse.core.widget.widgetEpisodeItems
import kotlinx.coroutines.CancellationException
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** Fixed-height horizontal layouts; episode directories are requested only for composed cards. */
@Composable
internal fun WidgetEpisodeLayouts(
    style: WidgetStyle,
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    if (style == WidgetStyle.DecadeShelf) {
        WidgetDecadeShelf(items, metadata, compact, onOpen)
        return
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val baseHeight = (if (compact) 312.dp else 346.dp) + 128.dp * (fontScale - 1f)
    val height =
        if (style == WidgetStyle.EpisodeMatrix) {
            with(LocalDensity.current) {
                val caption =
                    AppTypography.caption.regular.lineHeight
                        .toDp()
                val header =
                    AppTypography.body.medium.lineHeight
                        .toDp() * 2 + caption + 3.dp
                val cell =
                    AppTypography.section.strong.lineHeight
                        .toDp() + (if (metadata) caption else 0.dp) + 3.dp + 14.dp
                maxOf(baseHeight, 28.dp + 10.dp + 48.dp + header + 20.dp + 48.dp + 7.dp + caption + cell * 2 + 8.dp)
            }
        } else {
            baseHeight
        }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = minOf(maxWidth, if (compact) 302.dp else 342.dp)
        LazyRow(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$index:${item.id}" }) { _, item ->
                WidgetEpisodeCard(style, item, metadata, Modifier.width(width).fillMaxHeight(), onOpen)
            }
        }
    }
}

@Composable
private fun WidgetEpisodeCard(
    style: WidgetStyle,
    original: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    val preview = LocalWidgetPreview.current
    val data = remember(preview) { if (preview) null else widgetEpisodeData() }
    val revision = data?.revision?.collectAsState()?.value ?: 0L
    val partition = data?.partitionKey
    var retry by remember(original) { mutableIntStateOf(0) }
    var consumedRetry by remember(original, partition) { mutableIntStateOf(0) }
    var loading by remember(original, partition) { mutableStateOf(false) }
    var result by remember(original, partition) { mutableStateOf(WidgetEpisodeResult(original)) }
    LaunchedEffect(original, revision, retry, preview, style) {
        val source = data ?: return@LaunchedEffect
        val refresh = retry > consumedRetry
        consumedRetry = retry
        loading = true
        try {
            result = source.load(original, includeNext = style == WidgetStyle.NextDirect, refresh = refresh)
        } catch (failure: CancellationException) {
            throw failure
        } finally {
            loading = false
        }
    }
    Column(modifier.episodeGlass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (style) {
            WidgetStyle.NextDirect -> WidgetNextDirect(result.item, metadata, Modifier.weight(1f), onOpen)
            WidgetStyle.SeasonShowcase -> WidgetSeasonShowcase(result.item, metadata, Modifier.weight(1f), onOpen)
            WidgetStyle.EpisodePreview -> WidgetEpisodePreview(result.item, metadata, Modifier.weight(1f), onOpen)
            WidgetStyle.EpisodeMatrix -> WidgetEpisodeMatrix(result.item, metadata, Modifier.weight(1f), onOpen)
            else -> Unit
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (loading) "正在读取分集资料…" else result.message.orEmpty(),
                Modifier.weight(1f),
                color = LocalPalette.current.sub2,
                style = AppTypography.caption.regular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.retryable && !loading) WidgetTextButton({ retry++ }) { Text("重试") }
        }
    }
}

@Composable
private fun WidgetNextDirect(
    item: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    val next = remember(item) { item.confirmedNextWidgetEpisode() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(AppShapes.thumb)
                .clickable(role = Role.Button) { onOpen(item) },
        ) {
            Artwork(next ?: item, Modifier.fillMaxSize())
            Text(
                if (next == null) {
                    "剧集档案"
                } else if ((next.progress ?: 0f) > 0f) {
                    "继续这一集"
                } else {
                    "下一集已就绪"
                },
                Modifier
                    .align(
                        Alignment.TopStart,
                    ).padding(10.dp)
                    .episodeGlass()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = LocalAccentColors.current.accent,
                style = AppTypography.caption.medium,
                maxLines = 1,
            )
        }
        EpisodeTitle(item.title, Modifier.fillMaxWidth().clickable { onOpen(item) })
        if (metadata && next != null) {
            Text(
                listOf(episodeCoordinate(next), next.title).filter(String::isNotBlank).joinToString(" · "),
                color = LocalPalette.current.sub,
                style = AppTypography.caption.regular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = if (next == null) "查看剧集" else "查看第 ${next.episode} 集"
            WidgetSecondaryButton(onClick = { onOpen(next ?: item) }, modifier = Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(AppIcons.ChevronRight, null, Modifier.size(16.dp))
            }
            if (next?.progress != null) EpisodeProgress(next, Modifier.padding(start = 12.dp).width(62.dp))
        }
    }
}

@Composable
private fun WidgetSeasonShowcase(
    item: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    val episodes = remember(item) { item.widgetEpisodeItems() }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var chosenSeason by rememberSaveable(item.id) { mutableStateOf("first") }
    val selected =
        if (chosenSeason == "unknown" && null in seasons) {
            null
        } else {
            chosenSeason.toIntOrNull()?.takeIf { it in seasons } ?: seasons.firstOrNull()
        }
    val visible = episodes.filter { it.season == selected }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(item) },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(item, Modifier.size(50.dp, 70.dp).clip(AppShapes.thumb), poster = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EpisodeTitle(item.title)
                if (metadata) {
                    Text(
                        if (seasons.isEmpty()) "分季资料尚未提供" else "${seasons.size} 组分季 · ${episodes.size} 集",
                        color = LocalPalette.current.sub,
                        style = AppTypography.caption.regular,
                        maxLines = 1,
                    )
                }
            }
            Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = LocalPalette.current.sub2)
        }
        SeasonSelector(seasons, selected) { chosenSeason = it?.toString() ?: "unknown" }
        if (visible.isEmpty()) {
            EpisodeEmpty(item, Modifier.weight(1f), onOpen)
        } else {
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(visible, key = { _, episode -> episode.id }) { _, episode ->
                    Column(
                        Modifier
                            .width(
                                116.dp,
                            ).fillMaxHeight()
                            .clickable(enabled = episode.available != false, role = Role.Button) {
                                onOpen(episode)
                            },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Artwork(episode, Modifier.fillMaxWidth().weight(1f).clip(AppShapes.thumb))
                        Text(
                            episodeCoordinate(episode),
                            color = LocalPalette.current.text,
                            style = AppTypography.caption.medium,
                            maxLines = 1,
                        )
                        if (metadata) EpisodeState(episode)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetEpisodePreview(
    item: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    val episodes = remember(item) { item.widgetEpisodeItems() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EpisodeHeader(item, "分集预览", onOpen)
        if (episodes.isEmpty()) {
            EpisodeEmpty(item, Modifier.weight(1f), onOpen)
        } else {
            LazyRow(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
                    Column(
                        Modifier
                            .width(
                                216.dp,
                            ).fillMaxHeight()
                            .clickable(enabled = episode.available != false, role = Role.Button) {
                                onOpen(episode)
                            },
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(AppShapes.thumb)) {
                            Artwork(episode, Modifier.fillMaxSize())
                            Text(
                                episodeCoordinate(episode),
                                Modifier
                                    .align(
                                        Alignment.BottomStart,
                                    ).padding(8.dp)
                                    .episodeGlass()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = LocalPalette.current.text,
                                style = AppTypography.caption.medium,
                                maxLines = 1,
                            )
                        }
                        EpisodeTitle(episode.title)
                        if (metadata) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                episode.durationMinutes?.let { duration ->
                                    Text(
                                        "$duration 分钟",
                                        color = LocalPalette.current.sub2,
                                        style = AppTypography.caption.regular,
                                        maxLines = 1,
                                    )
                                }
                                EpisodeState(episode)
                            }
                        }
                        if (episode.progress != null) EpisodeProgress(episode, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetEpisodeMatrix(
    item: WidgetMedia,
    metadata: Boolean,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    val episodes = remember(item) { item.widgetEpisodeItems() }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var chosenSeason by rememberSaveable(item.id) { mutableStateOf("first") }
    val selected =
        if (chosenSeason == "unknown" && null in seasons) {
            null
        } else {
            chosenSeason.toIntOrNull()?.takeIf { it in seasons } ?: seasons.firstOrNull()
        }
    val selectedEpisodes = remember(episodes, selected) { episodes.filter { it.season == selected } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EpisodeHeader(item, "选集", onOpen)
        SeasonSelector(seasons, selected) { chosenSeason = it?.toString() ?: "unknown" }
        if (selectedEpisodes.isEmpty()) {
            EpisodeEmpty(item, Modifier.weight(1f), onOpen)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = (maxWidth / (68.dp * fontScale)).toInt().coerceIn(2, 4)
                val pages = remember(selectedEpisodes, columns) { selectedEpisodes.chunked(columns * 2) }
                val pager = rememberPagerState(pageCount = { pages.size })
                var previousSeason by rememberSaveable(item.id) { mutableStateOf(selected) }
                var previousColumns by rememberSaveable(item.id) { mutableIntStateOf(columns) }
                LaunchedEffect(selected, columns) {
                    if (selected != previousSeason || columns != previousColumns) pager.scrollToPage(0)
                    previousSeason = selected
                    previousColumns = columns
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), pageSpacing = 10.dp) { page ->
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(2) { row ->
                                Row(
                                    Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    repeat(columns) { column ->
                                        val episode = pages[page].getOrNull(row * columns + column)
                                        if (episode == null) {
                                            Spacer(Modifier.weight(1f))
                                        } else {
                                            Column(
                                                Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .episodeGlass()
                                                    .clickable(
                                                        enabled = episode.available != false,
                                                        role = Role.Button,
                                                    ) { onOpen(episode) }
                                                    .padding(7.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    episode.episode?.toString().orEmpty(),
                                                    color =
                                                        if (episode.id ==
                                                            item.confirmedNextWidgetEpisode()?.id
                                                        ) {
                                                            LocalAccentColors.current.accent
                                                        } else {
                                                            LocalPalette.current.text
                                                        },
                                                    style = AppTypography.section.strong,
                                                    maxLines = 1,
                                                )
                                                if (metadata) EpisodeState(episode)
                                                if (episode.progress !=
                                                    null
                                                ) {
                                                    EpisodeProgress(episode, Modifier.fillMaxWidth())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "${pager.currentPage + 1} / ${pages.size} · 左右滑动选集",
                        color = LocalPalette.current.sub2,
                        style = AppTypography.caption.regular,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonSelector(
    seasons: List<Int?>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    if (seasons.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(seasons) { _, season ->
            WidgetFilterChip(selected == season, { onSelect(season) }, {
                Text(season?.let { "第 $it 季" } ?: "分集", maxLines = 1)
            })
        }
    }
}

@Composable
private fun EpisodeHeader(
    item: WidgetMedia,
    label: String,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) {
            onOpen(item)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = LocalAccentColors.current.accent, style = AppTypography.caption.medium, maxLines = 1)
            EpisodeTitle(item.title)
        }
        Icon(AppIcons.ChevronRight, null, Modifier.size(18.dp), tint = LocalPalette.current.sub2)
    }
}

@Composable
private fun EpisodeEmpty(
    item: WidgetMedia,
    modifier: Modifier,
    onOpen: (WidgetMedia) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(item) },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item, Modifier.width(74.dp).fillMaxHeight().clip(AppShapes.thumb), poster = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("查看影视详情", color = LocalPalette.current.text, style = AppTypography.body.medium)
            Text(
                "有分集资料后，将在这里展示。",
                color = LocalPalette.current.sub,
                style = AppTypography.caption.regular,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EpisodeTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        modifier,
        color = LocalPalette.current.text,
        style = AppTypography.body.medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EpisodeState(item: WidgetMedia) {
    val text =
        when {
            item.available == false -> "暂无资源"
            item.played == true -> "已看完"
            item.progress != null && item.progress > 0f -> "已看 ${(item.progress * 100).toInt()}%"
            item.played == false -> "未看"
            else -> ""
        }
    Text(
        text,
        color = LocalPalette.current.sub2,
        style = AppTypography.caption.regular,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EpisodeProgress(
    item: WidgetMedia,
    modifier: Modifier,
) {
    val progress by animateFloatAsState(item.progress ?: 0f, label = "episode-progress")
    WidgetProgress(progress = { progress }, modifier = modifier.height(3.dp))
}

private fun episodeCoordinate(item: WidgetMedia): String =
    listOfNotNull(item.season?.let { "S$it" }, item.episode?.let { "第 $it 集" }).joinToString(" · ")

@Composable
private fun Modifier.episodeGlass(): Modifier =
    glass(shape = AppShapes.card, fill = LocalPalette.current.card, border = LocalPalette.current.border)

@Composable
private fun WidgetDecadeShelf(
    items: List<WidgetMedia>,
    metadata: Boolean,
    compact: Boolean,
    onOpen: (WidgetMedia) -> Unit,
) {
    val byDecade =
        remember(items) {
            items
                .groupBy { item ->
                    item.year
                        .take(4)
                        .toIntOrNull()
                        ?.takeIf { it in 1800..2199 }
                        ?.let { it / 10 * 10 }
                }.toSortedMap(compareBy<Int?> { it ?: Int.MAX_VALUE })
        }
    var selection by rememberSaveable { mutableStateOf("all") }
    val selected = selection.toIntOrNull()?.takeIf { it in byDecade }
    val unknownOnly = selection == "unknown" && null in byDecade
    val groups =
        remember(byDecade, selected, unknownOnly) {
            byDecade
                .filterKeys { if (unknownOnly) it == null else selected == null || it == selected }
                .flatMap { (decade, titles) -> titles.chunked(3).map { decade to it } }
        }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                WidgetFilterChip(selected == null && !unknownOnly, {
                    selection = "all"
                }, { Text("全部年代") })
            }
            itemsIndexed(byDecade.keys.toList()) { _, decade ->
                WidgetFilterChip(
                    if (decade == null) unknownOnly else !unknownOnly && selected == decade,
                    {
                        selection = decade?.toString() ?: "unknown"
                    },
                    { Text(decade?.let { "${it}s" } ?: "未标注", maxLines = 1) },
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = minOf(maxWidth, if (compact) 304.dp else 348.dp)
            val height = (if (compact) 264.dp else 304.dp) + 88.dp * (fontScale - 1f)
            LazyRow(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(groups) { _, (decade, titles) ->
                    Column(
                        Modifier
                            .width(width)
                            .fillMaxHeight()
                            .episodeGlass()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .width(
                                        3.dp,
                                    ).height(
                                        28.dp,
                                    ).background(
                                        LocalAccentColors.current.accent.copy(alpha = .65f),
                                        RoundedCornerShape(2.dp),
                                    ),
                            )
                            Text(
                                decade?.let { "${it}s" } ?: "未标注年代",
                                Modifier.padding(start = 10.dp),
                                color = LocalPalette.current.text,
                                style = AppTypography.section.strong,
                                maxLines = 1,
                            )
                        }
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { index ->
                                val item = titles.getOrNull(index)
                                if (item == null) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    Column(
                                        Modifier
                                            .weight(
                                                1f,
                                            ).fillMaxHeight()
                                            .clickable(role = Role.Button) { onOpen(item) },
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Artwork(
                                            item,
                                            Modifier.fillMaxWidth().weight(1f).clip(AppShapes.thumb),
                                            poster = true,
                                        )
                                        EpisodeTitle(item.title)
                                        if (metadata) {
                                            Text(
                                                item.year.take(4),
                                                color = LocalPalette.current.sub2,
                                                style = AppTypography.caption.regular,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
