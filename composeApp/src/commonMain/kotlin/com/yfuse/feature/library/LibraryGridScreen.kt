package com.yfuse.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.systemNavigationContentInset
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ErrorState
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayButtonRow
import com.yfuse.core.designsystem.OverlayButtonTone
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.PageHint
import com.yfuse.core.designsystem.SkeletonPosterTile
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionAwareItem
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.network.EmbyImages

/**
 * 「查看更多」 grid.
 *
 * Columns are [GridCells.Adaptive] rather than a fixed three: 96dp still resolves to three
 * on a 360dp phone, and a tablet or an unfolded device fills the extra width with more
 * posters instead of stretching three of them across it.
 */
private val PosterMinWidth = 96.dp

/**
 * How many tiles from the end the grid asks for the next page — roughly six rows at the
 * three-column phone width, and less lead time on a wider screen, which is the right way
 * round: a wider grid shows more per screen and reaches the end more slowly.
 */
private const val PREFETCH_ITEMS = 18

private val sortLabels =
    mapOf(
        LibrarySort.RecentlyAdded to "最近添加",
        LibrarySort.Name to "名称",
        LibrarySort.Year to "年份",
        LibrarySort.Rating to "评分",
    )

@Composable
fun LibraryGridScreen(component: LibraryGridComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val baseUrl = component.serverBaseUrl
    val accessToken = component.serverAccessToken
    val palette = LocalPalette.current
    StatusBarIconStyle(darkIcons = !palette.isDark)
    var sortOpen by remember { mutableStateOf(false) }
    val gridState = component.gridState
    val bottomContentInset = systemNavigationContentInset()

    // Paging is driven by what is on screen rather than by the last composed tile: a tile
    // composes once, so binding the request to it would never fire again after a failure.
    val shouldLoadMore by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible =
                info.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - 1 - PREFETCH_ITEMS
        }
    }
    // Re-evaluate at each raw server offset, including pages containing only duplicates.
    // Those pages advance the cursor without changing the number of visible cards.
    LaunchedEffect(gridState, state.nextStartIndex, state.loadMoreError) {
        // A failed page waits for the footer's 重试 instead of retrying on every scroll.
        if (state.loadMoreError != null) return@LaunchedEffect
        snapshotFlow { shouldLoadMore }.collect {
            if (it) component.store.accept(GridIntent.LoadMore)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.pageHorizontal)
                    .padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .pressable(onClickLabel = "返回上一页", onClick = component.onBack)
                        .touchTarget()
                        .size(34.dp)
                        .glass(GlassShapes.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = palette.text,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    component.title,
                    style = AppTypography.display.strong,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The server's total, not the loaded count: paging means those differ, and
                // a number that climbed as the user scrolled was reporting the wrong thing.
                Text(
                    "${state.totalCount.coerceAtLeast(
                        state.loadedCount,
                    )} ${if (state.directoryKind != null) "个" else "部"}",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                )
                if (state.sortable) {
                    Row(
                        Modifier
                            .pressable(onClickLabel = "打开排序选项") { sortOpen = true }
                            .touchTarget()
                            .height(34.dp)
                            .glass(
                                AppShapes.pill,
                                palette.glassStrong,
                                palette.tabbarBorder,
                            ).padding(horizontal = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Menu,
                            null,
                            tint = palette.sub,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            sortLabels[state.sort].orEmpty(),
                            style = AppTypography.body.strong,
                            color = palette.text,
                        )
                    }
                }
            }

            if (state.containerKind == MediaContainerKind.Playlist) {
                Text(
                    text = "按服务器中的手工顺序显示",
                    style = AppTypography.caption.medium,
                    color = palette.sub2,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.pageHorizontal)
                            .padding(bottom = 10.dp),
                )
            }

            if (state.genres.isNotEmpty()) {
                GenreFilterRow(
                    genres = state.genres,
                    selected = state.genre,
                    onSelect = { component.store.accept(GridIntent.SetGenre(it)) },
                )
            } else if (state.genreLoadError != null) {
                GenreLoadErrorRow(
                    message = state.genreLoadError!!,
                    onRetry = { component.store.accept(GridIntent.RetryGenres) },
                )
            }

            if (state.resolutionFilterable) {
                ResolutionFilterRow(
                    selected = state.resolution,
                    onSelect = { component.store.accept(GridIntent.SetResolution(it)) },
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.loadedCount == 0 ->
                        SkeletonGrid(bottomContentInset = bottomContentInset)

                    state.error != null && state.loadedCount == 0 ->
                        ErrorState(
                            message = state.error!!,
                            onRetry = { component.store.accept(GridIntent.Retry) },
                            modifier = Modifier.align(Alignment.Center),
                        )

                    state.loadedCount == 0 ->
                        EmptyGridHint(
                            title = component.title,
                            filtered =
                                state.genre != null ||
                                    state.resolution != LibraryResolution.All,
                            onClearFilters = { component.store.accept(GridIntent.ClearFilters) },
                            onBack = component.onBack,
                            modifier = Modifier.align(Alignment.Center),
                        )

                    else ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(PosterMinWidth),
                            state = gridState,
                            contentPadding =
                                PaddingValues(
                                    start = Dimens.pageHorizontal,
                                    end = Dimens.pageHorizontal,
                                    bottom = bottomContentInset,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (state.directoryKind != null) {
                                items(
                                    items = state.containers,
                                    key = { "${it.serverId}-${it.kind}-${it.id}" },
                                ) { container ->
                                    CaptionedPoster(
                                        url =
                                            EmbyImages.primary(
                                                baseUrl = baseUrl,
                                                itemId = container.id,
                                                tag = container.posterTag,
                                                maxHeight = 450,
                                                accessToken = accessToken,
                                            ),
                                        title = container.title,
                                        year = container.itemCount?.let { "$it 项" },
                                        progress = null,
                                        onClick = { component.onOpenContainer(container) },
                                        modifier = motionAwareItem(),
                                    )
                                }
                            } else {
                                items(state.items, key = { it.id }) { item ->
                                    Box(
                                        // Appended pages fade in where they land rather than
                                        // appearing mid-scroll, and a sort change cross-dissolves
                                        // instead of swapping the grid between two frames.
                                        modifier = motionAwareItem(),
                                    ) {
                                        PosterCard(
                                            baseUrl = baseUrl,
                                            accessToken = accessToken,
                                            serverId = component.serverId,
                                            item = item,
                                            showProgress = false,
                                            onClick = { component.onOpenItem(item.id) },
                                        )
                                        if (state.containerKind != null) {
                                            Box(
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(3.dp)
                                                    .pressable(
                                                        onClickLabel =
                                                            "从${if (
                                                                state.containerKind ==
                                                                MediaContainerKind.Playlist
                                                            ) {
                                                                "播放列表"
                                                            } else {
                                                                "合集"
                                                            }}移除${item.title}",
                                                        onClick = {
                                                            component.store.accept(
                                                                GridIntent.RequestRemove(
                                                                    item.id,
                                                                ),
                                                            )
                                                        },
                                                    ).touchTarget(),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(30.dp)
                                                        .glass(
                                                            shape = GlassShapes.chip,
                                                            fill = palette.background.copy(alpha = 0.82f),
                                                            border = palette.border,
                                                        ),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        AppIcons.Close,
                                                        contentDescription = null,
                                                        tint = palette.text,
                                                        modifier = Modifier.size(12.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.loadingMore || state.loadMoreError != null) {
                                item(
                                    key = "grid-footer",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    GridFooter(
                                        error = state.loadMoreError,
                                        onRetry = { component.store.accept(GridIntent.LoadMore) },
                                    )
                                }
                            }
                        }
                }
            }
        }

        // 排序 was a Material [DropdownMenu] hung off the chip — the last anchored menu in the
        // app, and the one shape the overlay system exists to replace. Centred like every
        // other overlay outside the player now; see [com.yfuse.core.designsystem.GlassDialog].
        if (sortOpen) {
            GlassDialog(onDismiss = { sortOpen = false }) {
                OverlayHeader(title = "排序", onClose = { sortOpen = false })
                Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
                    LibrarySort.entries.forEach { option ->
                        OverlayOptionRow(
                            label = sortLabels[option].orEmpty(),
                            selected = state.sort == option,
                            onClick = {
                                component.store.accept(GridIntent.SetSort(option))
                                sortOpen = false
                            },
                        )
                    }
                }
            }
        }

        state.pendingRemoval?.let { item ->
            val containerLabel =
                if (state.containerKind == MediaContainerKind.Playlist) {
                    "播放列表"
                } else {
                    "合集"
                }
            GlassDialog(onDismiss = { component.store.accept(GridIntent.CancelRemove) }) {
                OverlayHeader(
                    title = "从$containerLabel 移除？",
                    subtitle = item.title,
                    onClose = { component.store.accept(GridIntent.CancelRemove) },
                )
                Text(
                    text = "只会移除此容器中的条目，不会删除服务器上的媒体文件。",
                    style = AppTypography.body.regular,
                    color = palette.sub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OverlayButtonRow(
                    dismissLabel = "取消",
                    confirmLabel = "确认移除",
                    onDismiss = { component.store.accept(GridIntent.CancelRemove) },
                    onConfirm = { component.store.accept(GridIntent.ConfirmRemove) },
                    confirmTone = OverlayButtonTone.Destructive,
                )
            }
        }

        ActionToast(
            message = state.actionMessage,
            onDismiss = { component.store.accept(GridIntent.DismissMessage) },
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/** Facet failure must stay actionable even when the poster request itself succeeded. */
@Composable
private fun GenreLoadErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = AppTypography.body.regular,
            color = palette.sub,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "重试分类",
            style = AppTypography.body.strong,
            color = accent.accent,
            modifier =
                Modifier
                    .pressable(onClickLabel = "重新加载分类", onClick = onRetry)
                    .touchTarget()
                    .glass(
                        shape = GlassShapes.chip,
                        fill = accent.container,
                        border = accent.border.copy(alpha = 0.28f),
                    ).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** 全部 plus one chip per genre the server reports for this library. */
@Composable
private fun GenreFilterRow(
    genres: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "genre-all") {
            GenreChip("全部", selected == null) { onSelect(null) }
        }
        items(genres, key = { it }) { genre ->
            // The facet arrives after the first page, so the row grows under the header.
            GenreChip(
                label = genre,
                selected = selected == genre,
                modifier = motionAwareItem(),
                onClick = { onSelect(genre) },
            )
        }
    }
}

/** Emby-backed media specification filters: resolution, Dolby Vision, and Blu-ray. */
@Composable
private fun ResolutionFilterRow(
    selected: LibraryResolution,
    onSelect: (LibraryResolution) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "resolution-label") {
            Text(
                text = "规格",
                style = AppTypography.caption.strong,
                color = LocalPalette.current.sub2,
            )
        }
        items(LibraryResolution.entries, key = { it.name }) { resolution ->
            GenreChip(
                label = resolution.label,
                selected = selected == resolution,
                modifier = motionAwareItem(),
                onClick = { onSelect(resolution) },
            )
        }
    }
}

@Composable
private fun GenreChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        label,
        style = if (selected) AppTypography.body.strong else AppTypography.body.medium,
        color = if (selected) accent.accent else palette.body,
        maxLines = 1,
        modifier =
            modifier
                .pressable(
                    role = Role.RadioButton,
                    onClickLabel = "选择分类",
                    onClick = onClick,
                ).semantics { this.selected = selected }
                .touchTarget()
                .glass(
                    shape = GlassShapes.chip,
                    fill = if (selected) accent.container else palette.card2,
                    border = if (selected) accent.border.copy(alpha = 0.34f) else palette.border,
                ).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

/** Placeholder tiles in the grid's own geometry, so nothing shifts when the page lands. */
@Composable
private fun SkeletonGrid(bottomContentInset: androidx.compose.ui.unit.Dp) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PosterMinWidth),
        contentPadding =
            PaddingValues(
                start = Dimens.pageHorizontal,
                end = Dimens.pageHorizontal,
                bottom = bottomContentInset,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(12) { SkeletonPosterTile(Modifier.fillMaxWidth()) }
    }
}

/** The row under the last page: either "loading more" or the failure and a retry. */
@Composable
private fun GridFooter(
    error: String?,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Box(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (error == null) {
            Text("正在加载更多…", style = AppTypography.caption.medium, color = palette.sub2)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, style = AppTypography.body.regular, color = palette.sub)
                Text(
                    "重试",
                    style = AppTypography.body.strong,
                    color = accent.accent,
                    modifier =
                        Modifier
                            .pressable(onClickLabel = "重新加载更多内容", onClick = onRetry)
                            .touchTarget()
                            .glass(
                                shape = GlassShapes.chip,
                                fill = accent.container,
                                border = accent.border.copy(alpha = 0.28f),
                            ).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * An empty grid always offers the way out of itself: clearing a genre that matched
 * nothing, or leaving a collection the user has not filled yet.
 */
@Composable
private fun EmptyGridHint(
    title: String,
    filtered: Boolean,
    onClearFilters: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filtered) {
        PageHint(
            "这个分类下没有内容",
            modifier = modifier,
            actionLabel = "查看全部",
            onAction = onClearFilters,
        )
        return
    }
    when (title) {
        "我的收藏" ->
            PageHint(
                "还没有收藏的影视\n在详情页点击收藏，就会出现在这里",
                modifier = modifier,
                actionLabel = "去媒体库看看",
                onAction = onBack,
            )
        "稍后观看" ->
            PageHint(
                "还没有稍后观看的内容\n在详情页加入片单，稍后在这里继续",
                modifier = modifier,
                actionLabel = "去媒体库看看",
                onAction = onBack,
            )
        else -> PageHint("暂无内容", modifier = modifier)
    }
}
