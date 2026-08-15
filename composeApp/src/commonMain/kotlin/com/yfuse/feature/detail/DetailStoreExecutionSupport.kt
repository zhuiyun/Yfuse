package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException

internal fun Throwable.toOrganizationMessage(fallback: String): String {
    val denied = (this as? EmbyErrorException)?.error as? EmbyError.AccessDenied
    return if (denied != null && denied.provider == null) {
        "当前账号没有权限使用此合集或播放列表"
    } else {
        toUserMessage(fallback)
    }
}

internal sealed interface DetailAction {
    data object Load : DetailAction
}

internal data class ResolvedPlaybackSelection(
    val server: SavedServer,
    val sourceDetail: MediaDetail,
    val target: MediaDetail,
    val positionTicks: Long,
    val seasons: List<Season>? = null,
    val selectedSeasonId: String? = null,
    val episodes: List<Episode>? = null,
)

internal data class EpisodeCoordinate(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

internal const val SOURCE_SELECTION_MAX_ATTEMPTS = 3
internal const val SOURCE_SELECTION_RETRY_BASE_DELAY_MS = 250L
internal const val SOURCE_SELECTION_TIMEOUT_MS = 45_000L

internal sealed interface DetailMsg {
    data object Loading : DetailMsg

    data class Loaded(
        val detail: MediaDetail,
        val server: SavedServer,
    ) : DetailMsg

    data class Failed(
        val message: String,
    ) : DetailMsg

    data class Resolving(
        val value: Boolean,
    ) : DetailMsg

    data class SelectionLoading(
        val value: Boolean,
    ) : DetailMsg

    data class VersionSelected(
        val versionId: String,
    ) : DetailMsg

    data class EpisodeSelected(
        val itemId: String,
    ) : DetailMsg

    data class SeasonsLoaded(
        val seasons: List<Season>,
        val selected: String?,
    ) : DetailMsg

    data object EpisodesLoading : DetailMsg

    data object EpisodesLoadingFinished : DetailMsg

    data class EpisodesLoaded(
        val episodes: List<Episode>,
    ) : DetailMsg

    data class SourcesLoaded(
        val sources: List<ServerSource>,
    ) : DetailMsg

    data class RelatedLoaded(
        val items: List<MediaItem>,
    ) : DetailMsg

    data class FavoriteChanged(
        val serverId: String,
        val itemId: String,
        val value: Boolean,
    ) : DetailMsg

    data class PlayedChanged(
        val serverId: String,
        val itemId: String,
        val value: Boolean,
    ) : DetailMsg

    data class ActionMessage(
        val value: String?,
    ) : DetailMsg

    data class SourceFailure(
        val value: SourceSelectionFailure?,
    ) : DetailMsg

    data class PlaybackSelectionLoaded(
        val server: SavedServer,
        val sourceDetail: MediaDetail,
        val target: MediaDetail,
        val positionTicks: Long,
        val seasons: List<Season>? = null,
        val selectedSeasonId: String? = null,
        val episodes: List<Episode>? = null,
        val preferredVersionId: String? = null,
    ) : DetailMsg

    data class AudioLanguageSelected(
        val language: String?,
    ) : DetailMsg

    data class SubtitleLanguageSelected(
        val language: String?,
    ) : DetailMsg

    data object OrganizationLoading : DetailMsg

    data class OrganizationLoaded(
        val containers: List<MediaContainer>,
    ) : DetailMsg

    data class OrganizationLoadFailed(
        val message: String,
    ) : DetailMsg

    data class OrganizationAdding(
        val containerId: String,
    ) : DetailMsg

    data class OrganizationAdded(
        val containerId: String,
    ) : DetailMsg

    data class OrganizationAddFailed(
        val containerId: String,
        val message: String,
    ) : DetailMsg
}

internal fun Throwable.isTransientSourceFailure(): Boolean =
    when (val error = (this as? EmbyErrorException)?.error) {
        EmbyError.Network -> true
        is EmbyError.Server -> error.code in 500..599
        else -> false
    }

internal suspend inline fun <T> cancellableResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
