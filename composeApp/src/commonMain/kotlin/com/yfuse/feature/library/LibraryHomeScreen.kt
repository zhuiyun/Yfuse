package com.yfuse.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHomeScreen(component: LibraryHomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store
    val baseUrl = state.currentServer?.baseUrl.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    ServerSwitcher(
                        currentName = state.currentServer?.serverName ?: "媒体库",
                        servers = state.servers.map { it.id to it.serverName },
                        onSelect = { store.accept(LibraryIntent.SelectServer(it)) },
                        enabled = state.servers.size > 1,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.currentServer == null ->
                    CenterHint("还没有默认服务器,请到「服务器」添加", Modifier.align(Alignment.Center))

                state.loading && state.content.isEmpty ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.content.isEmpty -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { store.accept(LibraryIntent.Retry) }) { Text("重试") }
                }

                state.content.isEmpty ->
                    CenterHint("这个服务器还没有内容", Modifier.align(Alignment.Center))

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.content.featured.isNotEmpty()) {
                        item {
                            FeaturedCarousel(baseUrl, state.content.featured) { component.onOpenItem(it.id) }
                        }
                    }
                    if (state.content.resume.isNotEmpty()) {
                        item {
                            PosterRow(
                                baseUrl = baseUrl,
                                title = "继续观看",
                                items = state.content.resume,
                                showProgress = true,
                                onSeeAll = null,
                                onItemClick = { component.onOpenItem(it.id) },
                            )
                        }
                    }
                    items(state.content.rows, key = { it.libraryId }) { row ->
                        PosterRow(
                            baseUrl = baseUrl,
                            title = row.title,
                            items = row.items,
                            showProgress = false,
                            onSeeAll = { component.onSeeAll(row.libraryId, row.title) },
                            onItemClick = { component.onOpenItem(it.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCarousel(baseUrl: String, items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Box(
                Modifier
                    .fillParentMaxWidth(0.9f)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onClick(item) },
            ) {
                AsyncImage(
                    model = EmbyImages.backdrop(baseUrl, item),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC0E1520)),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle != null) {
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    baseUrl: String,
    title: String,
    items: List<MediaItem>,
    showProgress: Boolean,
    onSeeAll: (() -> Unit)?,
    onItemClick: (MediaItem) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text("查看更多", style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(baseUrl, item, showProgress, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
internal fun PosterCard(baseUrl: String, item: MediaItem, showProgress: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val url = EmbyImages.poster(baseUrl, item)
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            }
            val pct = item.playedPercentage
            if (showProgress && pct != null && pct > 0.0) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle != null) {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerSwitcher(
    currentName: String,
    servers: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { if (enabled) open = true }) {
            Text(
                currentName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (enabled) Icon(Icons.Rounded.ArrowDropDown, contentDescription = "切换服务器")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
