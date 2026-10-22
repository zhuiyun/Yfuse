package com.yfuse.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaRating
import com.yfuse.core.model.RatingSource
import com.yfuse.core.model.displayRatings
import com.yfuse.core.model.validRatings
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext

/** Optional enrichment never holds up detail content/playback and cancels when identity changes. */
@Composable
fun rememberRatedDetail(
    detail: MediaDetail?,
    serverId: String?,
): MediaDetail? {
    var extra by remember(detail?.id, serverId, detail?.providerIds) { mutableStateOf<MediaRating?>(null) }
    val ratings = detail?.displayRatings().orEmpty()
    LaunchedEffect(detail?.id, serverId, detail?.providerIds, ratings) {
        extra = null
        if (detail == null || ratings.any { it.source == RatingSource.Tmdb }) return@LaunchedEffect
        val type =
            when (detail.type) {
                "Movie" -> "movie"
                "Series" -> "tv"
                else -> return@LaunchedEffect
            }
        val id =
            detail.providerIds.entries.firstOrNull { it.key.equals("tmdb", true) }?.value?.toIntOrNull()?.takeIf {
                it >
                    0
            }
                ?: return@LaunchedEffect
        val repository = runCatching { GlobalContext.get().get<TmdbRepository>() }.getOrNull() ?: return@LaunchedEffect
        extra = withTimeoutOrNull(4_000) { repository.rating(type, id).getOrNull() }
    }
    return detail?.copy(ratings = (ratings + listOfNotNull(extra)).validRatings())
}
