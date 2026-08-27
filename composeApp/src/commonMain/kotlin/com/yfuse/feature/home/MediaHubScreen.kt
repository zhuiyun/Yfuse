package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.data.TgtoEmbyCardStatus
import com.yfuse.core.data.TgtoMediaItem
import com.yfuse.core.data.toEmbyCardTarget
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget

private val HubPosterMinWidth = 104.dp

private val RankingProviders =
    listOf(
        "netflix" to "Netflix",
        "hbo" to "HBO",
        "apple" to "Apple TV+",
        "disney" to "Disney+",
        "crunchyroll" to "Crunchyroll",
        "prime" to "Prime",
        "amazon" to "Amazon",
        "hulu" to "Hulu",
    )

@Composable
fun MediaHubScreen(
    component: MediaHubComponent,
    onOpenClassic: () -> Unit,
) {
    val state by component.state.collectAsState()
    val palette = LocalPalette.current

    Box(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(HubPosterMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = Dimens.pageHorizontal,
                    top = Dimens.contentTop,
                    end = Dimens.pageHorizontal,
                    bottom = TabBarInset,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MediaHubHeader(
                    section = state.section,
                    configured = state.configured,
                    stale = state.stale,
                    onSection = component::selectSection,
                    onOpenClassic = onOpenClassic,
                    onSettings = component.onOpenSettings,
                )
            }

            if (state.configured) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    MediaHubFilters(state, component)
                }
            }

            state.notice?.let { notice ->
                item(span = { GridItemSpan(maxLineSpan) }) { HubCallout(notice) }
            }

            when {
                !state.configured ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PageHint(
                            text = "先连接 TgtoDrive，首页才能读取榜单、探索、追剧日历和 123 资源。",
                            actionLabel = "前往设置",
                            onAction = component.onOpenSettings,
                            icon = AppIcons.Cloud,
                        )
                    }
                state.loading ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = LocalAccentColors.current.accent)
                        }
                    }
                state.error != null ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ErrorState(state.error.orEmpty(), component::refresh)
                    }
                visibleItems(state).isEmpty() ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PageHint(text = "当前筛选没有结果", icon = AppIcons.Search)
                    }
                else -> {
                    itemsIndexed(
                        items = visibleItems(state),
                        key = { index, item -> "${item.entityKey}:${item.calendarDate}:${item.rank}:$index" },
                    ) { _, item ->
                        MediaHubPoster(
                            item = item,
                            embyStatus = item.toEmbyCardTarget()?.key?.let(state.embyStatuses::get),
                            onClick = { component.openItem(item) },
                        )
                    }
                }
            }

            if (state.configured &&
                !state.loading &&
                state.section != MediaHubSection.Calendar &&
                state.error == null
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationRow(
                        page = state.page,
                        canPrevious = state.page > 1,
                        canNext = state.hasNextPage,
                        onPrevious = component::previousPage,
                        onNext = component::nextPage,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaHubPoster(
    item: TgtoMediaItem,
    embyStatus: TgtoEmbyCardStatus?,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val label = embyStatus?.posterLabel(item.normalizedMediaType)
    val tone =
        when {
            embyStatus?.state == "not-found" || embyStatus?.state == "error" -> palette.error
            embyStatus?.libraryStatus == "missing" -> Semantic.Warning
            else -> Semantic.Success
        }
    Box(Modifier.fillMaxWidth()) {
        CaptionedPoster(
            url = item.posterUrl,
            fallbackUrl = item.backdropUrl,
            title = item.title,
            rating = item.score,
            year = item.cardMeta(),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
        )
        label?.let {
            Text(
                text = it,
                style = AppTypography.caption.strong,
                color = tone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .flatGlass(
                            GlassShapes.chip,
                            palette.card.copy(alpha = 0.90f),
                            tone.copy(alpha = 0.48f),
                        ).padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

private fun TgtoEmbyCardStatus.posterLabel(mediaType: String): String? =
    when (state) {
        "not-found" -> "未入库"
        "error" -> "查询失败"
        "found" ->
            when {
                mediaType != "tv" -> "已入库"
                libraryStatus == "missing" && availableCount > 0 -> "已入库${availableCount}集 · 缺${missingCount}集"
                availableCount > 0 -> "已入库${availableCount}集"
                displayLabel.isNotBlank() -> displayLabel
                else -> "已入库"
            }
        else -> null
    }

@Composable
private fun MediaHubHeader(
    section: MediaHubSection,
    configured: Boolean,
    stale: Boolean,
    onSection: (MediaHubSection) -> Unit,
    onOpenClassic: () -> Unit,
    onSettings: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("影视发现", style = AppTypography.display.strong, color = palette.text)
                Text(
                    when {
                        !configured -> "尚未连接 TgtoDrive"
                        stale -> "正在显示缓存数据"
                        else -> "榜单、探索、日历与 123 云盘"
                    },
                    style = AppTypography.caption.regular,
                    color = palette.sub2,
                )
            }
            Icon(
                AppIcons.Home,
                contentDescription = "切换到 Yfuse 首页",
                tint = palette.sub,
                modifier =
                    Modifier
                        .pressable(onClick = onOpenClassic)
                        .touchTarget()
                        .size(40.dp)
                        .liquidGlass(GlassShapes.chip, palette.card2, palette.border, palette.background)
                        .padding(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.Server,
                contentDescription = "影视发现设置",
                tint = palette.sub,
                modifier =
                    Modifier
                        .pressable(onClick = onSettings)
                        .touchTarget()
                        .size(40.dp)
                        .liquidGlass(GlassShapes.chip, palette.card2, palette.border, palette.background)
                        .padding(10.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().flatGlass(GlassShapes.chip, palette.card2, palette.border).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            MediaHubSection.entries.forEach { option ->
                HubChip(
                    label = option.label,
                    selected = option == section,
                    onClick = { onSection(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MediaHubFilters(
    state: MediaHubState,
    component: MediaHubComponent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state.section) {
            MediaHubSection.Rankings -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RankingProviders, key = { it.first }) { (key, label) ->
                        HubChip(label, state.rankingProvider == key, onClick = { component.selectRankingProvider(key) })
                    }
                }
                MediaTypeRow(state.mediaType, includeAll = true, component::selectMediaType)
            }
            MediaHubSection.Explore -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HubChip("TMDB", state.exploreSource == "tmdb", onClick = { component.selectExploreSource("tmdb") })
                    HubChip(
                        "豆瓣",
                        state.exploreSource == "douban",
                        onClick = { component.selectExploreSource("douban") },
                    )
                    HubChip(
                        "AniList",
                        state.exploreSource == "anilist",
                        onClick = { component.selectExploreSource("anilist") },
                    )
                    HubChip(
                        "Bangumi",
                        state.exploreSource == "bangumi",
                        onClick = { component.selectExploreSource("bangumi") },
                    )
                }
                ExploreSearchField(state.searchQuery, component::setSearchQuery, component::submitSearch)
                if (state.exploreSource == "tmdb" || state.exploreSource == "douban") {
                    MediaTypeRow(state.mediaType, includeAll = false, component::selectMediaType)
                }
            }
            MediaHubSection.Calendar -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "movie" to "电影", "tv" to "剧集").forEach { (key, label) ->
                        HubChip(label, state.calendarKind == key, onClick = { component.selectCalendarKind(key) })
                    }
                }
                val dates =
                    state.items
                        .filter { state.calendarKind == "all" || it.calendarKind == state.calendarKind }
                        .map { it.calendarDate }
                        .filter(String::isNotBlank)
                        .distinct()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(dates, key = { it }) { date ->
                        HubChip(
                            date.substringAfter('-'),
                            state.selectedDate == date,
                            onClick = { component.selectDate(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .flatGlass(
                AppShapes.control,
                palette.card2,
                palette.border,
            ).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = AppTypography.body.regular.copy(color = palette.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) Text("搜索电影或剧集", style = AppTypography.body.regular, color = palette.hint)
                    inner()
                }
            },
        )
        Icon(
            AppIcons.Search,
            contentDescription = "搜索",
            tint = LocalAccentColors.current.accent,
            modifier =
                Modifier
                    .pressable(onClick = onSearch)
                    .touchTarget()
                    .size(38.dp)
                    .padding(9.dp),
        )
    }
}

@Composable
private fun MediaTypeRow(
    selected: String,
    includeAll: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buildList {
            if (includeAll) add("all" to "全部")
            add("movie" to "电影")
            add("tv" to "剧集")
        }.forEach { (key, label) -> HubChip(label, selected == key, onClick = { onSelect(key) }) }
    }
}

@Composable
private fun HubChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Box(
        modifier
            .pressable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 38.dp)
            .liquidGlass(
                shape = GlassShapes.chip,
                fill = if (selected) accent.container.copy(alpha = 0.72f) else palette.card2,
                border = if (selected) accent.border else palette.border,
                over = palette.background,
            ).padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
            color = if (selected) accent.accent else palette.sub,
            maxLines = 1,
        )
    }
}

@Composable
private fun HubCallout(text: String) {
    val palette = LocalPalette.current
    Text(
        text,
        style = AppTypography.caption.regular,
        color = palette.sub,
        modifier = Modifier.fillMaxWidth().flatGlass(AppShapes.control, palette.card2, palette.border).padding(14.dp),
    )
}

@Composable
private fun PaginationRow(
    page: Int,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HubChip("上一页", canPrevious, onPrevious)
        Spacer(Modifier.width(14.dp))
        Text("第 $page 页", style = AppTypography.caption.medium, color = LocalPalette.current.sub2)
        Spacer(Modifier.width(14.dp))
        HubChip("下一页", canNext, onNext)
    }
}

private fun visibleItems(state: MediaHubState): List<TgtoMediaItem> =
    if (state.section != MediaHubSection.Calendar) {
        state.items
    } else {
        state.items.filter { item ->
            (state.calendarKind == "all" || item.calendarKind == state.calendarKind) &&
                (state.selectedDate.isBlank() || item.calendarDate == state.selectedDate)
        }
    }

private fun TgtoMediaItem.cardMeta(): String =
    when {
        rank != null -> "#$rank · ${score?.let { "★$it" } ?: providerLabel}"
        calendarEpisode != null -> {
            val episode = calendarEpisode
            "S${episode.seasonNumber ?: 1}E${episode.episodeNumber ?: 0}" +
                network.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        }
        releaseDate.isNotBlank() -> releaseDate
        score != null -> "★$score · $year"
        else -> year.ifBlank { mediaType }
    }
