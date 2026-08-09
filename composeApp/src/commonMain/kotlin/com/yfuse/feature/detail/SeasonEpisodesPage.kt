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
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.PredictiveBackOverlay
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
 * detail child) to show a list the detail store has already loaded. The detail page stays
 * composed underneath, so predictive back reveals the exact detail state while this page
 * follows the gesture instead of exposing the activity backdrop.
 */
@Composable
internal fun SeasonEpisodesPage(
    seasonLabel: String,
    seriesName: String,
    episodes: List<Episode>,
    heroUrls: List<String?>,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
    accent: Color,
    currentEpisodeId: String?,
    onPlayEpisode: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current

    PredictiveBackOverlay(onBack = onDismiss) {
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
                        seriesPosterUrl = seriesPosterUrl,
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
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    baseUrl: String,
    accessToken: String,
    seriesPosterUrl: String?,
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
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(122.dp)
                .height(69.dp)
                .clip(GlassShapes.chip)
                .background(palette.card2),
        ) {
            val episodeUrl = EmbyImages.primary(
                baseUrl = baseUrl,
                itemId = episode.id,
                tag = episode.primaryTag,
                maxHeight = 240,
                accessToken = accessToken,
            )
            val imageUrl = episodeUrl ?: seriesPosterUrl
            if (imageUrl != null) {
                FallbackImage(
                    urls = listOf(episodeUrl, seriesPosterUrl),
                    contentDescription = episode.name,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Poster(
                    url = null,
                    contentDescription = episode.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (current) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text("当前", style = sc(9f, 700), color = Color.White)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
        ) {
            Text(
                buildString {
                    episode.indexNumber?.let { append("第 $it 集") }
                    if (episode.name.isNotBlank()) {
                        if (isNotEmpty()) append("  ")
                        append(episode.name)
                    }
                }.ifBlank { "剧集" },
                style = sc(13f, 700),
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(4.dp))
                Text(
                    overview,
                    style = mr(10.5f, 400, lineHeight = 15f),
                    color = palette.sub,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            AppIcons.Play,
            contentDescription = "播放",
            tint = if (current) accent else Brand.Primary,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(18.dp),
        )
    }
}
