package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.Person
import com.yfuse.core.model.Season
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.network.TmdbImages
import com.yfuse.core.sync.playback.PlaybackStateRecord
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.playbackIdentity
import com.yfuse.core.widget.widgetResumePositionMs

internal fun TmdbDetail.catalogDetail(context: WidgetSourceContext): MediaDetail =
    MediaDetail(
        id = context.playbackIdentity(item).mediaKey,
        title = item.title,
        type = if (item.mediaType in setOf("tv", "series")) "Series" else "Movie",
        seriesId = null,
        overview = item.overview,
        year = item.year?.take(4)?.toIntOrNull(),
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        officialRating = null,
        communityRating = item.rating,
        posterItemId = context.playbackIdentity(item).mediaKey,
        posterTag = TmdbImages.poster(item.posterPath ?: context.media.poster),
        backdropItemId = context.playbackIdentity(item).mediaKey,
        backdropTag = TmdbImages.backdrop(item.backdropPath ?: context.media.backdrop),
        resumePositionTicks = null,
        people = cast.map { Person("", it.name, it.role, TmdbImages.poster(it.profilePath)) },
        providerIds =
            item.id
                .takeIf { it > 0 }
                ?.let { mapOf("Tmdb" to it.toString()) }
                .orEmpty(),
    )

internal fun TmdbDetail.catalogSeasons(): List<Season> =
    seasons
        .map { Season(it.number.toString(), it.name.ifBlank { "第 ${it.number} 季" }, it.number, null) }
        .ifEmpty { (1..(numberOfSeasons ?: 0).coerceIn(0, 500)).map { Season(it.toString(), "第 $it 季", it, null) } }

internal fun Episode.withCatalogProgress(record: PlaybackStateRecord?): Episode =
    copy(
        played = record?.played == true,
        resumePositionTicks = record.widgetResumePositionMs().catalogTicks().takeIf { it > 0 },
        playedPercentage =
            record
                ?.takeIf { it.durationMs > 0 && !it.played }
                ?.let { (it.positionMs.toDouble() * 100 / it.durationMs).coerceIn(0.0, 100.0) },
    )

internal fun Long.catalogTicks(): Long = coerceIn(0L, Long.MAX_VALUE / 10_000L) * 10_000L

/** Catalog updates never replace a library selection which has already resolved. */
internal fun DetailState.withCatalogSnapshot(snapshot: DetailState): DetailState {
    val metadata = if (server == null) snapshot.detail else detail
    val common =
        copy(
            detail = metadata,
            sources = if (server == null) snapshot.sources else sources,
            sourcesLoading = if (server == null) snapshot.sourcesLoading else sourcesLoading,
            sourcesError = if (server == null) snapshot.sourcesError else sourcesError,
            loading = if (server == null) false else loading,
            error = if (server == null) null else error,
        )
    if (server != null && !snapshot.catalogPlayback) return common
    return common.copy(
        catalogPlayback = true,
        playTarget = snapshot.playTarget,
        playServer = null,
        playSourceDetail = snapshot.detail,
        playPositionTicks = snapshot.playPositionTicks,
        seasons = snapshot.seasons,
        selectedSeasonId = snapshot.selectedSeasonId,
        listedSeasonId = snapshot.listedSeasonId,
        episodes = snapshot.episodes,
        episodesLoading = snapshot.episodesLoading,
        selectedEpisodeId = snapshot.selectedEpisodeId,
        selectedSourceServerId = null,
        selectedSourceItemId = null,
        selectedVersionId = null,
        selectionLoading = selectionLoading && !snapshot.catalogPlayback,
        resolvingPlay = snapshot.resolvingPlay,
    )
}
