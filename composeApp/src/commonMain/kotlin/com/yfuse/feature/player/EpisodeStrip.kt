package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc

/** One card in the strip. A projection of [PlayerMediaItem], so the strip needs nothing else. */
internal data class EpisodeCard(
    val title: String,
    val caption: String?,
    val stillUrl: String?,
    val progress: Float?,
)

internal fun List<PlayerMediaItem>.toEpisodeCards(): List<EpisodeCard> = mapIndexed { index, item ->
    EpisodeCard(
        title = item.title.ifBlank { "第 ${index + 1} 集" },
        caption = item.caption,
        stillUrl = item.stillUrl,
        progress = item.progress?.takeIf { it > 0.01f },
    )
}

/**
 * 剧集列表 — a strip of stills along the bottom, in front of the picture.
 *
 * This used to be a 190dp column of text down the right-hand edge with an empty grey tile
 * where each thumbnail should have been: the queue was built from a list query and nobody
 * had carried the image tag along, so the slot existed and was never filled. Text alone is
 * the wrong shape for this list — episodes are told apart by what they look like far faster
 * than by "第 4 集", and a season of twenty in a vertical column means scrolling past the
 * picture to read them.
 *
 * Along the bottom, because that is where the rest of the chrome already is and because a
 * horizontal strip covers a band of the frame rather than a third of it. It opens scrolled
 * to what is playing — the reason it is opened at all is nearly always "what's next".
 */
@Composable
internal fun EpisodeStrip(
    episodes: List<EpisodeCard>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        runCatching { listState.scrollToItem(currentIndex) }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    0f,
                    0f to Color.Black.copy(alpha = 0.86f),
                    1f to Color.Transparent,
                ),
            )
            .padding(top = 14.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("剧集列表", style = sc(12.5f, 700), color = Color.White)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(11.dp).noRippleClickable(onDismiss),
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(episodes) { index, episode ->
                EpisodeStripCard(
                    episode = episode,
                    current = index == currentIndex,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeStripCard(
    episode: EpisodeCard,
    current: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(140.dp)
            .noRippleClickable(onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(79.dp)
                .clip(GlassShapes.thumb)
                .background(PlayerTokens.drawerFill),
        ) {
            FallbackImage(
                urls = listOf(episode.stillUrl),
                contentDescription = episode.title,
                modifier = Modifier.fillMaxWidth().height(79.dp),
            )
            if (current) {
                // The current card is named rather than only outlined: an outline on a
                // still is easy to lose against a bright frame.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(79.dp)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("正在播放", style = sc(10.5f, 700), color = Color.White)
                }
            }
            episode.progress?.let { progress ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Brand.PrimaryGradTop),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            episode.title,
            style = sc(10.5f, if (current) 700 else 600),
            color = if (current) Color.White else Color.White.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.caption?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = mr(9f, 400), color = Color.White.copy(alpha = 0.45f), maxLines = 1)
        }
    }
}
