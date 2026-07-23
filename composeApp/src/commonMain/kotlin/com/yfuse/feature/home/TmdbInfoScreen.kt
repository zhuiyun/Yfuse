package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.glass
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.TmdbImages

/** Info page for a TMDB pick the library doesn't have. */
@Composable
fun TmdbInfoScreen(item: TmdbItem, onBack: () -> Unit) {
    val glass = LocalGlass.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TabBarInset),
        ) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    AsyncImage(
                        model = TmdbImages.backdrop(item.backdropPath),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.5f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Box(
                        Modifier
                            .width(104.dp)
                            .aspectRatio(2f / 3f)
                            .clip(GlassShapes.poster)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = TmdbImages.poster(item.posterPath),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = glass.onGlass,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                item.year,
                                if (item.mediaType == "tv") "剧集" else "电影",
                                item.rating?.let { "TMDB ${(it * 10).toInt() / 10.0}" },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = glass.onGlassMuted,
                        )
                    }
                }
            }

            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .glass(GlassShapes.card)
                        .padding(16.dp),
                ) {
                    Text(
                        "你的媒体库中暂无此内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.onGlassMuted,
                    )
                }
            }

            if (!item.overview.isNullOrBlank()) {
                item {
                    Text(
                        item.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glass.onGlassMuted,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = Color(0x66000000),
            modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopStart),
        ) {
            Box(Modifier.clickable(onClick = onBack).padding(6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
        }
    }
}
