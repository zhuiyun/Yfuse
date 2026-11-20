package com.yfuse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.CaptionedPoster
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.core.designsystem.MediaSharedElementKey
import com.yfuse.core.designsystem.MediaSizing
import com.yfuse.core.designsystem.motionItems
import com.yfuse.core.designsystem.sharedMediaOnClick
import com.yfuse.core.network.EmbyImages
import com.yfuse.feature.library.libraryHomeCopy
import com.yfuse.feature.library.libraryHomeSharedKey

internal val LocalShelfPosterCopy = staticCompositionLocalOf<MutableState<String?>?> { null }

@Composable
internal fun LibraryMediaShelf(
    title: String,
    items: List<HomeResumeEntry>,
    onSeeAll: () -> Unit,
    onClick: (HomeResumeEntry) -> Unit,
    liftMenu: (HomeResumeEntry) -> LiftMenu,
    place: String = "home:library:$title",
) {
    Column {
        HomeShelfHeader(title = title, source = "媒体库", onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            motionItems(items.take(12), key = { "library-${it.server.id}-${it.item.id}" }) { entry ->
                val item = entry.item
                val selection = LocalShelfPosterCopy.current
                val artworkKey = MediaSharedElementKey(entry.server.id, item.id)
                val open =
                    sharedMediaOnClick(artworkKey) {
                        selection?.value = libraryHomeCopy(entry.server.id, place, item.id)
                        onClick(entry)
                    }
                CaptionedPoster(
                    url =
                        EmbyImages.poster(
                            entry.server.baseUrl,
                            item,
                            accessToken = entry.server.accessToken,
                        ),
                    blurHash = item.posterBlurHash,
                    title = item.title,
                    rating = item.communityRating,
                    year =
                        listOfNotNull(
                            item.year?.toString(),
                            entry.server.serverName.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                    onClick = open,
                    sharedTransitionKey =
                        selection?.let {
                            libraryHomeSharedKey(
                                entry.server.id,
                                place,
                                item.id,
                                it.value,
                            )
                        } ?: artworkKey,
                    liftMenu = { liftMenu(entry) },
                    modifier = Modifier.width(MediaSizing.posterRailWidth),
                    posterModifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                )
            }
        }
    }
}
