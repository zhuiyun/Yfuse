package com.yfuse.feature.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.model.MediaLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(component: LibraryComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val store = component.store

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
                state.currentServer == null -> CenterHint(
                    "还没有默认服务器,请到「服务器」添加",
                    Modifier.align(Alignment.Center),
                )

                state.loading && state.libraries.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.libraries.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { store.accept(LibraryIntent.Retry) }) { Text("重试") }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.libraries, key = { it.id }) { LibraryCard(it) }
                }
            }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .let { if (enabled) it else it }
                .padding(end = 8.dp),
        ) {
            TextButton(onClick = { if (enabled) open = true }) {
                Text(
                    currentName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (enabled) {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "切换服务器")
                }
            }
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

@Composable
private fun LibraryCard(library: MediaLibrary) {
    Column {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = library.collectionType.toIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            library.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String?.toIcon(): ImageVector = when (this) {
    "movies" -> Icons.Rounded.Movie
    "tvshows" -> Icons.Rounded.LiveTv
    "music" -> Icons.Rounded.MusicNote
    "boxsets" -> Icons.Rounded.Theaters
    "homevideos", "photos" -> Icons.Rounded.PhotoLibrary
    else -> Icons.Rounded.FolderOpen
}
