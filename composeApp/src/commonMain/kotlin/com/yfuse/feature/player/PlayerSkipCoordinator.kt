package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import com.yfuse.core.model.isShortRuntime
import kotlinx.coroutines.delay

/** Long enough to cancel an automatic skip without making an accepted skip feel sluggish. */
private const val AUTO_SKIP_COUNTDOWN_SECONDS = 5
private const val AUTO_SKIP_COUNTDOWN_TICK_MS = 100L

/**
 * An intro or recap this short is skipped the moment it is reached instead of counted down: five
 * seconds of a ten-second recap were watched before its skip, and one of five or less never skipped.
 */
private const val AUTO_SKIP_DIRECT_MAX_MS = 15_000L

/** An intro the file opens on, reached within this of its start: where 连播 lands. */
private const val AUTO_SKIP_OPENING_GRACE_MS = 3_000L

/** How long 已跳过片头 · 撤销 offers the way back. */
private const val SKIP_UNDO_WINDOW_MS = 5_000L

/**
 * Whether the automatic skip of [segment], reached at [positionMs] in a file of [durationMs], goes
 * at once — with 撤销 on the notice after it — instead of after the countdown: an intro of fifteen
 * seconds or less, any intro of an episode under five minutes, and an intro the episode opens on,
 * so that 连播 begins the next episode where its intro ends. Credits keep the countdown; their
 * skip leaves the episode, which 撤销 could not take back.
 */
internal fun skipsWithoutCountdown(
    segment: PlaybackSegment?,
    positionMs: Long,
    durationMs: Long,
): Boolean {
    if (segment?.type != PlaybackSegmentType.Intro) return false
    val end = segment.endMs ?: return false
    if (end <= segment.startMs) return false
    val opening =
        segment.startMs <= AUTO_SKIP_OPENING_GRACE_MS &&
            positionMs - segment.startMs <= AUTO_SKIP_OPENING_GRACE_MS
    return opening || end - segment.startMs <= AUTO_SKIP_DIRECT_MAX_MS || isShortRuntime(durationMs) == true
}

/** What 撤销 puts back after a skip that did not count down. */
private data class SkipUndo(
    val label: String,
    val returnToMs: Long,
)

internal data class PlayerSkipController(
    val state: SkipSegmentState,
    val actions: SkipSegmentActions,
    val nextItemBoundaryMs: Long?,
)

/** Movies have no series id, so intro/outro controls and automatic skipping are episode-only. */
internal fun skipSegmentsAvailableFor(seriesId: String?): Boolean = !seriesId.isNullOrBlank()

/** Stable when provider metadata exists, server-scoped otherwise; never collides by raw id alone. */
internal fun skipSeriesStorageKey(
    serverId: String?,
    seriesId: String?,
    providerSeriesKey: String?,
): String? {
    val id = seriesId?.takeIf(String::isNotBlank) ?: return null
    val external =
        providerSeriesKey
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it.startsWith("emby:", ignoreCase = true) }
    return external?.let { "provider:$it" }
        ?: serverId
            ?.takeIf(String::isNotBlank)
            ?.let { "server:$it/series:$id" }
        ?: "series:$id"
}

/**
 * A resume position can already be inside the credits. Treating that as a newly reached segment
 * immediately selects the next episode; repeating the same rule on every resumed item can walk
 * the whole queue without the viewer watching anything. Credits therefore auto-skip only after
 * this playback session has first been observed outside them. Intro skipping remains available at
 * position zero, but neither segment starts its countdown while the engine is still buffering.
 */
internal fun canArmAutomaticSkip(
    segmentType: PlaybackSegmentType?,
    playbackReady: Boolean,
    creditsEnteredFromPlayback: Boolean,
): Boolean =
    playbackReady &&
        when (segmentType) {
            PlaybackSegmentType.Intro -> true
            PlaybackSegmentType.Credits -> creditsEnteredFromPlayback
            null -> false
        }

internal fun observedForwardPlaybackOutsideCredits(
    previousPositionMs: Long?,
    positionMs: Long,
    segmentType: PlaybackSegmentType?,
    playbackReady: Boolean,
): Boolean =
    playbackReady &&
        segmentType != PlaybackSegmentType.Credits &&
        previousPositionMs != null &&
        positionMs > previousPositionMs

/** Owns segment detection, the automatic countdown and the persisted per-series boundaries. */
@Composable
internal fun rememberPlayerSkipController(
    currentItem: PlayerMediaItem?,
    playback: State<PlaybackState>,
    preferences: SkipSegmentPreferences,
    playbackGate: WatchGatedPlayback,
    watchGuest: Boolean,
): PlayerSkipController {
    val playbackState by remember(playback) { derivedStateOf { playback.value.runtimeProjection() } }
    val timesBySeries by preferences.bySeries.collectAsState()
    val defaultMode by preferences.skipMode.collectAsState()
    val legacySeriesId = currentItem?.seriesId?.takeIf(::skipSegmentsAvailableFor)
    // A film has no series, so it keeps its own key: the server's credits marker and a
    // remembered 片尾 still apply, they just never spill over to another title.
    val standaloneKey = currentItem?.takeIf { legacySeriesId == null }?.id?.let { "item:$it" }
    val skipSeriesKey =
        currentItem?.seriesKey
            ?: skipSeriesStorageKey(
                serverId = currentItem?.serverId,
                seriesId = legacySeriesId ?: standaloneKey,
                providerSeriesKey = null,
            )
    val storedTimes = skipSeriesKey?.let(timesBySeries::get)
    val fallbackSeriesKeys =
        listOfNotNull(
            skipSeriesStorageKey(
                serverId = currentItem?.serverId,
                seriesId = legacySeriesId,
                providerSeriesKey = null,
            ),
            legacySeriesId,
        ).distinct().filterNot { it == skipSeriesKey }
    val legacyEntry =
        fallbackSeriesKeys.firstNotNullOfOrNull { key ->
            timesBySeries[key]?.let { key to it }
        }
    val legacyTimes = legacyEntry?.second
    val times = storedTimes ?: legacyTimes
    // This series' own 跳过方式 where it has one, else the default.
    val mode = times?.mode ?: defaultMode
    LaunchedEffect(skipSeriesKey, storedTimes, legacyEntry, playbackState.durationMs) {
        val key = skipSeriesKey ?: return@LaunchedEffect
        if (storedTimes == null && legacyTimes != null) {
            preferences.set(key, legacyTimes)
            legacyEntry?.first?.let(preferences::clear)
        }
        preferences.migrateLegacyCredits(key, playbackState.durationMs)
    }
    // Credits are stored as a distance back from the end, so duration participates in the key.
    val segments =
        remember(currentItem, skipSeriesKey, timesBySeries, playbackState.durationMs) {
            if (skipSeriesKey == null) {
                emptyList()
            } else {
                preferences.applyTo(
                    seriesId = skipSeriesKey,
                    serverSegments = currentItem?.playbackSegments.orEmpty(),
                    durationMs = playbackState.durationMs,
                )
            }
        }
    val activeSegment by remember(segments, playback) {
        derivedStateOf {
            playback.value.let { current ->
                segments.firstOrNull { it.contains(current.positionMs, current.durationMs) }
            }
        }
    }
    val skipSegment: () -> Unit = {
        when (activeSegment?.type) {
            PlaybackSegmentType.Intro -> activeSegment?.endMs?.let(playbackGate::seekTo)
            PlaybackSegmentType.Credits ->
                if (playbackState.hasNext) {
                    playbackGate.selectNext()
                } else {
                    playbackGate.seekTo((playbackState.durationMs - 500L).coerceAtLeast(0L))
                }
            null -> Unit
        }
    }

    // An occurrence stays settled after a cancel or skip, even if the viewer rewinds into it.
    val settled = remember { mutableStateOf<Pair<String, PlaybackSegmentType>?>(null) }
    var creditsEnteredFromPlayback by remember(currentItem?.id) { mutableStateOf(false) }
    var creditsPreloadCancelled by remember(currentItem?.id) { mutableStateOf(false) }
    var lastOutsideCreditsPositionMs by remember(currentItem?.id) { mutableStateOf<Long?>(null) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    var skipUndo by remember(currentItem?.id) { mutableStateOf<SkipUndo?>(null) }
    LaunchedEffect(skipUndo) {
        if (skipUndo == null) return@LaunchedEffect
        delay(SKIP_UNDO_WINDOW_MS)
        skipUndo = null
    }
    val occurrence = activeSegment?.let { segment -> currentItem?.id?.let { it to segment.type } }
    LaunchedEffect(currentItem?.id, segments, playback) {
        snapshotFlow { playback.value }.collect { current ->
            if (currentItem != null) {
                val active = segments.firstOrNull { it.contains(current.positionMs, current.durationMs) }
                val ready = current.playing && !current.buffering
                if (observedForwardPlaybackOutsideCredits(
                        previousPositionMs = lastOutsideCreditsPositionMs,
                        positionMs = current.positionMs,
                        segmentType = active?.type,
                        playbackReady = ready,
                    )
                ) {
                    creditsEnteredFromPlayback = true
                }
                if (ready &&
                    active?.type != PlaybackSegmentType.Credits
                ) {
                    lastOutsideCreditsPositionMs = current.positionMs
                }
            }
        }
    }

    val playbackReady = playbackState.playing && !playbackState.buffering
    var armedOccurrence by remember(currentItem?.id) {
        mutableStateOf<Pair<String, PlaybackSegmentType>?>(null)
    }
    val latestOccurrence by rememberUpdatedState(occurrence)
    val latestPlaybackReady by rememberUpdatedState(playbackReady)
    val latestMode by rememberUpdatedState(mode)
    val latestWatchGuest by rememberUpdatedState(watchGuest)
    val latestSkipSegment by rememberUpdatedState(skipSegment)

    // Once an intro/credits occurrence has armed, transient transport buffering must not disarm it.
    // Otherwise every short YCore Range stall cancels this effect and starts the 5-second countdown
    // from the beginning. Leaving the segment, changing mode, becoming a watch guest or settling the
    // occurrence still clears it immediately.
    LaunchedEffect(
        occurrence,
        mode,
        watchGuest,
        playbackReady,
        creditsEnteredFromPlayback,
        settled.value,
    ) {
        if (
            occurrence == null ||
            mode != SkipMode.Auto ||
            watchGuest ||
            occurrence == settled.value
        ) {
            armedOccurrence = null
            countdownSeconds = null
            return@LaunchedEffect
        }
        if (
            armedOccurrence != occurrence &&
            canArmAutomaticSkip(
                segmentType = activeSegment?.type,
                playbackReady = playbackReady,
                creditsEnteredFromPlayback = creditsEnteredFromPlayback,
            )
        ) {
            armedOccurrence = occurrence
        }
    }

    // Buffering/pausing freezes the remaining countdown instead of resetting it. The coroutine is
    // keyed only by the latched occurrence, so frequent position and buffering state updates cannot
    // recreate the timer.
    LaunchedEffect(armedOccurrence) {
        val armed = armedOccurrence
        if (armed == null) {
            countdownSeconds = null
            return@LaunchedEffect
        }
        val reached = activeSegment
        val now = playback.value
        if (skipsWithoutCountdown(reached, now.positionMs, now.durationMs)) {
            countdownSeconds = null
            settled.value = armed
            if (armedOccurrence == armed) armedOccurrence = null
            skipUndo =
                SkipUndo(
                    label = "已" + reached?.type?.skipLabel.orEmpty(),
                    returnToMs = now.positionMs,
                )
            latestSkipSegment()
            return@LaunchedEffect
        }
        var remainingMs = AUTO_SKIP_COUNTDOWN_SECONDS * 1_000L
        countdownSeconds = AUTO_SKIP_COUNTDOWN_SECONDS
        while (remainingMs > 0L) {
            if (
                latestOccurrence != armed ||
                latestMode != SkipMode.Auto ||
                latestWatchGuest ||
                settled.value == armed
            ) {
                countdownSeconds = null
                if (armedOccurrence == armed) armedOccurrence = null
                return@LaunchedEffect
            }
            if (!latestPlaybackReady) {
                delay(AUTO_SKIP_COUNTDOWN_TICK_MS)
                continue
            }
            delay(AUTO_SKIP_COUNTDOWN_TICK_MS)
            if (!latestPlaybackReady) continue
            remainingMs = (remainingMs - AUTO_SKIP_COUNTDOWN_TICK_MS).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                countdownSeconds =
                    ((remainingMs + 999L) / 1_000L)
                        .toInt()
                        .coerceAtLeast(1)
            }
        }
        countdownSeconds = null
        settled.value = armed
        if (armedOccurrence == armed) armedOccurrence = null
        latestSkipSegment()
    }

    return PlayerSkipController(
        nextItemBoundaryMs =
            nextItemCreditsBoundary(
                segments = segments,
                durationMs = playbackState.durationMs,
                mode = mode,
                cancelled = creditsPreloadCancelled,
                watchGuest = watchGuest,
            ),
        state =
            SkipSegmentState(
                segmentLabel =
                    activeSegment
                        ?.type
                        ?.skipLabel
                        ?.takeIf { mode != SkipMode.Off },
                countdownSeconds = countdownSeconds,
                undoLabel = skipUndo?.label,
                seriesName =
                    skipSeriesKey?.let {
                        currentItem?.seriesName?.ifBlank { null } ?: "本剧"
                    },
                introStartSeconds = times?.introStartSeconds ?: 0L,
                introEndSeconds = times?.introEndSeconds ?: 0L,
                creditsLeadSeconds = times?.effectiveCreditsLeadSeconds(playbackState.durationMs) ?: 0L,
                mode = mode,
                credits = creditsSegment(segments, playbackState.durationMs),
            ),
        actions =
            SkipSegmentActions(
                onSkip = skipSegment,
                onCancelAuto = {
                    settled.value = occurrence
                    if (occurrence?.second == PlaybackSegmentType.Credits) creditsPreloadCancelled = true
                },
                onSetTimes = { introStart, introEnd, creditsLead ->
                    val seriesKey = skipSeriesKey
                    if (seriesKey != null) {
                        preferences.set(
                            seriesId = seriesKey,
                            times =
                                SkipTimes(
                                    introStartSeconds = introStart,
                                    introEndSeconds = introEnd,
                                    creditsLeadSeconds = creditsLead,
                                    seriesName = currentItem?.seriesName.orEmpty(),
                                    // New times keep the series' own 跳过方式.
                                    mode = times?.mode,
                                ),
                        )
                        legacyEntry?.first?.let(preferences::clear)
                    }
                },
                // The panel holds this series' times, and its 跳过方式 is kept with them; a film
                // or an entry without a series changes the default.
                onSelectMode = { selected ->
                    val seriesKey = skipSeriesKey
                    if (seriesKey != null) {
                        preferences.setSeriesMode(seriesKey, selected, currentItem?.seriesName.orEmpty())
                    } else {
                        preferences.setSkipMode(selected)
                    }
                },
                onUndoSkip = {
                    skipUndo?.let { undo ->
                        skipUndo = null
                        playbackGate.seekTo(undo.returnToMs)
                    }
                },
            ),
    )
}

/**
 * Where [next]'s picture will be once its intro is skipped, or null when nothing will be skipped.
 * Uses the same series keys and server/custom precedence as the on-screen controls, so the warmed
 * position is the one the 跳过片头 button or countdown will actually seek to.
 */
internal fun nextItemIntroEndMs(
    next: PlayerMediaItem?,
    mode: SkipMode,
    timesBySeries: Map<String, SkipTimes>,
    preferences: SkipSegmentPreferences,
): Long? {
    if (next == null) return null
    val seriesId = next.seriesId?.takeIf(::skipSegmentsAvailableFor) ?: return null
    val candidates =
        listOfNotNull(
            next.seriesKey,
            skipSeriesStorageKey(serverId = next.serverId, seriesId = seriesId, providerSeriesKey = null),
            seriesId,
        ).distinct()
    val key = candidates.firstOrNull(timesBySeries::containsKey) ?: candidates.first()
    if ((timesBySeries[key]?.mode ?: mode) == SkipMode.Off) return null
    return preferences
        .applyTo(seriesId = key, serverSegments = next.playbackSegments, durationMs = 0L)
        .filter { it.type == PlaybackSegmentType.Intro }
        .mapNotNull { intro -> intro.endMs?.takeIf { it > intro.startMs && it > 0L } }
        .minOrNull()
}

/** Manual credits buttons can be pressed at entry; do not wait for the automatic countdown. */
internal fun nextItemCreditsBoundary(
    segments: List<PlaybackSegment>,
    durationMs: Long,
    mode: SkipMode,
    cancelled: Boolean,
    watchGuest: Boolean,
): Long? =
    if (mode == SkipMode.Off || cancelled || watchGuest) {
        null
    } else {
        segments
            .filter {
                it.type == PlaybackSegmentType.Credits &&
                    it.startMs > 0L &&
                    (durationMs <= 0L || it.startMs < durationMs)
            }.minOfOrNull { it.startMs }
    }
