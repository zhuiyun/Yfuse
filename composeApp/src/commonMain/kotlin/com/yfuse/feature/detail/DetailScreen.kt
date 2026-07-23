package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.Person
import com.yfuse.core.network.EmbyImages

@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val message = state.error
        if (message != null && state.detail != null) snackbar.showSnackbar(message)
    }

    val baseUrl = state.server?.baseUrl.orEmpty()
    val detail = state.detail
    val heroUrl = detail?.let { EmbyImages.backdrop(baseUrl, it) ?: EmbyImages.poster(baseUrl, it) }
    val accent = rememberDominantColor(heroUrl, MaterialTheme.colorScheme.surfaceVariant)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { _ ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = maxHeight * 0.56f

            when {
                state.loading && detail == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && detail == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { component.store.accept(DetailIntent.Retry) }) { Text("重试") }
                }

                detail != null -> DetailContent(
                    detail = detail,
                    state = state,
                    baseUrl = baseUrl,
                    heroUrl = heroUrl,
                    accent = accent,
                    heroHeight = heroHeight,
                    onPlay = { component.store.accept(DetailIntent.Play) },
                    onSelectSeason = { component.store.accept(DetailIntent.SelectSeason(it)) },
                    onPlayEpisode = { ep ->
                        component.store.accept(
                            DetailIntent.PlayEpisode(ep.id, ep.resumePositionTicks ?: 0L),
                        )
                    },
                )
            }

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
private fun DetailContent(
    detail: MediaDetail,
    state: DetailState,
    baseUrl: String,
    heroUrl: String?,
    accent: Color,
    heroHeight: Dp,
    onPlay: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        // Full-bleed hero, tinted with the colour sampled from the artwork.
        item {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                AsyncImage(
                    model = heroUrl,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.0f to Color.Transparent,
                            0.45f to accent.copy(alpha = 0.18f),
                            0.78f to background.copy(alpha = 0.82f),
                            1.0f to background,
                        ),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp)) {
                    Text(
                        detail.title,
                        style = MaterialTheme.typography.headlineSmall,
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
                            Icon(Icons.Rounded.Star, null, tint = Color(0xFFF5A623), modifier = Modifier.size(16.dp))
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
                enabled = !state.resolvingPlay,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
            ) {
                if (state.resolvingPlay) {
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

        if (state.seasons.size > 1) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.seasons, key = { it.id }) { season ->
                        FilterChip(
                            selected = season.id == state.selectedSeasonId,
                            onClick = { onSelectSeason(season.id) },
                            label = { Text(season.name) },
                        )
                    }
                }
            }
        }

        if (state.episodes.isNotEmpty()) {
            item {
                Text(
                    "剧集",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            items(state.episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    baseUrl = baseUrl,
                    episode = episode,
                    isCurrent = episode.id == detail.id,
                    onClick = { onPlayEpisode(episode) },
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
                    items(detail.people.take(20), key = { it.id }) { PersonCard(baseUrl, it) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(baseUrl: String, episode: Episode, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(132.dp).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = EmbyImages.primary(baseUrl, episode.id, episode.primaryTag, maxHeight = 240),
                contentDescription = episode.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val pct = episode.playedPercentage
            if (pct != null && pct > 0.0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(
                        Modifier.fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                            .height(3.dp).background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "第 $it 集" }, episode.name).joinToString("  "),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.runtimeMinutes != null) {
                Text(
                    "${episode.runtimeMinutes} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
