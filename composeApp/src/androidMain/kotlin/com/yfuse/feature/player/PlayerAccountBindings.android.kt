package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.trakt.TraktRepository
import com.yfuse.core.trakt.traktPlaybackMedia
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.feature.handoff.HandoffPlaybackBinding
import com.yfuse.feature.trakt.TraktPlaybackReportingEffect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext

/** Account integrations read the mounted item; they never start playback during composition. */
@Composable
internal fun PlayerAccountBindings(
    item: PlayerMediaItem?,
    player: YPlayer,
    playback: StateFlow<PlaybackState>,
    casting: Boolean,
    handoffAllowed: Boolean,
    personal: PersonalLibraryRepository?,
    ownerToken: String?,
    subtitleOffsetMs: Long,
    secondarySubtitleOffsetMs: Long,
    audioOffsetMs: Long,
) {
    val registry = remember { GlobalContext.get().getOrNull<HandoffPlaybackRegistry>() }
    val trakt = remember { GlobalContext.get().getOrNull<TraktRepository>() }
    val latestItem by rememberUpdatedState(item)
    val latestCasting by rememberUpdatedState(casting)
    val latestHandoffAllowed by rememberUpdatedState(handoffAllowed)
    val transport by remember(player) {
        player.state.map { Triple(it.phase, it.playing, it.buffering) }.distinctUntilChanged()
    }.collectAsState(Triple(player.state.value.phase, player.state.value.playing, player.state.value.buffering))
    // What a handoff receiver needs while it waits for the first frame. Constant once playing, so an
    // ordinary session does not recompose on every buffer tick.
    val startup by remember(player) {
        player.state.map(::handoffStartupEvidence).distinctUntilChanged()
    }.collectAsState(handoffStartupEvidence(player.state.value))
    var pausedOwner by remember { mutableStateOf<SubtitleItemKey?>(null) }
    var resumeAfterFailure by remember { mutableStateOf(false) }
    val reportSession =
        remember(item?.subtitleItemKey(), item?.playSessionId) {
            item?.playSessionId?.takeIf(String::isNotBlank) ?: java.util.UUID
                .randomUUID()
                .toString()
        }
    if (trakt != null) {
        TraktPlaybackReportingEffect(
            repository = trakt,
            media = item?.let { traktPlaybackMedia(it.mediaType, it.providerIds) },
            playbackSessionId = reportSession,
            playing = transport.second && !transport.third && !casting,
            positionMs = { player.currentPositionMs() },
            durationMs = { player.state.value.durationMs },
            completed = transport.first == YPlaybackPhase.Ended,
        )
    }
    if (registry != null) {
        fun snapshot() =
            latestItem
                ?.takeIf {
                    latestHandoffAllowed &&
                        !latestCasting &&
                        (
                            personal == null ||
                                (
                                    personal.scopeToken == ownerToken &&
                                        it.serverId?.let(personal::canAccessServer) != false
                                )
                        )
                }?.handoffMedia(
                    state = playback.value,
                    positionMs = player.currentPositionMs(),
                    profileId = personal?.policy?.value?.profileId,
                    subtitleOffsetMs = subtitleOffsetMs,
                    secondarySubtitleOffsetMs = secondarySubtitleOffsetMs,
                    audioOffsetMs = audioOffsetMs,
                )
        HandoffPlaybackBinding(
            registry = registry,
            snapshot = ::snapshot,
            pauseAndSnapshot = {
                snapshot()?.also {
                    pausedOwner = latestItem?.subtitleItemKey()
                    resumeAfterFailure = player.playbackRequested
                    player.pause()
                }
            },
            resumeSource = {
                val sameSource = pausedOwner != null && pausedOwner == latestItem?.subtitleItemKey()
                if (sameSource && resumeAfterFailure && snapshot() != null) player.play()
                pausedOwner = null
                resumeAfterFailure = false
            },
            ready = transport.first == YPlaybackPhase.Ready,
            playing = transport.second && !transport.third,
            failed = transport.first == YPlaybackPhase.Failed,
            progress = startup.progress,
            networkBitsPerSecond = startup.networkBitsPerSecond,
            sourceBitsPerSecond = startup.sourceBitsPerSecond,
        )
    }
    LaunchedEffect(player, item?.subtitleItemKey(), item?.playSessionId, personal, ownerToken) {
        val media = item?.personalMediaRef() ?: return@LaunchedEffect
        val history = personal ?: return@LaunchedEffect
        val owner = ownerToken ?: return@LaunchedEffect
        var started = false
        var wasPlaying = false
        var wasEnded = false
        var lastWriteMs = 0L
        var last = player.state.value

        fun record() {
            if (started) {
                history.recordHistory(
                    media,
                    last.positionMs,
                    last.durationMs,
                    last.phase == YPlaybackPhase.Ended,
                    owner,
                )
            }
        }
        try {
            player.state.collect { state ->
                last = state
                val playing = state.playing && !state.buffering
                if (playing) started = true
                val now = android.os.SystemClock.elapsedRealtime()
                val ended = state.phase == YPlaybackPhase.Ended
                if (started && (playing != wasPlaying || ended != wasEnded || !ended && now - lastWriteMs >= 15_000L)) {
                    record()
                    lastWriteMs = now
                }
                wasPlaying = playing
                wasEnded = ended
            }
        } finally {
            record()
        }
    }
}

private data class HandoffStartupEvidence(
    val progress: Long = 0L,
    val networkBitsPerSecond: Long = 0L,
    val sourceBitsPerSecond: Long = 0L,
)

private fun handoffStartupEvidence(state: YPlayerState): HandoffStartupEvidence =
    if (state.playing && !state.buffering) {
        HandoffStartupEvidence()
    } else {
        HandoffStartupEvidence(
            progress =
                listOf(
                    state.phase.ordinal.toLong(),
                    state.bufferedPositionMs,
                    state.diagnostics.sourceBufferedMs,
                    state.diagnostics.sourceQueueBytes,
                ).hashCode().toLong(),
            networkBitsPerSecond = state.diagnostics.networkBitsPerSecond,
            sourceBitsPerSecond = state.diagnostics.bitrateBitsPerSecond,
        )
    }
