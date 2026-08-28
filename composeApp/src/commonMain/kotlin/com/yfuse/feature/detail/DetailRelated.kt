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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.Poster
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.EmbyImages

@Composable
internal fun RelatedSection(
    baseUrl: String,
    accessToken: String,
    serverId: String?,
    items: List<MediaItem>,
    accent: Color,
    onOpen: (String) -> Unit,
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
            itemsIndexed(
                items,
                key = { index, item -> "related-${item.id}-$index" },
            ) { _, item ->
                val sharedKey = MediaSharedElementKey(serverId, item.id)
                Column(
                    Modifier
                        .width(96.dp)
                        .pressable(
                            onClick = sharedMediaOnClick(sharedKey) { onOpen(item.id) },
                        ),
                ) {
                    Poster(
                        url =
                            EmbyImages.primary(
                                baseUrl = baseUrl,
                                itemId = item.posterItemId,
                                tag = item.posterTag,
                                maxHeight = 480,
                                accessToken = accessToken,
                            ),
                        shape = GlassShapes.poster,
                        rating = item.communityRating,
                        sharedTransitionKey = sharedKey,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
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

// ---------------------------------------------------------------- scroll plumbing

/** Section blocks share one horizontal inset and one vertical rhythm (§8.4 大区块间距). */
internal fun Modifier.sectionPadding(): Modifier =
    this.padding(horizontal = Dimens.pageHorizontal).padding(top = Dimens.sectionGap)

/** Pixels of the hero that have scrolled past the top edge. */
