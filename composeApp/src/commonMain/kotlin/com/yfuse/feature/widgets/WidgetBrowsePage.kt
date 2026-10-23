package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSection

@Composable
internal fun WidgetBrowsePage(
    section: WidgetSection,
    repository: WidgetRepository,
    onBack: () -> Unit,
    onOpen: (WidgetMedia) -> Unit,
) {
    val feed = rememberWidgetFeed(section, repository)
    val state by feed.state.collectAsState()
    val grid = rememberLazyGridState()
    // Three columns on typical phones; larger text keeps wider, readable cards.
    val minimumCardWidth = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    var lastError by remember(feed) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error, state.busy) {
        if (state.error != null) {
            lastError = state.error
        } else if (!state.busy) {
            lastError = null
        }
    }
    LaunchedEffect(feed) { feed.ensureLoaded() }
    // Only a browse gesture asks for another page; returning to this scroll position does not.
    LaunchedEffect(feed, grid) {
        snapshotFlow {
            grid.isScrollInProgress &&
                (
                    grid.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: -1
                ) >= state.items.size - 4
        }.collect { nearEnd -> if (nearEnd) feed.more(Int.MAX_VALUE) }
    }
    FullWidgetDialog(onBack) {
        WidgetFrame(section.title, onBack) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minimumCardWidth),
                state = grid,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.items, key = { it.id }) { media -> WidgetGridCard(media, section.showMetadata, onOpen) }
                if (state.busy && lastError == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { WidgetProgress(Modifier.fillMaxWidth()) }
                }
                (state.error ?: lastError.takeIf { state.busy })?.let { error ->
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                    ) { WidgetLoadError(error, state.busy, state.items.isNotEmpty(), feed::retry) }
                }
                if (state.loaded && !state.busy && state.error == null) {
                    if (state.items.isEmpty()) {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                        ) { WidgetEmptyState(AppIcons.Movie, "暂无内容", "此栏目还没有返回内容。") }
                    } else if (state.hasMore && state.items.size < 500) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            WidgetHomeAction(AppIcons.ChevronDown, "继续浏览后续内容", { feed.more(Int.MAX_VALUE) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WidgetDetailLoading(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        com.yfuse.feature.detail
            .DetailSkeleton(280.dp)
        Box(Modifier.statusBarsPadding().padding(start = 12.dp)) {
            WidgetHomeAction(AppIcons.ChevronLeft, "返回", onBack)
        }
    }
}
