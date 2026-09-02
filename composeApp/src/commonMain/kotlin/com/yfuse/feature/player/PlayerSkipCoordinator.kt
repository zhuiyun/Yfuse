package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.SkipTimes
import com.yfuse.core.model.PlaybackSegmentType
import kotlinx.coroutines.delay

/** Long enough to cancel an automatic skip without making an accepted skip feel sluggish. */
private const val AUTO_SKIP_COUNTDOWN_SECONDS = 5
private const val AUTO_SKIP_COUNTDOWN_TICK_MS = 100L

internal data class PlayerSkipController(
    val state: SkipSegmentState,
    val actions: SkipSegmentActions,
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
    playbackState: PlaybackState,
    preferences: SkipSegmentPreferences,
    playbackGate: WatchGatedPlayback,
    watchGuest: Boolean,
): PlayerSkipController {
    val timesBySeries by preferences.bySeries.collectAsState()
    val mode by preferences.skipMode.collectAsState()
    val legacySeriesId = currentItem?.seriesId?.takeIf(::skipSegmentsAvailableFor)
    val skipSeriesKey =
        currentItem?.seriesKey
            ?: skipSeriesStorageKey(
                serverId = currentItem?.serverId,
                seriesId = legacySeriesId,
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
    LaunchedEffect(skipSeriesKey, storedTimes, legacyEntry, playbackState.durationMs) {
        val key = skipSeriesKey ?: return@LaunchedEffect
        if (storedTimes == null && legacyTimes != null) {
            preferences.set(key, legacyTimes)
            legacyEntry?.first?.let(preferences::clear)
        }
        preferences.migrateLegacyCredits(key, playbackState.durationMs)
    }
    // Credits are stored as a distance back from the end, so duration participates in the key.
    val activeSegment =
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
        }.firstOrNull { segment ->
            segment.contains(playbackState.positionMs, playbackState.durationMs)
        }
    val skipSegment: () -> Unit = {
        when (activeSegment?.type) {
            PlaybackSegmentType.Intro -> activeSegment.endMs?.let(playbackGate::seekTo)
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
    var lastOutsideCreditsPositionMs by remember(currentItem?.id) { mutableStateOf<Long?>(null) }
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    val occurrence = activeSegment?.let { segment -> currentItem?.id?.let { it to segment.type } }
    LaunchedEffect(
        currentItem?.id,
        activeSegment?.type,
        playbackState.playing,
        playbackState.buffering,
        playbackState.positionMs,
    ) {
        if (currentItem == null) return@LaunchedEffect
        val playbackReady = playbackState.playing && !playbackState.buffering
        if (
            observedForwardPlaybackOutsideCredits(
                previousPositionMs = lastOutsideCreditsPositionMs,
                positionMs = playbackState.positionMs,
                segmentType = activeSegment?.type,
                playbackReady = playbackReady,
            )
        ) {
            creditsEnteredFromPlayback = true
        }
        if (playbackReady && activeSegment?.type != PlaybackSegmentType.Credits) {
            lastOutsideCreditsPositionMs = playbackState.positionMs
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
        state =
            SkipSegmentState(
                segmentLabel =
                    activeSegment
                        ?.type
                        ?.skipLabel
                        ?.takeIf { mode != SkipMode.Off },
                countdownSeconds = countdownSeconds,
                seriesName =
                    skipSeriesKey?.let {
                        currentItem?.seriesName?.ifBlank { null } ?: "本剧"
                    },
                introStartSeconds = times?.introStartSeconds ?: 0L,
                introEndSeconds = times?.introEndSeconds ?: 0L,
                creditsLeadSeconds = times?.effectiveCreditsLeadSeconds(playbackState.durationMs) ?: 0L,
                mode = mode,
            ),
        actions =
            SkipSegmentActions(
                onSkip = skipSegment,
                onCancelAuto = { settled.value = occurrence },
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
                                ),
                        )
                        legacyEntry?.first?.let(preferences::clear)
                    }
                },
                onSelectMode = preferences::setSkipMode,
            ),
    )
}
