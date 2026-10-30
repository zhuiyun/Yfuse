package com.yfuse.feature.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.app.floatingNavigationContentInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.widget.WidgetFeed
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSection
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.widget.widgetDetailSeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun WidgetPageContent(
    page: WidgetPage,
    repository: WidgetRepository,
    onEdit: () -> Unit,
    onOpenTmdb: (TmdbItem, WidgetSourceContext?) -> Unit,
    active: Boolean = true,
) {
    var browsePath by rememberSaveable(page.id) { mutableStateOf(emptyList<String>()) }

    fun browse(section: WidgetSection) {
        browsePath =
            browsePath + Json.encodeToString(WidgetSection.serializer(), section)
    }
    val open: (WidgetSection, WidgetMedia) -> Unit = { section, media ->
        if (media.categoryParams.isNotEmpty() && browsePath.size < 5) {
            browse(section.categorySection(media))
        } else if (media.categoryParams.isEmpty()) {
            onOpenTmdb(
                media.widgetDetailSeed(),
                WidgetSourceContext(section.widgetId, media, section.params, section.id),
            )
        }
    }
    val sections = page.sections.filterNot { it.hidden }
    val feeds = rememberWidgetPageFeeds(sections, repository)
    val homeActive = active && browsePath.isEmpty()
    WidgetHomeRefresh(feeds.values.toList(), homeActive) {
        WidgetHomeSections(
            sections = sections,
            bottomInset = floatingNavigationContentInset(),
            empty = { WidgetEmptyState(AppIcons.Home, "首页还没有内容", "添加栏目，或重新显示已隐藏的栏目。", "编辑页面", onEdit) },
        ) { section ->
            WidgetSectionContent(section, feeds.getValue(section.id), homeActive, onBrowse = {
                browse(section)
            }, onOpen = { open(section, it) })
        }
    }
    val browseStates =
        androidx.compose.runtime.saveable
            .rememberSaveableStateHolder()
    browsePath.lastOrNull()?.let { encoded ->
        browseStates.SaveableStateProvider(encoded) {
            val section = remember(encoded) { Json.decodeFromString(WidgetSection.serializer(), encoded) }
            WidgetBrowsePage(
                section,
                repository,
                onBack = { browsePath = browsePath.dropLast(1) },
                onOpen = { open(section, it) },
            )
        }
    }
}

internal fun WidgetSection.categorySection(item: WidgetMedia): WidgetSection =
    copy(
        id = "$id:${item.id}",
        title = item.title,
        params = JsonObject(params + item.categoryParams),
    )

@Composable
internal fun rememberWidgetFeed(
    section: WidgetSection,
    repository: WidgetRepository,
): com.yfuse.core.widget.WidgetFeed {
    val database by repository.database.collectAsState()
    val revision by repository.feedRevision.collectAsState()
    val widget = database.widgets.firstOrNull { it.metadata.id == section.widgetId }
    return remember(
        repository,
        section.id,
        section.widgetId,
        section.moduleId,
        section.params,
        section.style,
        section.limit,
        widget?.digest,
        widget?.enabled,
        revision,
    ) {
        repository.feed(section)
    }
}

@Composable
private fun WidgetSectionContent(
    section: WidgetSection,
    feed: WidgetFeed,
    active: Boolean,
    onBrowse: () -> Unit,
    onOpen: (WidgetMedia) -> Unit,
) {
    val state by feed.state.collectAsState()
    var lastError by remember(feed) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error, state.busy) {
        if (state.error != null) {
            lastError = state.error
        } else if (!state.busy) {
            lastError = null
        }
    }
    WidgetFeedRefreshEffect(feed, active)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                section.title,
                Modifier.weight(1f),
                color = LocalPalette.current.text,
                style = AppTypography.section.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.homeItems != null && state.items.size > 1) {
                WidgetHomeAction(AppIcons.Refresh, "${section.title}换一批", feed::nextRecommendations)
            }
            WidgetHomeAction(AppIcons.ChevronRight, "查看${section.title}全部内容", onBrowse)
        }
        if (state.items.isEmpty() && state.error == null && (!state.loaded || state.busy) && lastError == null) {
            WidgetSectionLoading(WidgetStyle.fromId(section.style), section.compact)
        }
        if (state.loaded && !state.busy && state.items.isEmpty() && state.error == null) {
            WidgetEmptyState(AppIcons.Movie, "暂无内容", "可以在编辑首页中调整栏目筛选条件。")
        }
        // A requested new batch should show its lead, even when a small pool only changes order.
        key(state.homeItems?.map(WidgetMedia::id)) {
            WidgetCards(
                WidgetStyle.fromId(section.style),
                state.homeItems ?: state.items.take(section.limit),
                section.showMetadata,
                section.compact,
                onOpen,
            )
        }
        (
            state.error ?: lastError.takeIf {
                state.busy
            }
        )?.let { WidgetLoadError(it, state.busy, state.items.isNotEmpty(), feed::retry) }
    }
}

@Composable
internal fun WidgetLoadError(
    message: String,
    busy: Boolean,
    hasContent: Boolean = false,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeIcon(AppIcons.Info, null, tint = palette.sub2, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (busy) {
                        "正在重新连接"
                    } else if (hasContent) {
                        "后续内容暂未加载"
                    } else {
                        "内容暂时无法加载"
                    },
                    color = palette.text,
                    style = AppTypography.body.strong,
                )
                Text(
                    if (busy) "请稍候，已有内容会保留。" else widgetFailureMessage(message),
                    Modifier.padding(top = 4.dp),
                    color = palette.sub,
                    style = AppTypography.caption.regular,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WidgetRetryButton(onRetry, busy)
        }
    }
}
