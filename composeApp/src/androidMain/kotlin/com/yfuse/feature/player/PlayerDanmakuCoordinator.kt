package com.yfuse.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.data.DanmakuBinding
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFilter
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuMedia
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.DanmakuRepository
import com.yfuse.core.data.DanmakuSource
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.data.MAX_DANMAKU_SYNC_BLOCKED_WORDS
import com.yfuse.core.data.activeOr
import com.yfuse.core.data.danmakuBindingKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
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
    /**
     * 弹幕热度 of the comments on screen, for the curve over the progress bar: a reader, so the rail
     * picks it up while drawing, and null until a match has loaded.
     */
    val heat: () -> DanmakuHeat? = { null },
    /** 点弹幕's 屏蔽此词 and 屏蔽同类: puts an entry on the block list, the one list both use. */
    val onBlock: (String) -> DanmakuBlockOutcome = { DanmakuBlockOutcome.AlreadyBlocked },
    /** 撤销 for [onBlock]: takes the entry it added off again. */
    val onUnblock: (String) -> Unit = {},
)

@Composable
internal fun rememberPlayerDanmakuController(
    currentItem: PlayerMediaItem?,
    positionMs: () -> Long,
    preferences: DanmakuPreferences,
    repository: DanmakuRepository,
    /** Where the 弹幕 key's tap says what it came to: [danmakuKeyToast]'s line, as a toast. */
    onNotice: (String) -> Unit = {},
): PlayerDanmakuController {
    val scope = rememberCoroutineScope()
    val latestNotice by rememberUpdatedState(onNotice)
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
    // Asks the loaded episode for its comments again, behind what is on screen, once a line is sent.
    var refreshes by remember { mutableIntStateOf(0) }
    var episodeId by remember { mutableStateOf<String?>(null) }
    // The source that episode is on: a pinned match need not be on the active one.
    var episodeSource by remember { mutableStateOf<DanmakuSource?>(null) }
    // Lines sent from here to that episode, shown as soon as the server takes them and kept over a
    // refetch that does not carry them yet.
    var sent by remember { mutableStateOf(emptyList<DanmakuComment>()) }
    // A tap on the 弹幕 key that switched it on, waiting for the load it started to say how it went.
    var keyNoticeArmed by remember { mutableStateOf(false) }
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
        sent = emptyList()
        error = null
        sendError = null
        match = null
        episodeId = null
        episodeSource = null
        loading = false
        val item = currentItem
        val activeSource = source
        if (item == null || !enabled || activeSource == null) {
            // Nothing loads, so a waiting tap has nothing to report, now or for a later load.
            keyNoticeArmed = false
            return@LaunchedEffect
        }
        // The source answered with no episode for this one, as opposed to not answering at all.
        var unmatched = false
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
                    episodeSource = pinned
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
                                    unmatched = true
                                    Result.failure(
                                        IllegalStateException("没有匹配到弹幕，可用搜索手动选择"),
                                    )
                                } else {
                                    match = episode.label
                                    episodeId = episode.episodeId
                                    episodeSource = activeSource
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
        if (keyNoticeArmed) {
            keyNoticeArmed = false
            latestNotice(danmakuKeyToast(enabled = true, count = comments.size, error = error, unmatched = unmatched))
        }
        val loadedSource = episodeSource ?: return@LaunchedEffect
        val loadedEpisode = episodeId ?: return@LaunchedEffect
        // A line that went through asks for the episode again. The comments on screen keep running
        // until the answer replaces them in one step, and a refetch that fails leaves them be.
        snapshotFlow { refreshes }.drop(1).collectLatest {
            repository.loadEpisode(loadedSource, loadedEpisode).onSuccess { refreshed ->
                comments = refreshed
                error = null
            }
        }
    }

    val visibleComments =
        remember(comments, sent, mergeDuplicates, blockedWords) {
            // A line sent from here stands on its own rather than being folded into a 合并重复 run,
            // where it would never be seen to arrive.
            DanmakuFilter
                .apply(comments.withoutEchoesOf(sent), mergeDuplicates, blockedWords)
                .withSentLines(DanmakuFilter.apply(sent, merge = false, blockedWords = blockedWords))
        }
    // Counted from what is shown — blocked words left out, a merged line counting for all it
    // stands for — once per load rather than per frame; the reader stays one instance, so the
    // controls it is handed to never see a new parameter when a match arrives.
    val heat = remember(visibleComments) { danmakuHeatOf(visibleComments) }
    val latestHeat = rememberUpdatedState(heat)
    val heatReader = remember { { latestHeat.value } }
    val actions =
        DanmakuPanelActions(
            onToggle = { preferences.setEnabled(!enabled) },
            onKeyToggle = {
                if (enabled) {
                    // Off is said at once, and a tap still waiting on a load is dropped.
                    keyNoticeArmed = false
                    preferences.setEnabled(false)
                    latestNotice(danmakuKeyToast(enabled = false))
                } else {
                    // On is said once the load the switch starts has come back.
                    keyNoticeArmed = true
                    preferences.setEnabled(true)
                }
            },
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
                val target = episodeSource
                val targetEpisode = episodeId
                when {
                    // One line at a time: a second press while one is on its way is not a second line.
                    sending -> Unit

                    // Said rather than ignored: 发送弹幕 stays up until it hears how the send went.
                    target == null || targetEpisode == null -> sendError = "弹幕源未就绪，暂时无法发送"

                    else -> {
                        val capturedPosition = positionMs()
                        sending = true
                        sendError = null
                        scope.launch {
                            try {
                                repository
                                    .send(
                                        source = target,
                                        episodeId = targetEpisode,
                                        text = text,
                                        positionMs = capturedPosition,
                                    ).fold(
                                        onSuccess = {
                                            // Onto the episode it went to only; the player may have
                                            // moved on while it was being sent.
                                            if (episodeId == targetEpisode && episodeSource == target) {
                                                val line = DanmakuComment(capturedPosition, text.trim())
                                                sent = sent.withSentLines(listOf(line))
                                                refreshes++
                                            }
                                        },
                                        onFailure = { sendError = it.message ?: "发送失败" },
                                    )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                sendError = "发送失败"
                            } finally {
                                sending = false
                            }
                        }
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
        heat = heatReader,
        onBlock = { entry ->
            val before = preferences.blockedWords.value
            preferences.addBlockedWord(entry)
            val after = preferences.blockedWords.value
            when {
                after.size > before.size -> DanmakuBlockOutcome.Added(after.last())
                before.size >= MAX_DANMAKU_SYNC_BLOCKED_WORDS -> DanmakuBlockOutcome.ListFull
                else -> DanmakuBlockOutcome.AlreadyBlocked
            }
        },
        onUnblock = preferences::removeBlockedWord,
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
                canSend = episodeSource?.supportsSearch == true && episodeId != null,
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

/** The 弹幕 key's notices, one at a time: a new line takes the place of the one still showing. */
internal class DanmakuKeyToastSlot {
    fun show(
        @Suppress("UNUSED_PARAMETER") context: Context,
        message: String,
    ) {
        PlayerNotices.show(message)
    }
}

/** How far apart a line sent from here and the server's copy of it can be and still be one line. */
private const val SENT_ECHO_WINDOW_MS = 1_000L

/**
 * The time-sorted [this] without the server's copies of [sent]. The line already on screen stays
 * the one drawn: the copy comes back with its time rounded, as a comment starting over elsewhere.
 */
internal fun List<DanmakuComment>.withoutEchoesOf(sent: List<DanmakuComment>): List<DanmakuComment> {
    if (sent.isEmpty() || isEmpty()) return this
    val echoes = HashSet<Int>()
    sent.forEach { line ->
        var index = lowerBoundDanmaku(this, line.timeMs - SENT_ECHO_WINDOW_MS)
        while (index < size && this[index].timeMs <= line.timeMs + SENT_ECHO_WINDOW_MS) {
            if (index !in echoes && this[index].text == line.text) {
                echoes += index
                break
            }
            index++
        }
    }
    return if (echoes.isEmpty()) this else filterIndexed { index, _ -> index !in echoes }
}

/** The time-sorted [lines] slotted into the time-sorted [this], each after what shares its moment. */
internal fun List<DanmakuComment>.withSentLines(lines: List<DanmakuComment>): List<DanmakuComment> {
    if (lines.isEmpty()) return this
    val merged = ArrayList<DanmakuComment>(size + lines.size)
    var next = 0
    lines.forEach { line ->
        while (next < size && this[next].timeMs <= line.timeMs) merged += this[next++]
        merged += line
    }
    while (next < size) merged += this[next++]
    return merged
}
