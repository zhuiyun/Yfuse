package com.yfuse.feature.detail

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.cssShadow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.rememberDominantColor
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.scrim
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.Person
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyImages

/** 详情 — artwork fills at least 56% of the viewport before the information sheet. */
@Composable
fun DetailScreen(component: DetailComponent) {
    val state by component.store.states.collectAsState(component.store.state)
    val palette = LocalPalette.current
    val detail = state.detail
    val baseUrl = state.server?.baseUrl.orEmpty()

    val heroUrl = detail?.let { EmbyImages.backdrop(baseUrl, it) ?: EmbyImages.poster(baseUrl, it) }
    val accent = rememberDominantColor(heroUrl, Brand.Primary)

    var seasonPickerOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = maxHeight * 0.56f
        val density = LocalDensity.current
        val pageColor = palette.backgroundStops[1].second
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
        StatusBarIconStyle(darkIcons = detail == null || lightPageReached)

        when {
            state.loading && detail == null ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))

            detail == null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.error ?: "加载失败",
                    style = sc(13f, 400),
                    color = palette.sub,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { component.store.accept(DetailIntent.Retry) }) {
                    Text("重试", style = sc(13f, 700), color = Brand.Primary)
                }
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = Dimens.contentBottom),
            ) {
                item { Hero(heroUrl, detail.title, heroHeight, component.onBack) }

                item {
                    // Pull the information sheet over the lower edge of the artwork.
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
                        TitleBlock(baseUrl, detail)
                        ActionRow(
                            accent = accent,
                            label = if (detail.type == "Series") "继续观看" else "立即播放",
                            resolving = state.resolvingPlay,
                            onPlay = { component.store.accept(DetailIntent.Play) },
                        )
                        if (!detail.overview.isNullOrBlank()) {
                            Text(
                                detail.overview,
                                style = sc(12.5f, 400, lineHeight = 12.5f * 1.6f),
                                color = palette.body,
                            )
                        }

                        if (state.sources.isNotEmpty()) {
                            SourceComparison(state.sources, accent)
                        }

                        if (state.episodes.isNotEmpty()) {
                            EpisodeSection(
                                baseUrl = baseUrl,
                                accent = accent,
                                seasonLabel = state.seasons
                                    .firstOrNull { it.id == state.selectedSeasonId }
                                    ?.name
                                    ?: "剧集",
                                seasons = state.seasons.map { it.id to it.name },
                                selectedSeasonId = state.selectedSeasonId,
                                pickerOpen = seasonPickerOpen,
                                onTogglePicker = { seasonPickerOpen = !seasonPickerOpen },
                                onSelectSeason = {
                                    seasonPickerOpen = false
                                    component.store.accept(DetailIntent.SelectSeason(it))
                                },
                                episodes = state.episodes,
                                onPlayEpisode = { episode ->
                                    component.store.accept(
                                        DetailIntent.PlayEpisode(
                                            episode.id,
                                            episode.resumePositionTicks ?: 0L,
                                        ),
                                    )
                                },
                            )
                        }

                        if (detail.people.isNotEmpty()) {
                            CastRow(baseUrl, detail.people)
                        }
                    }
                }
            }
        }

        if (state.resolvingPlay) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/**
 * 260px backdrop under the annotated wash
 * `0deg {page} 5%, rgba(20,15,25,.1) 60%, rgba(20,15,25,.35)`, plus a white chevron.
 */
@Composable
private fun Hero(url: String?, title: String, height: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
    val palette = LocalPalette.current
    // The prototype hardcodes #EEF1F5, its light page colour; using the active
    // backdrop's mid stop keeps the fade landing on the page in dark mode too.
    val pageColor = palette.backgroundStops[1].second
    Box(Modifier.fillMaxWidth().height(height)) {
        AsyncImage(
            model = url,
            contentDescription = title,
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

/**
 * Poster + title cluster — `gap:16px`, bottom aligned; poster 112×158 with a 2px
 * white border and `0 10px 24px rgba(0,0,0,.25)`.
 */
@Composable
private fun TitleBlock(baseUrl: String, detail: MediaDetail) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Poster(
            url = EmbyImages.poster(baseUrl, detail),
            modifier = Modifier
                .width(112.dp)
                .height(158.dp)
                .shadow(Shadows.detailPoster, GlassShapes.poster)
                .border(2.dp, Color.White, GlassShapes.poster),
        )
        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
            Text(
                detail.title,
                style = sc(19f, 800),
                color = palette.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    detail.genres.firstOrNull(),
                    detail.year?.toString(),
                    detail.runtimeMinutes?.let { "$it 分钟" },
                    detail.officialRating,
                ).joinToString(" · "),
                style = mr(11f, 400),
                color = palette.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.communityRating != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The prototype shows IMDb and 豆瓣 chips; Emby exposes one
                    // community rating, rendered in the IMDb chip's styling.
                    ScoreChip(
                        "评分 ${(detail.communityRating * 10).toInt() / 10.0}",
                        Brand.Imdb,
                        Brand.Imdb.copy(alpha = 0.14f),
                    )
                    if (detail.officialRating != null) {
                        ScoreChip(
                            detail.officialRating,
                            Brand.Douban,
                            Brand.Douban.copy(alpha = 0.12f),
                        )
                    }
                }
            }
        }
    }
}

/** `700 10px Manrope`, `padding:2px 7px`, `radius:6px`. */
@Composable
private fun ScoreChip(label: String, fg: Color, bg: Color) {
    Text(
        label,
        style = mr(10f, 700),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/**
 * Play + add — `gap:8px`; play fills the row at `radius:16px`, `padding:11px`,
 * `700 13px`, `0 8px 20px {accent 30%}`; the 44px add button is glass tinted `{accent}`.
 */
@Composable
private fun ActionRow(accent: Color, label: String, resolving: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .cssShadow(
                    offsetY = 8.dp,
                    blur = 20.dp,
                    color = accent.copy(alpha = 0.3f),
                    shape = GlassShapes.card,
                )
                .background(accent, GlassShapes.card)
                .clickable(enabled = !resolving, onClick = onPlay)
                .padding(11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Icon(AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(13.dp))
            Text(label, style = sc(13f, 700), color = Color.White)
            Spacer(Modifier.weight(1f))
        }
        Box(
            Modifier.width(44.dp).height(41.dp).glass(GlassShapes.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Add,
                contentDescription = "加入列表",
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * 跨服务器片源对比 — 140px columns, `radius:16px`, `padding:12px`, `gap:10px`;
 * the current server is tinted `{accent 10%}` over `{accent 30%}`.
 */
@Composable
private fun SourceComparison(sources: List<ServerSource>, accent: Color) {
    val palette = LocalPalette.current
    Column {
        Text(
            "跨服务器片源对比",
            style = sc(13f, 700),
            color = palette.text,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sources, key = { it.serverId }) { entry ->
                val available = entry.reachable && entry.source != null
                Column(
                    Modifier
                        .width(140.dp)
                        .glass(
                            shape = GlassShapes.card,
                            fill = if (entry.isCurrent) accent.copy(alpha = 0.1f) else palette.card2,
                            border = if (entry.isCurrent) accent.copy(alpha = 0.3f) else palette.border,
                        )
                        .padding(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    cssLinearGradient(
                                        135f,
                                        0f to Brand.PrimaryGradTop,
                                        1f to Brand.PrimaryGradBottom,
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                entry.serverName.take(1).uppercase(),
                                style = mr(11f, 700),
                                color = Color.White,
                            )
                        }
                        if (entry.isCurrent) {
                            Text(
                                "当前",
                                style = mr(10f, 700),
                                color = accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(accent.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        entry.serverName,
                        style = sc(12f, 700),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !entry.reachable -> "离线 · 无法获取"
                            entry.source == null -> "无此片源"
                            else -> entry.source.summary
                        },
                        style = mr(10f, 400, lineHeight = 10f * 1.5f),
                        color = if (available) palette.body else palette.hint,
                    )
                }
            }
        }
    }
}

/** 主演 — `gap:14px`; 52px round avatars with `500 10px Manrope` names 6px below. */
@Composable
private fun CastRow(baseUrl: String, people: List<Person>) {
    val palette = LocalPalette.current
    Column {
        Text(
            "主演",
            style = sc(13f, 700),
            color = palette.text,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(people.take(20), key = { it.id }) { person ->
                Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Poster(
                        url = EmbyImages.avatar(baseUrl, person),
                        shape = CircleShape,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        person.name,
                        style = mr(10f, 500),
                        color = palette.body,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Season header with the `切换季数 ▾` chip, then a horizontally scrolling
 * episode rail above the cast section.
 */
@Composable
private fun EpisodeSection(
    baseUrl: String,
    accent: Color,
    seasonLabel: String,
    seasons: List<Pair<String, String>>,
    selectedSeasonId: String?,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onSelectSeason: (String) -> Unit,
    episodes: List<Episode>,
    onPlayEpisode: (Episode) -> Unit,
) {
    val palette = LocalPalette.current
    Column {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(seasonLabel, style = sc(13f, 700), color = palette.text)
                if (seasons.size > 1) {
                    Row(
                        Modifier
                            .clip(GlassShapes.chipSmall)
                            .background(accent.copy(alpha = 0.1f))
                            .clickable(onClick = onTogglePicker)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("切换季数", style = mr(11f, 500), color = accent)
                        Icon(AppIcons.ChevronDown, null, tint = accent, modifier = Modifier.size(9.dp))
                    }
                }
            }

            if (pickerOpen) {
                // `top:26px; right:0; width:170px`, `rgba(255,255,255,.92)`, `radius:14px`.
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 26.dp)
                        .width(170.dp)
                        .shadow(Shadows.menu, GlassShapes.chip)
                        .glass(
                            GlassShapes.chip,
                            Color.White.copy(alpha = 0.92f),
                            Color.White.copy(alpha = 0.9f),
                        )
                        .padding(6.dp),
                ) {
                    seasons.forEach { (id, name) ->
                        val selected = id == selectedSeasonId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(GlassShapes.chipSmall)
                                .background(
                                    if (selected) accent.copy(alpha = 0.1f) else Color.Transparent,
                                )
                                .clickable { onSelectSeason(id) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                style = sc(12.5f, if (selected) 700 else 500),
                                color = if (selected) accent else Color(0xFF151A22),
                            )
                            if (selected) {
                                Icon(
                                    AppIcons.Check,
                                    null,
                                    tint = accent,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(episodes, key = { it.id }) { episode ->
                val watching = (episode.playedPercentage ?: 0.0) > 0.0
                Column(
                    Modifier
                        .width(176.dp)
                        .glass(
                            shape = GlassShapes.chip,
                            fill = if (watching) accent.copy(alpha = 0.08f) else palette.card,
                            border = if (watching) accent.copy(alpha = 0.25f) else palette.border,
                        )
                        .clickable { onPlayEpisode(episode) }
                        .padding(8.dp),
                ) {
                    Poster(
                        url = EmbyImages.primary(baseUrl, episode.id, episode.primaryTag, maxHeight = 240),
                        progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(92.dp),
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        listOfNotNull(episode.indexNumber?.let { "第${it}集" }, episode.name)
                            .joinToString(" · "),
                        style = sc(12f, 700),
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            if (watching) append("正在观看")
                            val runtime = episode.runtimeMinutes?.let { "$it 分钟" }
                            if (watching && runtime != null) append(" · ")
                            if (runtime != null) append(runtime)
                        },
                        style = mr(10.5f, 400),
                        color = if (watching) accent else palette.sub2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
