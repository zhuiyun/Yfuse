package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yfuse.core.data.DanmakuBinding
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFilter
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuMedia
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.activeOr
import com.yfuse.core.data.danmakuBindingKey
import kotlinx.coroutines.launch

/**
 * Player-facing output of the danmaku feature.
 *
 * Loading, matching, search and preference mutations stay behind this boundary so the player
 * composition only observes the values needed by the overlay and controls.
 */
internal data class PlayerDanmakuController(
    val visibleComments: List<DanmakuComment>,
    val enabled: Boolean,
    val displayArea: DanmakuDisplayArea,
    val fontSize: DanmakuFontSize,
    val speed: DanmakuSpeed,
    val opacity: DanmakuOpacity,
    val panelState: DanmakuPanelState,
    val actions: DanmakuPanelActions,
)

@Composable
internal fun rememberPlayerDanmakuController(
    currentItem: PlayerMediaItem?,
    positionMs: Long,
    preferences: DanmakuPreferences,
    repository: DanmakuRepository,
): PlayerDanmakuController {
    val scope = rememberCoroutineScope()
    val sources by preferences.sources.collectAsState()
    val activeSourceId by preferences.activeSourceId.collectAsState()
    val bindings by preferences.bindings.collectAsState()
    val enabled by preferences.enabled.collectAsState()
    val area by preferences.displayArea.collectAsState()
    val font by preferences.fontSize.collectAsState()
    val speed by preferences.speed.collectAsState()
    val opacity by preferences.opacity.collectAsState()
    val mergeDuplicates by preferences.mergeDuplicates.collectAsState()
    val blockedWords by preferences.blockedWords.collectAsState()
    val recentSearches by preferences.recentSearches.collectAsState()
    var comments by remember { mutableStateOf(emptyList<DanmakuComment>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var match by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf(DanmakuSearchState()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var episodeId by remember { mutableStateOf<String?>(null) }
    val source = sources.activeOr(activeSourceId)

    // Keyed on the show and its coordinate rather than the library's item id, so a match
    // made on one server still holds on another — see danmakuBindingKey.
    val bindingKey =
        currentItem?.let { item ->
            danmakuBindingKey(
                itemId = item.id,
                title = item.title,
                seriesName = item.seriesName,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
            )
        }
    val binding =
        bindingKey
            ?.let { key -> bindings[key] ?: currentItem.id.let(bindings::get) }
            ?.takeIf { candidate -> sources.any { it.id == candidate.sourceId } }

    LaunchedEffect(currentItem?.id, source, binding, enabled, reloads) {
        comments = emptyList()
        error = null
        match = null
        episodeId = null
        loading = false
        val item = currentItem ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        val activeSource = source ?: return@LaunchedEffect
        val media =
            DanmakuMedia(
                id = item.id,
                title = item.title,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                serverId = item.serverId,
            )

        loading = true
        val loaded =
            when {
                binding != null -> {
                    match = binding.label
                    episodeId = binding.episodeId
                    val pinned = sources.first { it.id == binding.sourceId }
                    repository.loadEpisode(pinned, binding.episodeId)
                }

                activeSource.isTemplate -> repository.load(activeSource.url, media)

                else ->
                    repository
                        .match(
                            source = activeSource,
                            media =
                                media.copy(
                                    title = item.seriesName?.takeIf { it.isNotBlank() } ?: item.title,
                                ),
                        ).fold(
                            onSuccess = { episode ->
                                if (episode == null) {
                                    Result.failure(
                                        IllegalStateException("没有匹配到弹幕，可用搜索手动选择"),
                                    )
                                } else {
                                    match = episode.label
                                    episodeId = episode.episodeId
                                    repository.loadEpisode(activeSource, episode.episodeId)
                                }
                            },
                            onFailure = { Result.failure(it) },
                        )
            }
        loaded.fold(
            onSuccess = { comments = it },
            onFailure = { error = it.message ?: "弹幕加载失败" },
        )
        loading = false
    }

    val visibleComments =
        remember(comments, mergeDuplicates, blockedWords) {
            DanmakuFilter.apply(comments, mergeDuplicates, blockedWords)
        }
    val actions =
        DanmakuPanelActions(
            onToggle = { preferences.setEnabled(!enabled) },
            onSelectArea = { index ->
                preferences.setDisplayArea(DanmakuDisplayArea.entries[index])
            },
            onSelectFont = { index -> preferences.setFontSize(DanmakuFontSize.entries[index]) },
            onSelectSpeed = { index -> preferences.setSpeed(DanmakuSpeed.entries[index]) },
            onSelectOpacity = { index -> preferences.setOpacity(DanmakuOpacity.entries[index]) },
            onSelectSource = { id ->
                preferences.selectSource(id)
                search = DanmakuSearchState(query = search.query)
            },
            onOpenSearch = {
                if (search.query.isBlank()) {
                    val seed =
                        currentItem?.let { item ->
                            item.seriesName?.takeIf { it.isNotBlank() } ?: item.title
                        }
                    search = search.copy(query = seed.orEmpty())
                }
            },
            onQueryChange = { search = search.copy(query = it) },
            onSubmitSearch = {
                val keyword = search.query.trim()
                if (source != null && keyword.isNotEmpty()) {
                    search =
                        search.copy(
                            running = true,
                            error = null,
                            openResult = null,
                            episodes = emptyList(),
                        )
                    scope.launch {
                        preferences.rememberSearch(keyword)
                        repository.search(source, keyword).fold(
                            onSuccess = { results ->
                                search =
                                    search.copy(
                                        running = false,
                                        results = results,
                                        searched = true,
                                    )
                            },
                            onFailure = { failure ->
                                search =
                                    search.copy(
                                        running = false,
                                        results = emptyList(),
                                        error = failure.message ?: "搜索失败",
                                    )
                            },
                        )
                    }
                }
            },
            onOpenResult = { result ->
                source?.let { activeSource ->
                    search =
                        search.copy(
                            openResult = result,
                            episodes = emptyList(),
                            running = true,
                            error = null,
                        )
                    scope.launch {
                        repository.episodes(activeSource, result).fold(
                            onSuccess = { episodes ->
                                search = search.copy(running = false, episodes = episodes)
                            },
                            onFailure = { failure ->
                                search =
                                    search.copy(
                                        running = false,
                                        error = failure.message ?: "读取剧集失败",
                                    )
                            },
                        )
                    }
                }
            },
            onBackToResults = {
                search =
                    search.copy(
                        openResult = null,
                        episodes = emptyList(),
                        error = null,
                    )
            },
            onPickEpisode = { episode ->
                val item = currentItem
                if (item != null && source != null) {
                    preferences.bind(
                        itemId =
                            danmakuBindingKey(
                                itemId = item.id,
                                title = item.title,
                                seriesName = item.seriesName,
                                seasonNumber = item.seasonNumber,
                                episodeNumber = item.episodeNumber,
                            ),
                        binding =
                            DanmakuBinding(
                                sourceId = source.id,
                                episodeId = episode.episodeId,
                                label = episode.label,
                            ),
                    )
                    if (!enabled) preferences.setEnabled(true)
                }
            },
            onToggleMerge = { preferences.setMergeDuplicates(!mergeDuplicates) },
            onRetry = { reloads++ },
            onSend = { text ->
                val activeSource = source
                val activeEpisodeId = episodeId
                if (activeSource != null && activeEpisodeId != null) {
                    sending = true
                    sendError = null
                    scope.launch {
                        repository
                            .send(
                                source = activeSource,
                                episodeId = activeEpisodeId,
                                text = text,
                                positionMs = positionMs,
                            ).fold(
                                onSuccess = {
                                    sending = false
                                    reloads++
                                },
                                onFailure = {
                                    sending = false
                                    sendError = it.message ?: "发送失败"
                                },
                            )
                    }
                }
            },
            onClearMatch = {
                bindingKey?.let(preferences::unbind)
                currentItem?.id?.let(preferences::unbind)
            },
        )

    return PlayerDanmakuController(
        visibleComments = visibleComments,
        enabled = enabled,
        displayArea = area,
        fontSize = font,
        speed = speed,
        opacity = opacity,
        panelState =
            DanmakuPanelState(
                sources = sources,
                activeSourceId = activeSourceId,
                enabled = enabled,
                count = comments.size,
                loading = loading,
                error = error,
                matchLabel = match,
                matchPinned = binding != null,
                mergeDuplicates = mergeDuplicates,
                canSend = source?.supportsSearch == true && episodeId != null,
                sending = sending,
                sendError = sendError,
                areaOptions = DanmakuDisplayArea.entries.map { it.label to (it == area) },
                fontOptions = DanmakuFontSize.entries.map { it.label to (it == font) },
                speedOptions = DanmakuSpeed.entries.map { it.label to (it == speed) },
                opacityOptions = DanmakuOpacity.entries.map { it.label to (it == opacity) },
                search = search.copy(recent = recentSearches),
            ),
        actions = actions,
    )
}
