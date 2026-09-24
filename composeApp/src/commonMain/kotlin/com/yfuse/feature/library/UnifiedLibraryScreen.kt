package com.yfuse.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CrossServerMediaGroup
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.TabBarInset
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.LibrarySort
import kotlinx.coroutines.launch
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
fun UnifiedLibraryScreen(
    repository: EmbyRepository,
    registry: ServerRegistry,
    onBack: () -> Unit,
    onOpenItem: (String, String) -> Unit,
) {
    val servers by registry.data.collectAsState()
    val pager =
        remember(repository) {
            UnifiedLibraryPager(
                libraries = repository::libraries,
                page = { server, library, offset, limit, unplayed ->
                    repository.libraryItems(
                        server,
                        library,
                        LibrarySort.Name,
                        startIndex = offset,
                        limit = limit,
                        unplayedOnly = unplayed,
                    )
                },
            )
        }
    val state by pager.state.collectAsState()
    var query by remember { mutableStateOf(UnifiedLibraryQuery()) }
    var refresh by remember { mutableStateOf(0) }
    var sources by remember { mutableStateOf<CrossServerMediaGroup?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    LaunchedEffect(servers, query, refresh) {
        pager.reset(servers.servers, query)
        pager.loadMore()
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            LibraryAction("返回", onClick = onBack)
            Text("全部服务器", style = AppTypography.body.strong, modifier = Modifier.padding(top = 12.dp))
            LibraryAction("刷新", onClick = { refresh++ })
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(UnifiedLibraryType.entries) { type ->
                LibraryAction(type.label, selected = query.type == type) { query = query.copy(type = type) }
            }
            item {
                LibraryAction("只看未看", selected = query.unplayedOnly) {
                    query =
                        query.copy(unplayedOnly = !query.unplayedOnly)
                }
            }
        }
        Text(
            "已加载 ${state.groups.size} 部 · ${state.completedLibraries}/${state.libraryCount} 个库已读完 · 已加载内容按片名排列",
            style = AppTypography.caption.regular,
            color = palette.sub2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(125.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = TabBarInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (servers.servers.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("当前资料没有可访问的服务器，请在服务器页添加或由家长关联媒体用户。", color = palette.sub2)
                }
            }
            if (state.failures.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        state.failures.forEach { (source, error) ->
                            Text("$source：$error", style = AppTypography.caption.regular, color = palette.error)
                        }
                        LibraryAction("重试失败的库", onClick = { scope.launch { pager.loadMore(retryFailures = true) } })
                        Text("连接失败的服务器可使用顶部刷新重新发现。", style = AppTypography.caption.regular, color = palette.sub2)
                    }
                }
            }
            items(state.groups, key = { it.identity }) { group ->
                val hit = group.recommended
                val server = registry.serverById(hit.serverId)
                if (server != null) {
                    Column {
                        PosterCard(
                            baseUrl = server.baseUrl,
                            accessToken = server.accessToken,
                            serverId = server.id,
                            item = hit.item,
                            showProgress = true,
                            onClick = { onOpenItem(server.id, hit.item.id) },
                            onLongClick = { sources = group },
                        )
                        LibraryAction("${group.copies.size} 个片源 · 选择") { sources = group }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    state.loading -> Text("正在读取媒体库…", color = palette.sub2)
                    state.hasMore -> LibraryAction("加载更多", onClick = { scope.launch { pager.loadMore() } })
                    state.groups.isEmpty() && state.failures.isEmpty() -> Text("当前筛选没有内容", color = palette.sub2)
                    else -> Text("已读完可用媒体库", style = AppTypography.caption.regular, color = palette.sub2)
                }
            }
        }
    }
    sources?.let { group ->
        GlassDialog(onDismiss = { sources = null }) {
            OverlayHeader(group.recommended.item.title, onClose = { sources = null })
            group.copies.forEach { hit ->
                OverlayOptionRow(
                    label = "${hit.serverName} · ${hit.item.year ?: "年份未知"}",
                    selected = hit == group.recommended,
                    onClick =
                        overlayAction {
                            sources = null
                            if (registry.serverById(hit.serverId) != null) onOpenItem(hit.serverId, hit.item.id)
                        },
                )
            }
        }
    }
}

@Composable
internal fun LibraryAction(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        if (selected) "✓ $label" else label,
        style = AppTypography.caption.strong,
        color = LocalAccentColors.current.accent,
        modifier = Modifier.pressable(onClick = onClick).touchTarget().padding(horizontal = 8.dp, vertical = 8.dp),
    )
}
