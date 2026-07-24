package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.TmdbImages

/** Info page for a TMDB pick the library doesn't have; mirrors the 详情 layout. */
@Composable
fun TmdbInfoScreen(item: TmdbItem, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val pageColor = palette.backgroundStops[1].second

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Dimens.contentBottom),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    AsyncImage(
                        model = TmdbImages.backdrop(item.backdropPath),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            scrim(
                                0.05f to pageColor,
                                0.60f to Color(0xFF140F19).copy(alpha = 0.10f),
                                1f to Color(0xFF140F19).copy(alpha = 0.35f),
                            ),
                        ),
                    )
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 18.dp, top = 12.dp)
                            .size(16.dp)
                            .clickable(onClick = onBack),
                    )
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .offset(y = (-46).dp)
                        .padding(horizontal = Dimens.pageHorizontal),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Poster(
                            url = TmdbImages.poster(item.posterPath),
                            modifier = Modifier
                                .width(84.dp)
                                .height(118.dp)
                                .shadow(Shadows.detailPoster, GlassShapes.poster)
                                .border(2.dp, Color.White, GlassShapes.poster),
                        )
                        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                            Text(
                                item.title,
                                style = sc(19f, 800),
                                color = palette.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    item.year,
                                    if (item.mediaType == "tv") "剧集" else "电影",
                                    item.rating?.let { "TMDB ${(it * 10).toInt() / 10.0}" },
                                ).joinToString(" · "),
                                style = mr(11f, 400),
                                color = palette.sub,
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().glass(GlassShapes.card).padding(16.dp)) {
                        Text("你的媒体库中暂无此内容", style = sc(12.5f, 500), color = palette.sub2)
                    }

                    if (!item.overview.isNullOrBlank()) {
                        Text(
                            item.overview,
                            style = sc(12.5f, 400, lineHeight = 12.5f * 1.6f),
                            color = palette.body,
                        )
                    }
                }
            }
        }
    }
}
