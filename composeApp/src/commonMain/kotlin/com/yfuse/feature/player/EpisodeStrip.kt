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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.EpisodeGridColors
import com.yfuse.core.designsystem.EpisodeNumberCell
import com.yfuse.core.designsystem.EpisodeNumberGrid
import com.yfuse.core.designsystem.FallbackImage
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.model.prefersEpisodeGrid
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** One card in the strip. A projection of [PlayerMediaItem], so the strip needs nothing else. */
internal data class EpisodeCard(
    val title: String,
    val caption: String?,
    val stillUrl: String?,
    /** Series poster, used only after the episode still is absent or fails to load. */
    val posterUrl: String?,
    val progress: Float?,
    /** Cross-server identities are also used by the room playlist to jump into this queue. */
    val watchKey: String,
    val watchMatchKeys: List<String>,
    /** The episode's number, which the 选集 grid shows; null where the server gave none. */
    val number: Int? = null,
)

/** Finished episodes read 1 in [EpisodeCard.progress]: the queue marks them played that way. */
private const val WATCHED_PROGRESS = 0.995f

/** The 选集 grid's cells for the strip's cards: a number each, in queue order. */
internal fun List<EpisodeCard>.toNumberCells(currentIndex: Int): List<EpisodeNumberCell> =
    mapIndexed { index, card ->
        val progress = card.progress
        EpisodeNumberCell(
            key = card.watchKey.ifBlank { "episode-$index" },
            number = card.number ?: (index + 1),
            watched = progress != null && progress >= WATCHED_PROGRESS,
            progress = progress?.takeIf { it < WATCHED_PROGRESS },
            current = index == currentIndex,
        )
    }

internal fun List<PlayerMediaItem>.toEpisodeCards(): List<EpisodeCard> =
    mapIndexed { index, item ->
        EpisodeCard(
            title = item.title.ifBlank { "第 ${index + 1} 集" },
            caption = item.caption,
            stillUrl = item.stillUrl,
            posterUrl = item.posterUrl,
            progress = item.progress?.takeIf { it > 0.01f },
            watchKey = item.watchKey,
            watchMatchKeys = item.matchKeys,
            number = item.episodeNumber,
        )
    }

/** Keep the episode still first; the series poster is a real artwork fallback, not a placeholder. */
internal fun EpisodeCard.artworkUrls(): List<String?> = listOf(stillUrl, posterUrl)

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
    /** A remote drives the player: the number grid hands focus to what is playing as it opens. */
    takeFocus: Boolean = false,
) {
    val listState = rememberLazyListState()
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    LaunchedEffect(currentIndex) {
        runCatching { listState.scrollToItem(currentIndex) }
    }
    // A long season — most 短剧 run past a hundred episodes — opens on the number grid; stills
    // stay a tap away for the viewer who picks by picture.
    val gridFits = prefersEpisodeGrid(episodes.size)
    var showGrid by remember(gridFits) { mutableStateOf(gridFits) }
    val gridMaxHeight = if (rememberUprightPhoneWindow()) UprightGridMaxHeight else LandscapeGridMaxHeight

    Column(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    0f,
                    0f to Color.Black.copy(alpha = 0.86f),
                    1f to Color.Transparent,
                ),
            ).padding(top = 14.dp, bottom = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("剧集列表", style = AppTypography.body.strong, color = Color.White)
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (gridFits) {
                    Text(
                        if (showGrid) "剧照" else "集数",
                        style = AppTypography.caption.strong,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier =
                            Modifier
                                .pressable(
                                    onClickLabel = if (showGrid) "按剧照选集" else "按集数选集",
                                    onClick = { showGrid = !showGrid },
                                ).touchTarget(),
                    )
                }
                Icon(
                    AppIcons.Close,
                    contentDescription = "关闭",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.noRippleClickable(onDismiss).size(11.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (showGrid) {
            EpisodeNumberGrid(
                cells = remember(episodes, currentIndex) { episodes.toNumberCells(currentIndex) },
                onPick = onSelect,
                colors = EpisodeGridColors.overPicture(),
                focusCurrent = takeFocus,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = gridMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp),
            )
        } else {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                motionItemsIndexed(
                    items = episodes,
                    key = { index, episode -> episode.watchKey.ifBlank { "episode-$index" } },
                ) { index, episode ->
                    EpisodeStripCard(
                        episode = episode,
                        current = index == currentIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

/**
 * A tab of thirty in 48dp rows: six rows of five on an upright phone, three of ten or more across a
 * landscape frame, which keeps its upper half clear. A narrower frame scrolls the rest.
 */
private val UprightGridMaxHeight = 300.dp
private val LandscapeGridMaxHeight = 150.dp

@Composable
private fun EpisodeStripCard(
    episode: EpisodeCard,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(
        modifier
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
                urls = episode.artworkUrls(),
                contentDescription = episode.title,
                modifier = Modifier.fillMaxWidth().height(79.dp),
                fitNarrow = true,
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
                    Text("正在播放", style = AppTypography.caption.strong, color = Color.White)
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
                            .background(accent.accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            episode.title,
            style = if (current) AppTypography.caption.strong else AppTypography.caption.medium,
            color = if (current) Color.White else Color.White.copy(alpha = 0.82f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.caption?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = AppTypography.caption.regular, color = Color.White.copy(alpha = 0.45f), maxLines = 1)
        }
    }
}
