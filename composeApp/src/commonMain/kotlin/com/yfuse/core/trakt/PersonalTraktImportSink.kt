package com.yfuse.core.trakt

import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import java.time.Instant

class PersonalTraktImportSink(
    private val personal: PersonalLibraryRepository,
) : TraktImportSink {
    override suspend fun importWatchlist(item: TraktListItem): Boolean =
        item.toPersonalMedia()?.let(personal::importWatchLater) ?: false

    override suspend fun importHistory(item: TraktListItem): Boolean {
        val media = item.toPersonalMedia() ?: return false
        val watchedAt =
            item.watchedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        return personal.importWatched(media, watchedAt)
    }
}

internal fun TraktListItem.toPersonalMedia(): PersonalMediaRef? =
    when (type) {
        "movie" ->
            movie?.let { media ->
                media.ids.key()?.let {
                    PersonalMediaRef(
                        it,
                        media.title.take(512),
                        "Movie",
                        media.ids.tmdb,
                        media.year,
                    )
                }
            }
        "show" ->
            show?.let { media ->
                media.ids.key()?.let {
                    PersonalMediaRef(
                        it,
                        media.title.take(512),
                        "Series",
                        media.ids.tmdb,
                        media.year,
                    )
                }
            }
        "episode" ->
            episode?.let { ep ->
                val seriesKey = show?.ids?.key()
                val key =
                    if (seriesKey != null &&
                        ep.season >= 0 &&
                        ep.number > 0
                    ) {
                        "$seriesKey/s${ep.season}e${ep.number}"
                    } else {
                        ep.ids.key()
                    }
                key?.let {
                    PersonalMediaRef(
                        it,
                        "${show?.title.orEmpty()} · S${ep.season}E${ep.number} ${ep.title}".take(512),
                        "Episode",
                        ep.ids.tmdb,
                        show?.year,
                    )
                }
            }
        else -> null
    }

private fun TraktIds.key(): String? =
    when {
        (tmdb ?: 0) > 0 -> "tmdb:$tmdb"
        (tvdb ?: 0) > 0 -> "tvdb:$tvdb"
        imdb?.matches(Regex("tt[0-9]+")) == true -> "imdb:$imdb"
        (trakt ?: 0) > 0 -> "trakt:$trakt"
        else -> null
    }
