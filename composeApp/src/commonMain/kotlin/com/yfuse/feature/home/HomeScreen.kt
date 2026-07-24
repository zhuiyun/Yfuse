package com.yfuse.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.app.TabBarInset
import com.yfuse.app.hideBottomBarOnScroll
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PrimaryGradient
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.TmdbImages
import com.yfuse.resources.Res
import com.yfuse.resources.logo
import org.jetbrains.compose.resources.painterResource

/**
 * 首页 — the prototype's `isHome` screen:
 * `padding:52px 18px 100px; gap:22px`, greeting, search entry, hero, 继续观看, 为你推荐.
 */
@Composable
fun HomeScreen(component: HomeComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && state.content.isEmpty ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            state.error != null && state.content.isEmpty -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error!!, style = sc(13f, 400), color = palette.sub, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { component.store.accept(HomeIntent.Retry) }) {
                    Text("重试", style = sc(13f, 700))
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding().hideBottomBarOnScroll(),
                contentPadding = PaddingValues(
                    top = Dimens.contentTop,
                    bottom = TabBarInset,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.sectionGap),
            ) {
                item { Greeting(onOpenProfile = component.onOpenProfile) }
                item { SearchEntry(onClick = component.onOpenSearch) }

                state.content.featured.firstOrNull()?.let { featured ->
                    item {
                        HeroCard(featured) { component.store.accept(HomeIntent.Open(featured)) }
                    }
                }

                if (state.resume.isNotEmpty()) {
                    item {
                        ContinueWatching(
                            baseUrl = component.serverBaseUrl,
                            items = state.resume,
                            onClick = { component.store.accept(HomeIntent.OpenResume(it)) },
                        )
                    }
                }

                val recommended = state.content.rows.flatMap { it.items }
                if (recommended.isNotEmpty()) {
                    item {
                        Recommended(recommended) { component.store.accept(HomeIntent.Open(it)) }
                    }
                }
            }
        }

        if (state.resolving) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Header row — `gap:10px`; left cluster `gap:9px` with the 30px mark,
 * `下午好` at `400 11px Manrope`, `继续你的旅程` at `800 17px`; 36px avatar.
 */
@Composable
private fun Greeting(onOpenProfile: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.pageHorizontal),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppMark(Modifier.size(30.dp))
            Column {
                Text("下午好", style = mr(11f, 400), color = palette.sub)
                Text(
                    "继续你的旅程",
                    style = sc(17f, 800),
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(PrimaryGradient)
                .clickable(onClick = onOpenProfile),
        )
    }
}

/**
 * Search entry — `radius:20px; padding:11px 16px; gap:8px;`
 * `--pg-card` over a 1px `--pg-border`, `0 6px 18px rgba(90,120,180,.12)`.
 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .shadow(Shadows.searchBar, shape)
            .glass(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = Color(0xFF7A8FC4), modifier = Modifier.size(15.dp))
        Text("搜索电影、剧集、演员", style = mr(13f, 400), color = palette.sub2)
    }
}

/**
 * 今日精选 hero — `height:170px; radius:24px;`
 * scrim `linear-gradient(0deg,rgba(10,14,26,.75),rgba(10,14,26,.05) 55%)`,
 * `0 10px 30px rgba(30,40,70,.18)`, 1px `--pg-card2` hairline.
 */
@Composable
private fun HeroCard(item: TmdbItem, onPlay: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .height(170.dp)
            .shadow(Shadows.hero, GlassShapes.hero)
            .glass(GlassShapes.hero, palette.card2)
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
                scrim(
                    0f to Color(0xFF0A0E1A).copy(alpha = 0.75f),
                    0.55f to Color(0xFF0A0E1A).copy(alpha = 0.05f),
                ),
            ),
        )

        // 今日精选 chip: left 16, top 14, 500 10px Manrope, #CFE0FF on white 15%.
        Text(
            "今日精选",
            style = mr(10f, 500),
            color = Color(0xFFCFE0FF),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.15f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        ) {
            Text(
                item.title,
                style = sc(20f, 800),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(item.year, item.rating?.let { "评分 ${(it * 10).toInt() / 10.0}" })
                    .joinToString(" · "),
                style = mr(11f, 400),
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Play,
                        null,
                        tint = Color(0xFF1B2436),
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text("立即播放", style = mr(11f, 500), color = Color.White)
            }
        }
    }
}

/** 继续观看 — 118×74 artwork with title/year below and a 3px progress bar. */
@Composable
private fun ContinueWatching(
    baseUrl: String,
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.pageHorizontal)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("继续观看", style = sc(14f, 700), color = palette.text)
            Text("全部", style = mr(11f, 400), color = palette.sub2)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(items, key = { it.id }) { item ->
                CaptionedPoster(
                    url = EmbyImages.backdrop(baseUrl, item, maxWidth = 480)
                        ?: EmbyImages.poster(baseUrl, item),
                    title = item.title,
                    year = item.year?.toString(),
                    progress = item.playedPercentage?.let { (it / 100.0).toFloat() },
                    onClick = { onClick(item) },
                    modifier = Modifier.width(118.dp),
                    posterModifier = Modifier.fillMaxWidth().height(74.dp),
                )
            }
        }
    }
}

/** 为你推荐 — three enlarged portrait posters with identity below the artwork. */
@Composable
private fun Recommended(items: List<TmdbItem>, onClick: (TmdbItem) -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.padding(horizontal = Dimens.pageHorizontal)) {
        Text(
            "为你推荐",
            style = sc(14f, 700),
            color = palette.text,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        // A plain Column of Rows keeps the grid inside the outer LazyColumn.
        items.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { item ->
                    CaptionedPoster(
                        url = TmdbImages.poster(item.posterPath),
                        title = item.title,
                        year = item.year,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f),
                        posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The app mark, sized to the prototype's 30px header slot. */
@Composable
private fun AppMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
