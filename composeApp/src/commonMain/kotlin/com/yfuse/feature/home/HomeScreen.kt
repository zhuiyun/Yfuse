package com.yfuse.feature.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.TmdbImages

@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val glass = LocalGlass.current

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && state.content.isEmpty ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            state.error != null && state.content.isEmpty -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, color = glass.onGlassMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { component.store.accept(HomeIntent.Retry) }) { Text("重试") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(top = 8.dp, bottom = TabBarInset),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item { Greeting() }
                item { SearchEntry() }

                state.content.featured.firstOrNull()?.let { featured ->
                    item {
                        FeaturedCard(featured) { component.store.accept(HomeIntent.Open(featured)) }
                    }
                }

                items(state.content.rows) { row ->
                    PosterRow(row.title, row.items) { component.store.accept(HomeIntent.Open(it)) }
                }
            }
        }

        if (state.resolving) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun Greeting() {
    val glass = LocalGlass.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("下午好", style = MaterialTheme.typography.bodyMedium, color = glass.onGlassMuted)
            Text(
                "继续你的旅程",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = glass.onGlass,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SearchEntry() {
    val glass = LocalGlass.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .glass(GlassShapes.pill, strong = true)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = glass.onGlassMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("搜索电影、剧集、演员", style = MaterialTheme.typography.bodyMedium, color = glass.onGlassMuted)
    }
}

@Composable
private fun FeaturedCard(item: TmdbItem, onPlay: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .aspectRatio(16f / 10f)
            .clip(GlassShapes.panel)
            .background(Color(0xFF1B2233))
            .clickable(onClick = onPlay),
    ) {
        AsyncImage(
            model = TmdbImages.backdrop(item.backdropPath),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x66000000),
                    0.45f to Color(0x33000000),
                    1f to Color(0xE6101725),
                ),
            ),
        )
        Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Box(
                Modifier
                    .clip(GlassShapes.chip)
                    .background(Color(0x33FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("今日精选", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(item.year, item.rating?.let { "评分 ${(it * 10).toInt() / 10.0}" })
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xCCFFFFFF),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .clip(GlassShapes.pill)
                    .background(Color(0x3DFFFFFF))
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即播放", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

@Composable
private fun PosterRow(title: String, items: List<TmdbItem>, onClick: (TmdbItem) -> Unit) {
    val glass = LocalGlass.current
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = glass.onGlass,
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item -> TmdbPoster(item) { onClick(item) } }
        }
    }
}

@Composable
private fun TmdbPoster(item: TmdbItem, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Column(Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(GlassShapes.poster)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val url = TmdbImages.poster(item.posterPath)
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
                    null,
                    tint = glass.onGlassMuted,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = glass.onGlass,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.year != null) {
            Text(item.year, style = MaterialTheme.typography.bodySmall, color = glass.onGlassMuted)
        }
    }
}
