package com.yfuse.feature.detail

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.Person
import com.yfuse.core.network.EmbyImages
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val snackbar = remember { SnackbarHostState() }

    // Surface play/resolve failures without replacing the loaded detail.
    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null && state.detail != null) snackbar.showSnackbar(message)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.detail == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.detail == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { component.store.accept(DetailIntent.Retry) }) { Text("重试") }
                }

                state.detail != null -> DetailContent(
                    detail = state.detail!!,
                    baseUrl = state.server?.baseUrl.orEmpty(),
                    resolving = state.resolvingPlay,
                    onPlay = { component.store.accept(DetailIntent.Play) },
                )
            }

            // Back button overlaid on the backdrop.
            Surface(
                shape = CircleShape,
                color = Color(0x66000000),
                modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart),
            ) {
                Box(Modifier.clickable(onClick = component.onBack).padding(6.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DetailContent(detail: MediaDetail, baseUrl: String, resolving: Boolean, onPlay: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = EmbyImages.backdrop(baseUrl, detail),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.5f to Color.Transparent, 1f to MaterialTheme.colorScheme.background),
                    ),
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 0.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(110.dp).aspectRatio(2f / 3f),
                ) {
                    AsyncImage(
                        model = EmbyImages.poster(baseUrl, detail),
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        detail.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildMeta(detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (detail.communityRating != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFF5A623), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                ((detail.communityRating * 10).toInt() / 10.0).toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onPlay,
                enabled = !resolving,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            ) {
                if (resolving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (detail.type == "Series") "播放下一集" else "播放")
                }
            }
        }

        if (!detail.overview.isNullOrBlank()) {
            item {
                Text(
                    detail.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (detail.people.isNotEmpty()) {
            item {
                Text(
                    "演职人员",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(detail.people.take(20), key = { it.id }) { person ->
                        PersonCard(baseUrl, person)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(baseUrl: String, person: Person) {
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp)) {
            val url = EmbyImages.avatar(baseUrl, person)
            if (url != null) {
                AsyncImage(model = url, contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(person.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (person.role != null) {
            Text(person.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun buildMeta(detail: MediaDetail): String {
    val parts = buildList {
        detail.year?.let { add(it.toString()) }
        detail.runtimeMinutes?.let { add("$it 分钟") }
        if (detail.genres.isNotEmpty()) add(detail.genres.take(3).joinToString(" / "))
        detail.officialRating?.let { add(it) }
    }
    return parts.joinToString("  ·  ")
}
