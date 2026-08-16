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

internal data class PlayerSkipController(
    val state: SkipSegmentState,
    val actions: SkipSegmentActions,
)

/** Movies have no series id, so intro/outro controls and automatic skipping are episode-only. */
internal fun skipSegmentsAvailableFor(seriesId: String?): Boolean = !seriesId.isNullOrBlank()

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
    val skipSeriesId = currentItem?.seriesId?.takeIf(::skipSegmentsAvailableFor)
    val times = skipSeriesId?.let(timesBySeries::get)
    // Credits are stored as a distance back from the end, so duration participates in the key.
    val activeSegment =
        remember(currentItem, skipSeriesId, timesBySeries, playbackState.durationMs) {
            if (skipSeriesId == null) {
                emptyList()
            } else {
                preferences.applyTo(
                    seriesId = skipSeriesId,
                    serverSegments = currentItem.playbackSegments,
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
    var countdownSeconds by remember { mutableStateOf<Int?>(null) }
    val occurrence = activeSegment?.let { segment -> currentItem?.id?.let { it to segment.type } }
    val armed =
        occurrence != null &&
            mode == SkipMode.Auto &&
            !watchGuest &&
            occurrence != settled.value
    val latestSkipSegment by rememberUpdatedState(skipSegment)
    LaunchedEffect(occurrence, armed) {
        if (!armed) {
            countdownSeconds = null
            return@LaunchedEffect
        }
        for (remaining in AUTO_SKIP_COUNTDOWN_SECONDS downTo 1) {
            countdownSeconds = remaining
            delay(1_000L)
        }
        countdownSeconds = null
        settled.value = occurrence
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
                    skipSeriesId?.let {
                        currentItem.seriesName?.ifBlank { null } ?: "本剧"
                    },
                introStartSeconds = times?.introStartSeconds ?: 0L,
                introEndSeconds = times?.introEndSeconds ?: 0L,
                creditsLeadSeconds = times?.creditsLeadSeconds ?: 0L,
                mode = mode,
            ),
        actions =
            SkipSegmentActions(
                onSkip = skipSegment,
                onCancelAuto = { settled.value = occurrence },
                onSetTimes = { introStart, introEnd, creditsLead ->
                    val seriesId = currentItem?.seriesId?.takeIf(::skipSegmentsAvailableFor)
                    if (seriesId != null) {
                        preferences.set(
                            seriesId = seriesId,
                            times =
                                SkipTimes(
                                    introStartSeconds = introStart,
                                    introEndSeconds = introEnd,
                                    creditsLeadSeconds = creditsLead,
                                    seriesName = currentItem.seriesName.orEmpty(),
                                ),
                        )
                    }
                },
                onSelectMode = preferences::setSkipMode,
            ),
    )
}
