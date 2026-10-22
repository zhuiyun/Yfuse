package com.yfuse.feature.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.ThemeIcon
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPage
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetSection
import com.yfuse.core.widget.WidgetStyle
import com.yfuse.core.widget.openWidgetVideo
import com.yfuse.core.widget.searchWidgetMedia
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.yfuse.core.designsystem.ThemeText as Text
import com.yfuse.feature.widgets.WidgetPrimaryButton as Button
import com.yfuse.feature.widgets.WidgetProgress as LinearProgressIndicator
import com.yfuse.feature.widgets.WidgetSecondaryButton as OutlinedButton
import com.yfuse.feature.widgets.WidgetTextButton as TextButton

@Composable
internal fun WidgetPageContent(
    page: WidgetPage,
    repository: WidgetRepository,
    onEdit: () -> Unit,
) {
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    com.yfuse.core.designsystem
        .ScrollToTopOnReselect(listState)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (page.sections.none { !it.hidden }) {
            item {
                WidgetEmptyState(AppIcons.Home, "首页还没有内容", "添加栏目，或重新显示已隐藏的栏目。", "编辑页面", onEdit)
            }
        }
        items(
            page.sections.filterNot { it.hidden },
            key = { it.id },
        ) { section -> WidgetSectionContent(section, repository) }
    }
}

@Composable
private fun WidgetSectionContent(
    section: WidgetSection,
    repository: WidgetRepository,
    categoryDepth: Int = 0,
) {
    val database by repository.database.collectAsState()
    val widget = database.widgets.firstOrNull { it.metadata.id == section.widgetId }
    val module = widget?.metadata?.modules?.firstOrNull { it.id == section.moduleId }
    val sourceKey =
        listOf(section.id, section.widgetId, section.moduleId, section.params, widget?.digest, widget?.enabled)
    var items by remember(sourceKey) { mutableStateOf<List<WidgetMedia>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<WidgetMedia?>(null) }
    var expanded by remember(sourceKey) { mutableStateOf(false) }
    var pageNumber by remember(sourceKey) { mutableIntStateOf(1) }
    var hasMore by remember(sourceKey) { mutableStateOf(true) }
    var category by remember { mutableStateOf<WidgetMedia?>(null) }
    LaunchedEffect(section, widget?.digest, widget?.enabled, refresh, pageNumber) {
        busy = true
        error = null
        try {
            val loaded = repository.load(section, pageNumber, refresh > 0)
            hasMore = loaded.any { next -> pageNumber == 1 || items.none { it.id == next.id } }
            items = (if (pageNumber == 1) loaded else (items + loaded).distinctBy { it.id }).take(500)
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "栏目加载失败"
        } finally {
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(if (categoryDepth == 0) section.title else "全部内容", "刷新") {
            pageNumber = 1
            refresh++
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            WidgetLoadError(message, busy, onRetry = { refresh++ })
        }
        if (!busy && items.isEmpty() && error == null) {
            WidgetEmptyState(AppIcons.Movie, "暂无内容", "换一组筛选条件，或稍后刷新。", "刷新") { refresh++ }
        }
        WidgetCards(
            WidgetStyle.fromId(section.style),
            if (expanded) items else items.take(section.limit),
            section.showMetadata,
            section.compact,
        ) { item ->
            if (item.categoryParams.isNotEmpty() && categoryDepth < 5) category = item else selected = item
        }
        if (items.size > section.limit &&
            !expanded
        ) {
            TextButton(onClick = { expanded = true }) { Text("查看全部 ${items.size} 项") }
        }
        if (expanded &&
            hasMore &&
            module?.params?.any { it.type == "page" } == true
        ) {
            TextButton(
                enabled = !busy && error == null && items.size < 500,
                onClick = { pageNumber++ },
            ) { Text("加载更多") }
        }
        if (!expanded &&
            items.size <= section.limit &&
            hasMore &&
            module?.params?.any { it.type == "page" } == true
        ) {
            TextButton(enabled = !busy && error == null, onClick = {
                expanded = true
                pageNumber++
            }) { Text("更多内容") }
        }
    }
    selected?.let { media -> WidgetMediaDialog(repository, section.widgetId, media, onDismiss = { selected = null }) }
    category?.let { item ->
        FullWidgetDialog(onClose = { category = null }) {
            WidgetFrame(item.title, { category = null }) {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        WidgetSectionContent(
                            section.copy(
                                id = section.id + ":" + item.id,
                                title = item.title,
                                params =
                                    JsonObject(
                                        section.params + item.categoryParams,
                                    ),
                            ),
                            repository,
                            categoryDepth + 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetMediaDialog(
    repository: WidgetRepository,
    widgetId: String,
    initial: WidgetMedia,
    onDismiss: () -> Unit,
) {
    var media by remember(initial) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(initial.link.isNotBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(widgetId, initial, retry) {
        busy = true
        try {
            media = repository.detail(widgetId, initial)
            error = null
        } catch (
            e: CancellationException,
        ) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "详情加载失败"
        } finally {
            busy = false
        }
    }

    fun play(item: WidgetMedia) {
        try {
            openWidgetVideo(item.copy(headers = media.headers + item.headers))
        } catch (
            e: Exception,
        ) {
            error = e.message ?: "无法播放此地址"
        }
    }
    FullWidgetDialog(onClose = onDismiss) {
        WidgetFrame("内容详情", onDismiss) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item { Artwork(media, Modifier.fillMaxWidth().aspectRatio(1.78f).clip(AppShapes.card)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            media.title,
                            color = LocalPalette.current.text,
                            style = AppTypography.display.strong,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (media.year.isNotBlank()) {
                                Text(
                                    media.year,
                                    color = LocalPalette.current.sub,
                                    style = AppTypography.body.regular,
                                )
                            }
                            val typeLabel =
                                when (media.mediaType) {
                                    "movie" -> "电影"
                                    "tv", "series" -> "剧集"
                                    else -> ""
                                }
                            if (typeLabel.isNotBlank()) {
                                Text(
                                    typeLabel,
                                    color = LocalPalette.current.sub2,
                                    style = AppTypography.body.regular,
                                )
                            }
                            if (media.rating.isNotBlank()) {
                                Row(
                                    Modifier
                                        .glass(
                                            AppShapes.chip,
                                            LocalPalette.current.card2,
                                            LocalPalette.current.border,
                                        ).padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ThemeIcon(
                                        AppIcons.StarFilled,
                                        null,
                                        tint = LocalAccentColors.current.accent,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        media.rating,
                                        color = LocalPalette.current.text,
                                        style = AppTypography.body.strong,
                                    )
                                }
                            }
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { message -> item { WidgetLoadError(message, busy, onRetry = { retry++ }) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (media.videoUrl.isNotBlank()) {
                            Button(onClick = { play(media) }, modifier = Modifier.fillMaxWidth()) {
                                ThemeIcon(AppIcons.Play, null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                                Text("播放")
                            }
                        }
                        OutlinedButton(onClick = {
                            searchWidgetMedia(media.title)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            ThemeIcon(AppIcons.Search, null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                            Text("搜索媒体库资源")
                        }
                    }
                }
                if (media.description.isNotBlank()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetGroupCaption("简介")
                            Text(
                                media.description,
                                color = LocalPalette.current.sub,
                                style = AppTypography.body.reading,
                            )
                        }
                    }
                }
                if (media.children.isNotEmpty()) item { WidgetGroupCaption("分集与播放来源") }
                items(media.children, key = { it.id }) { child ->
                    WidgetChildRow(child, media.headers, onPlay = ::play)
                }
                if (!busy && media.videoUrl.isBlank() && media.children.isEmpty()) {
                    item {
                        Text(
                            "可在媒体库中搜索这部作品的可播放版本。",
                            color = LocalPalette.current.sub2,
                            style = AppTypography.body.reading,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetLoadError(
    message: String,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    var details by remember(message) { mutableStateOf(false) }
    val palette = LocalPalette.current
    Panel {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeIcon(AppIcons.Info, null, tint = palette.error, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("内容暂时无法加载", color = palette.text, style = AppTypography.body.strong)
                TextButton({ details = !details }) {
                    Text(if (details) "收起原因" else "查看原因", style = AppTypography.caption.regular)
                }
            }
            OutlinedButton(onRetry, enabled = !busy) { Text("重试") }
        }
        if (details) Text(message.take(2000), color = palette.error, style = AppTypography.body.reading)
    }
}

@Composable
private fun WidgetChildRow(
    item: WidgetMedia,
    parentHeaders: Map<String, String>,
    onPlay: (WidgetMedia) -> Unit,
    depth: Int = 0,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val palette = LocalPalette.current
    val hasChildren = item.children.isNotEmpty()
    val playable = item.videoUrl.isNotBlank()
    val headers = parentHeaders + item.headers
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(enabled = hasChildren || playable, role = Role.Button, onClick = {
                    if (hasChildren) expanded = !expanded else onPlay(item.copy(headers = headers))
                })
                .padding(start = (depth.coerceAtMost(3) * 12).dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeIcon(
                if (hasChildren) AppIcons.Series else AppIcons.Movie,
                null,
                tint = palette.sub2,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    color = palette.text,
                    style = AppTypography.body.medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasChildren) {
                    Text(
                        "${item.children.size} 项内容",
                        color = palette.sub2,
                        style = AppTypography.caption.regular,
                    )
                }
            }
            if (playable) {
                WidgetIconButton(AppIcons.Play, "播放${item.title}", {
                    onPlay(item.copy(headers = headers))
                })
            }
            if (hasChildren) {
                WidgetIconButton(
                    if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                    if (expanded) "收起${item.title}" else "展开${item.title}",
                    { expanded = !expanded },
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = (depth.coerceAtMost(3) * 12).dp)
                .height(0.5.dp)
                .background(palette.border),
        )
        if (expanded) {
            item.children.forEach { child ->
                key(child.id) { WidgetChildRow(child, headers, onPlay, depth + 1) }
            }
        }
    }
}
