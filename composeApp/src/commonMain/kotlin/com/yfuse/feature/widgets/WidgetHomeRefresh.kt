package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.RefreshIndicator
import com.yfuse.core.designsystem.RefreshThresholdHaptics
import com.yfuse.core.widget.WidgetFeed
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun rememberWidgetPageFeeds(
    sections: List<WidgetSection>,
    repository: WidgetRepository,
): Map<String, WidgetFeed> {
    val database by repository.database.collectAsState()
    val revision by repository.feedRevision.collectAsState()
    return remember(repository, sections, database.widgets, revision) {
        sections.associate { it.id to repository.feed(it) }
    }
}

/** Lazy rows only check while visible, resumed and outside the full-list/editor overlays. */
@Composable
internal fun WidgetFeedRefreshEffect(
    feed: WidgetFeed,
    active: Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible = LocalRouteVisible.current && active
    LaunchedEffect(feed, lifecycle, visible) {
        if (visible) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    feed.ensureLoaded()
                    feed.refreshIfStale()
                    delay(60_000L)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetHomeRefresh(
    feeds: List<WidgetFeed>,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val refreshing by remember(feeds) {
        if (feeds.isEmpty()) {
            flowOf(
                false,
            )
        } else {
            combine(feeds.map { it.state }) { states -> states.any { it.refreshing } }
        }
    }.collectAsState(false)
    val pullState = rememberPullToRefreshState()
    RefreshThresholdHaptics(pullState, refreshing)
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (active) feeds.forEach(WidgetFeed::refresh) },
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        indicator = { RefreshIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter)) },
    ) { content() }
}
