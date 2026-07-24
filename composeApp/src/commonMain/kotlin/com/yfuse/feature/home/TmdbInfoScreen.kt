package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.network.TmdbImages

/** Full TMDB detail page; Emby availability only controls the play action. */
@Composable
fun TmdbInfoScreen(component: TmdbInfoComponent) {
    val state by component.state.collectAsState()
    val detail = state.detail
    val item = detail.item
    val palette = LocalPalette.current
    val pageColor = palette.backgroundStops[1].second

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.56f
        val density = LocalDensity.current
        val panelBrush = remember(pageColor, density) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.58f to pageColor.copy(alpha = 0.94f),
                    1f to pageColor,
                ),
                startY = 0f,
                endY = with(density) { 176.dp.toPx() },
            )
        }
        val listState = rememberLazyListState()
        val lightPageReached by remember(listState, heroHeight, density) {
            derivedStateOf {
                val switchOffset = with(density) { (heroHeight - 56.dp).roundToPx() }
                listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset >= switchOffset
            }
        }
        StatusBarIconStyle(darkIcons = lightPageReached)

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = Dimens.contentBottom),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
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
                            .size(18.dp)
                            .clickable(onClick = component.onBack),
                    )
                }
            }

            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .offset(y = (-132).dp)
                        .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                        .background(panelBrush)
                        .padding(
                            start = Dimens.pageHorizontal,
                            top = 24.dp,
                            end = Dimens.pageHorizontal,
                            bottom = 12.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Poster(
                            url = TmdbImages.poster(item.posterPath),
                            modifier = Modifier
                                .width(112.dp)
                                .height(158.dp)
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
                            Spacer(Modifier.height(5.dp))
                            Text(
                                listOfNotNull(
                                    detail.genres.firstOrNull(),
                                    item.year,
                                    detail.runtimeMinutes?.let { "$it 分钟" },
                                    detail.numberOfSeasons?.let { "$it 季" },
                                ).joinToString(" · "),
                                style = mr(11f, 400),
                                color = palette.sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.rating?.let { rating ->
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    "TMDB ${(rating * 10).toInt() / 10.0}",
                                    style = mr(10f, 700),
                                    color = Brand.Imdb,
                                    modifier = Modifier
                                        .clip(GlassShapes.chipSmall)
                                        .background(Brand.Imdb.copy(alpha = 0.14f))
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    if (state.playable) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(GlassShapes.card)
                                .background(Brand.Primary)
                                .clickable(enabled = !state.resolvingPlay, onClick = component::play)
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.resolvingPlay) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(15.dp),
                                )
                            } else {
                                Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("立即播放", style = sc(13f, 700), color = Color.White)
                            }
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().glass(GlassShapes.card).padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "未加入媒体库 · 以下资料来自 TMDB",
                                style = sc(12f, 500),
                                color = palette.sub2,
                            )
                        }
                    }

                    state.error?.let { error ->
                        Text(
                            error,
                            style = sc(12f, 600),
                            color = Brand.Danger,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(GlassShapes.card)
                                .clickable(onClick = component::dismissError)
                                .padding(12.dp),
                        )
                    }

                    if (!detail.tagline.isNullOrBlank()) {
                        Text(
                            "“${detail.tagline}”",
                            style = sc(13f, 500, lineHeight = 20f),
                            fontStyle = FontStyle.Italic,
                            color = palette.sub,
                        )
                    }

                    if (!item.overview.isNullOrBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("剧情简介", style = sc(14f, 750), color = palette.text)
                            Text(
                                item.overview,
                                style = sc(12.5f, 400, lineHeight = 20f),
                                color = palette.body,
                            )
                        }
                    }

                    if (detail.genres.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(detail.genres) { genre ->
                                Text(
                                    genre,
                                    style = sc(11f, 600),
                                    color = palette.sub,
                                    modifier = Modifier
                                        .glass(GlassShapes.chipSmall)
                                        .padding(horizontal = 11.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }

                    if (detail.cast.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("演职人员", style = sc(14f, 750), color = palette.text)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(detail.cast, key = { it.id }) { person ->
                                    Column(
                                        Modifier.width(70.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        AsyncImage(
                                            model = TmdbImages.poster(person.profilePath, "w185"),
                                            contentDescription = person.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(58.dp)
                                                .clip(CircleShape)
                                                .background(palette.card),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            person.name,
                                            style = sc(10.5f, 650),
                                            color = palette.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        person.role?.let {
                                            Text(
                                                it,
                                                style = sc(9.5f, 400),
                                                color = palette.sub2,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.loading) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                color = Brand.Primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
