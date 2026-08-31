package com.yfuse.feature.detail

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource

data class DetailState(
    val loading: Boolean = false,
    val detail: MediaDetail? = null,
    val server: SavedServer? = null,
    val resolvingPlay: Boolean = false,
    /** A newly selected resource/episode is being resolved into a concrete playable file. */
    val selectionLoading: Boolean = false,
    /**
     * Which of [playTarget]'s files plays. Null means "whatever the server lists first",
     * which is also what a library with a single file always resolves to.
     */
    val selectedVersionId: String? = null,
    /** Cards select on first tap; tapping the selected card again uses the main play target. */
    val selectedSourceServerId: String? = null,
    val selectedSourceItemId: String? = null,
    val selectedEpisodeId: String? = null,
    /**
     * The 音轨 / 字幕 to open with, as languages.
     *
     * Null means "whatever the file defaults to", which is what every entry starts as and
     * what most stay. See `PlaybackTrackRequest` for why these travel as languages and why
     * they are not part of the navigation config.
     */
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    /**
     * The entry 播放 would actually open, resolved at load rather than on the tap.
     *
     * For a film that is the item itself. For a series it is the 下一集 — a different item
     * with its own file, its own runtime and its own progress, none of which the series
     * carries. The page needs all three before anything is tapped: the button says what it
     * will play, 从头播放 only appears when there is something to rewind, and the 杜比 badge
     * describes a file, which a series does not have one of.
     */
    val playTarget: MediaDetail? = null,
    /** Server and root library item which own [playTarget]. */
    val playServer: SavedServer? = null,
    val playSourceDetail: MediaDetail? = null,
    /** Where [playTarget] would resume from, in Emby ticks. Zero for something unstarted. */
    val playPositionTicks: Long = 0L,
    val seasons: List<Season> = emptyList(),
    val selectedSeasonId: String? = null,
    val episodes: List<Episode> = emptyList(),
    val episodesLoading: Boolean = false,
    val progressManagerOpen: Boolean = false,
    val progressSelection: Set<String> = emptySet(),
    val progressSaving: Boolean = false,
    /** 跨服务器片源对比. */
    val sources: List<ServerSource> = emptyList(),
    val related: List<MediaItem> = emptyList(),
    val error: String? = null,
    val actionMessage: String? = null,
    /** Current route's optimistic/server-confirmed membership in Yfuse's 稍后观看 playlist. */
    val watchLater: Boolean = false,
    /** Initial membership lookup. This is background enrichment and must not look like a user sync. */
    val watchLaterLoading: Boolean = false,
    /** An explicit add/remove operation is still in flight. */
    val watchLaterMutating: Boolean = false,
    val sourceFailure: SourceSelectionFailure? = null,
    val organizationContainers: List<MediaContainer> = emptyList(),
    val organizationLoading: Boolean = false,
    val organizationError: String? = null,
    val addingContainerIds: Set<String> = emptySet(),
    val addedContainerIds: Set<String> = emptySet(),
) {
    /** Compatibility/readiness view used by store tests and non-visual consumers. */
    val watchLaterBusy: Boolean
        get() = watchLaterLoading || watchLaterMutating
}

sealed interface DetailIntent {
    data object Retry : DetailIntent

    /** The one-shot 提示 has been on screen long enough — see [ActionToast]. */
    data object DismissMessage : DetailIntent

    data object Play : DetailIntent

    data object ToggleFavorite : DetailIntent

    data object TogglePlayed : DetailIntent

    data object OpenProgressManager : DetailIntent

    data object CloseProgressManager : DetailIntent

    data class ToggleProgressEpisode(
        val episodeId: String,
    ) : DetailIntent

    data class SelectProgressEpisodes(
        val preset: EpisodeSelectionPreset,
    ) : DetailIntent

    data class ApplyEpisodeProgress(
        val action: EpisodeProgressAction,
    ) : DetailIntent

    data object ToggleWatchLater : DetailIntent

    data object LoadOrganizationContainers : DetailIntent

    data class AddToOrganizationContainer(
        val containerId: String,
    ) : DetailIntent

    /** 从头播放 — the same target as [Play], with the stored progress ignored. */
    data object PlayFromStart : DetailIntent

    data class SelectSource(
        val serverId: String,
        val itemId: String,
    ) : DetailIntent

    /** Picks one of the several files the server holds for this title. */
    data class SelectVersion(
        val versionId: String,
    ) : DetailIntent

    data class SelectSeason(
        val seasonId: String,
    ) : DetailIntent

    /** Null restores the file's own default track. */
    data class SelectAudioLanguage(
        val language: String?,
    ) : DetailIntent

    /** `PlaybackTrackRequest.SUBTITLES_OFF` starts with subtitles off. */
    data class SelectSubtitleLanguage(
        val language: String?,
    ) : DetailIntent

    data class SelectEpisode(
        val episodeId: String,
        val startPositionTicks: Long,
    ) : DetailIntent

    /** Mirrors episode/resource/version changes made inside the dedicated player. */
    data class SyncPlaybackSelection(
        val serverId: String?,
        val itemId: String?,
        val versionId: String?,
    ) : DetailIntent
}

enum class EpisodeSelectionPreset { All, Watched, Unwatched, Invert }

enum class EpisodeProgressAction { MarkWatched, MarkUnwatched, Reset }

sealed interface DetailLabel {
    /** Resolved playable target; the component turns this into navigation. */
    data class Play(
        val serverId: String,
        val itemId: String,
        val startPositionTicks: Long,
        /** Names one file when the item has several; null takes the server's first. */
        val mediaSourceId: String? = null,
    ) : DetailLabel
}
