package com.yfuse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.app.TabBarInset
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.PredictiveBackOverlay
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.TmdbImages

/**
 * Smallest poster column this grid will draw.
 *
 * Chosen so a 360dp phone still lays out three across, while a tablet or an unfolded
 * device spends its extra width on more posters instead of three stretched ones.
 */
private val PosterMinWidth = 94.dp

/**
 * 全部 — one home shelf, opened out into a grid.
 *
 * The chip beside every shelf used to navigate to the 库 tab. For 继续观看 that is at least
 * arguable — those are the user's own files. For 热门 / 正在上映 / 即将上映 it was simply
 * wrong: those come from TMDB and most of them are not in the library at all, so the chip
 * promised "more of this" and delivered a different screen showing none of it.
 *
 * A layer over the home page rather than a pushed route, for the reason 查看全部 on the
 * detail page is one: the shelf's items are already loaded and already in the home store,
 * and a route would mean threading the same list through the navigation stack to show
 * something the page above it is already holding. The home page remains composed below this
 * layer, so predictive back can reveal that exact page while the grid follows the finger.
 */
@Composable
internal fun TmdbRowPage(
    title: String,
    items: List<TmdbItem>,
    showReleaseDate: Boolean,
    onOpen: (TmdbItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current

    PredictiveBackOverlay(onBack = onDismiss) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.pageHorizontal)
                        .padding(top = Dimens.contentTop, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        AppIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = palette.text,
                        modifier = Modifier
                            .size(36.dp)
                            .pressable(onClick = onDismiss)
                            .solidGlass(CircleShape, palette.card2, palette.border)
                            .padding(10.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(title, style = sc(17f, 800), color = palette.text)
                        Spacer(Modifier.height(2.dp))
                        Text("${items.size} 部", style = mr(10f, 400), color = palette.sub2)
                    }
                }

                LazyVerticalGrid(
                    // Three across on a phone, more on anything wider — see [PosterMinWidth].
                    columns = GridCells.Adaptive(PosterMinWidth),
                    contentPadding = PaddingValues(
                        start = Dimens.pageHorizontal,
                        end = Dimens.pageHorizontal,
                        bottom = TabBarInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = { "${it.mediaType}:${it.id}" }) { item ->
                        CaptionedPoster(
                            url = TmdbImages.poster(item.posterPath),
                            fallbackUrls = listOfNotNull(
                                TmdbImages.media(item.posterPath),
                                TmdbImages.poster(item.posterPath, "original"),
                                TmdbImages.media(item.posterPath, "original"),
                                TmdbImages.backdrop(item.backdropPath, "w780"),
                                TmdbImages.media(item.backdropPath, "w780"),
                            ),
                            title = item.title,
                            year = if (showReleaseDate) {
                                item.releaseDate?.let { "上映 $it" } ?: "上映日期待定"
                            } else {
                                item.year
                            },
                            // The same title can sit in two shelves at once, and this page is
                            // opened from one of them; a shared element would compete with the
                            // shelf poster still mounted underneath.
                            sharedKey = null,
                            onClick = { onOpen(item) },
                            modifier = Modifier.fillMaxWidth(),
                            posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                        )
                    }
                }
            }
        }
    }
}
