package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftAnchor
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.SectionHeader
import com.yfuse.core.designsystem.liftAnchor
import com.yfuse.core.designsystem.liftable
import com.yfuse.core.designsystem.motionItemsIndexed
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.library.favoriteLiftAction
import com.yfuse.feature.library.liftRemainingLabel
import com.yfuse.feature.library.mediaItemLiftMenu
import com.yfuse.feature.library.playedLiftAction
import com.yfuse.core.designsystem.ThemeText as Text

@Composable
internal fun RelatedSection(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    items: List<MediaItem>,
    accent: Color,
    onOpen: (String) -> Unit,
    liftMenu: ((MediaItem) -> LiftMenu)? = null,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(top = Dimens.sectionGap)) {
        SectionHeader(
            title = "相关推荐",
            modifier = Modifier.padding(horizontal = Dimens.pageHorizontal),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
        ) {
            motionItemsIndexed(
                items,
                key = { index, item -> "related-${item.id}-$index" },
            ) { _, item ->
                val sharedKey = MediaSharedElementKey(serverId, item.id)
                val open = sharedMediaOnClick(sharedKey) { onOpen(item.id) }
                val artwork = remember { LiftAnchor() }
                val posterUrl =
                    EmbyImages.primary(
                        baseUrl = baseUrl,
                        itemId = item.posterItemId,
                        tag = item.posterTag,
                        maxHeight = 480,
                        accessToken = accessToken,
                    )
                Column(
                    Modifier
                        .width(96.dp)
                        .liftable(
                            menu = liftMenu?.let { build -> { build(item).withArtwork(listOfNotNull(posterUrl)) } },
                            anchor = artwork,
                            onOpen = open,
                        ).pressable(onClick = open),
                ) {
                    Poster(
                        url = posterUrl,
                        shape = AppShapes.card,
                        rating = item.communityRating,
                        sharedTransitionKey = sharedKey,
                        modifier = Modifier.liftAnchor(artwork).fillMaxWidth().height(140.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.year?.toString().orEmpty(),
                        style = AppTypography.caption.strong,
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 浮起菜单 on a 相关推荐 poster: play it (a series opens its page instead, where its episode is
 * resolved), and the two flags. The flags are remembered by [DetailComponent.relatedFlags].
 */
internal fun DetailComponent.relatedLiftMenu(
    serverId: String,
    listed: MediaItem,
    backdropUrl: String?,
): LiftMenu {
    val item = relatedFlags.current(serverId, listed)
    val resumeTicks = item.resumePositionTicks?.takeIf { it > 0L && !item.played }
    return mediaItemLiftMenu(
        item = item,
        backdropUrl = backdropUrl,
        onOpen = { onOpenRelated(serverId, item.id) },
        actions =
            listOf(
                if (item.type == "Series") {
                    emptyList()
                } else {
                    listOfNotNull(
                        ItemAction(
                            label = if (resumeTicks != null) "继续播放" else "播放",
                            icon = AppIcons.Play,
                            detail = item.liftRemainingLabel(),
                            leavesPage = true,
                            onSelect = { playRelated(serverId, item.id, resumeTicks ?: 0L) },
                        ),
                        resumeTicks?.let {
                            ItemAction(
                                label = "从头播放",
                                icon = AppIcons.Refresh,
                                leavesPage = true,
                                onSelect = { playRelated(serverId, item.id, 0L) },
                            )
                        },
                    )
                },
                listOf(
                    playedLiftAction(item.played) { relatedFlags.setPlayed(serverId, listed, it) },
                    favoriteLiftAction(item.isFavorite) { relatedFlags.setFavorite(serverId, listed, it) },
                ),
            ),
    )
}

// ---------------------------------------------------------------- scroll plumbing

/** Section blocks share one horizontal inset and one vertical rhythm (§8.4 大区块间距). */
internal fun Modifier.sectionPadding(): Modifier =
    this.padding(horizontal = Dimens.pageHorizontal).padding(top = Dimens.sectionGap)

/** Pixels of the hero that have scrolled past the top edge. */
