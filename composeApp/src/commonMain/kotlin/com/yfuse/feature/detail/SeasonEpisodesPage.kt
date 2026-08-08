package com.yfuse.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PlatformBackHandler
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.heroScrim
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.model.Episode
import com.yfuse.core.network.EmbyImages

/**
 * 查看全部 — one season, every episode, laid out to be read rather than skimmed.
 *
 * The rail on the detail page is a shelf: four thumbnails, a title each, one line of
 * synopsis. That is the right shape for "what's next" and the wrong one for "which episode
 * was the one with the tunnel" — which needs the synopses long enough to recognise and
 * stacked so the eye runs down them.
 *
 * A layer over the detail page rather than a pushed route. The page it covers is the one
 * that owns this season, its artwork and its state; a route would mean the same
 * configuration threaded through three navigation stacks (库 / 首页 / 搜索 each own a
 * detail child) to show a list the detail store has already loaded. 添加服务器 in 我的 is
 * modal for the same reason. System back closes it, so it behaves like a page regardless.
 */
@Composable
internal fun SeasonEpisodesPage(
    seasonLabel: String,
    seriesName: String,
    episodes: List<Episode>,
    heroUrls: List<String?>,
    baseUrl: String,
    accessToken: String,
    accent: Color,
    currentEpisodeId: String?,
    onPlayEpisode: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    PlatformBackHandler(enabled = true, onBack = onDismiss)

    Box(Modifier.fillMaxSize().background(palette.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Dimens.contentBottom),
        ) {
            item(key = "season-hero") {
                Box(Modifier.fillMaxWidth().height(268.dp)) {
                    FallbackImage(
                        urls = heroUrls,
                        contentDescription = seriesName,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(heroScrim(palette.background)))
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = Dimens.pageHorizontal)
                            .padding(bottom = 18.dp),
                    ) {
                        Text(seasonLabel, style = sc(26f, 800), color = palette.text, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            seriesName,
                            style = sc(13f, 500),
                            color = palette.sub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "${episodes.size} 剧集",
                            style = mr(12f, 600),
                            color = palette.sub2,
                            maxLines = 1,
                        )
                    }
                }
            }

            itemsIndexed(
                episodes,
                key = { index, episode -> "all-ep-${episode.id}-$index" },
            ) { _, episode ->
                EpisodeRow(
                    episode = episode,
                    baseUrl = baseUrl,
                    accessToken = accessToken,
                    accent = accent,
                    current = episode.id == currentEpisodeId,
                    onPlay = { onPlayEpisode(episode) },
                    modifier = Modifier.padding(
                        horizontal = Dimens.pageHorizontal,
                        vertical = 7.dp,
                    ),
                )
            }
        }

        // Same chip as the detail page's, in the same corner, so backing out of this reads
        // as one gesture rather than two different ones a screen apart.
        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = Dimens.pageHorizontal, top = 10.dp)
                .size(34.dp)
                .pressable(onClick = onDismiss)
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = palette.text,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    baseUrl: String,
    accessToken: String,
    accent: Color,
    current: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onPlay)
            .clip(GlassShapes.card)
            .then(
                if (current) {
                    Modifier.border(1.5.dp, accent, GlassShapes.card)
                } else {
                    Modifier
                }
            )
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(148.dp).height(84.dp)) {
            Poster(
                url = EmbyImages.primary(
                    baseUrl,
                    episode.id,
                    episode.primaryTag,
                    maxHeight = 240,
                    accessToken = accessToken,
                ),
                shape = GlassShapes.thumb,
                progress = episode.playedPercentage?.let { (it / 100.0).toFloat() },
                modifier = Modifier.fillMaxSize(),
            )
            // Watched and part-watched are different states and only one of them has a
            // number: a check for "done", the time left for "you stopped here".
            if (episode.played) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Brand.Online),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = "已观看",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                }
            } else {
                episode.remainingLabel()?.let { remaining ->
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(GlassShapes.chip)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            AppIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(8.dp),
                        )
                        Text(remaining, style = mr(9.5f, 600), color = Color.White, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "E$it." }, episode.name)
                    .joinToString(" "),
                style = sc(13f, 700),
                color = if (current) accent else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val facts = listOfNotNull(
                episode.runtimeMinutes?.let { "$it 分钟" },
                episode.premiereDate,
            )
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    facts.joinToString(" · "),
                    style = mr(10f, 400),
                    color = palette.sub2,
                    maxLines = 1,
                )
            }
            if (!episode.overview.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    episode.overview,
                    style = mr(10.5f, 400, lineHeight = 10.5f * 1.55f),
                    color = palette.sub,
                    // Three lines: enough to recognise an episode by, short enough that
                    // ten of them still scan as a list.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `20:01` — how much of this episode is left, for something already started. */
private fun Episode.remainingLabel(): String? {
    val runtimeMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L } ?: return null
    val watchedMs = resumePositionTicks?.takeIf { it > 0 }?.let { it / 10_000L } ?: return null
    val leftMs = (runtimeMs - watchedMs).takeIf { it > 0 } ?: return null
    val totalSeconds = leftMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
