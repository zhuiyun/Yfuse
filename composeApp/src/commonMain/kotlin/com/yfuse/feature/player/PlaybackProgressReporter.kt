package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import kotlin.math.abs
import kotlin.random.Random

private const val REPORT_INTERVAL_MS = 10_000L
private const val SEEK_THRESHOLD_MS = 5_000L
private const val TICKS_PER_MILLISECOND = 10_000L
private const val NEXT_SOURCE_PRELOAD_WINDOW_MS = 90_000L
private const val MAX_PENDING_REPORT_COMMANDS = 8
private const val DEFAULT_PLAY_METHOD = "DirectPlay"

/**
 * Serializes media-server and Yfuse playback events so item transitions always stop the old
 * session before starting the new one. Local cross-platform state is persisted before native
 * network reporting. Position updates are throttled to ten seconds, while pause/resume, seeks,
 * app-background, item changes and terminal state are immediate.
 */
internal class PlaybackProgressReporter(
    items: List<PlayerMediaItem>,
    private val sink: PlaybackEventSink,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sourcePreloader: PlaybackSourcePreloader? =
        runCatching {
            GlobalContext.get().get<PlaybackSourcePreloader>()
        }.getOrNull(),
    private val playbackSync: PlaybackSyncManager? =
        runCatching {
            GlobalContext.get().get<PlaybackSyncManager>()
        }.getOrNull(),
) {
    private sealed interface Command {
        data class Update(
            val state: PlaybackState,
        ) : Command

        data class Background(
            val state: PlaybackState,
        ) : Command

        data class Rebind(
            val items: List<PlayerMediaItem>,
            val state: PlaybackState,
        ) : Command

        data class Close(
            val state: PlaybackState,
        ) : Command
    }

    private var items = items
    private var observedBinding = items.reportingBinding()
    private val commandLock = Any()
    private val pendingCommands = ArrayDeque<Command>()
    private val commandWakeups = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var closed = false

    @Volatile
    private var latestState: PlaybackState? = null

    private var observedIndex = -1
    private var observedPlaying: Boolean? = null
    private var observedPositionMs = 0L
    private var enqueuedPositionMs = Long.MIN_VALUE
    private val preloadedSources = mutableSetOf<String>()

    private var activeIndex = -1

    /** Stable identity of the reported entry; unlike [activeIndex], it survives queue reorders. */
    private var activeItemId = ""

    /** Session id stored on the item; may be blank even though [activeSessionId] is generated. */
    private var activeBindingSessionId = ""
    private var activeSessionId = ""
    private var activePositionMs = 0L
    private var activeDurationMs = 0L
    private var activePaused = true
    private var activePlayMethod = DEFAULT_PLAY_METHOD
    private var reportedPositionMs = Long.MIN_VALUE
    private var terminalIndex = -1

    private val removeBackgroundListener =
        PlaybackBackgroundNotifier.register {
            val state = latestState
            if (!closed && state != null) {
                enqueue(Command.Background(state))
            }
        }

    init {
        scope.launch {
            for (ignored in commandWakeups) {
                while (true) {
                    val command =
                        synchronized(commandLock) {
                            pendingCommands.removeFirstOrNull()
                        } ?: break
                    val closeAfterCommand = command is Command.Close
                    try {
                        when (command) {
                            is Command.Update -> handleUpdate(command.state)
                            is Command.Background -> handleBackground(command.state)
                            is Command.Rebind -> handleRebind(command.items, command.state)
                            is Command.Close ->
                                try {
                                    if (activeIndex >= 0 || !command.state.ended) {
                                        handleUpdate(command.state)
                                    }
                                } finally {
                                    stopActive(
                                        if (command.state.ended) PlaybackSyncTrigger.Completed
                                        else PlaybackSyncTrigger.Stop,
                                    )
                                }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        AppLog.error(
                            category = "playback.reporting",
                            event = "command_failed",
                            message = "Playback reporting command failed; actor will continue",
                            throwable = error,
                            attributes = mapOf("command" to command.logName),
                        )
                    }
                    if (closeAfterCommand) {
                        commandWakeups.close()
                        return@launch
                    }
                }
            }
        }
    }

    fun update(state: PlaybackState) {
        if (closed || items.isEmpty()) return
        latestState = state
        preloadNextIfNeeded(state)

        val itemChanged = state.currentIndex != observedIndex
        val playStateChanged = state.playing != observedPlaying
        val seeked = abs(state.positionMs - observedPositionMs) >= SEEK_THRESHOLD_MS
        val periodic =
            enqueuedPositionMs == Long.MIN_VALUE ||
                abs(state.positionMs - enqueuedPositionMs) >= REPORT_INTERVAL_MS

        observedIndex = state.currentIndex
        observedPlaying = state.playing
        observedPositionMs = state.positionMs

        if (itemChanged || playStateChanged || seeked || periodic) {
            enqueuedPositionMs = state.positionMs
            enqueue(Command.Update(state))
        }
    }

    /**
     * The queue already contains concrete URLs for sibling episodes. When the active episode has
     * at most 90 seconds left, warm the beginning of the next direct source. The platform
     * implementation de-duplicates concurrent requests and writes into the player's shared cache,
     * so this is cheap to call from the normal 500 ms playback-state sampling path.
     */
    private fun preloadNextIfNeeded(state: PlaybackState) {
        val preloader = sourcePreloader ?: return
        if (state.durationMs <= 0L) return
        val remaining = state.remainingMs
        if (remaining <= 0L || remaining > NEXT_SOURCE_PRELOAD_WINDOW_MS) return
        val next = items.getOrNull(state.currentIndex + 1) ?: return
        if (!next.canPreloadSource) return
        if (!preloadedSources.add(next.url)) return
        preloader.preload(next.url)
    }

    /**
     * Replaces queue/session metadata on the same actor that reports playback.
     *
     * A version handover is ordered stop-old → bind-new → start-new. A tail append merely
     * grows [items], because the active `(item, session)` pair did not change and stopping
     * it would kill the healthy transcode ExoPlayer is still consuming.
     */
    fun rebind(
        items: List<PlayerMediaItem>,
        state: PlaybackState,
    ) {
        if (closed || items.isEmpty()) return
        latestState = state
        val binding = items.reportingBinding()
        if (binding == observedBinding) return
        observedBinding = binding
        enqueue(Command.Rebind(items, state))
    }

    fun close(state: PlaybackState) {
        if (closed) return
        latestState = state
        closed = true
        removeBackgroundListener()
        enqueue(Command.Close(state))
    }

    /**
     * Keeps report memory independent of seek frequency and server latency.
     *
     * Updates are snapshots, so only the newest pending one after a control command matters.
     * Background is durable and supersedes stale pending progress. A rebind carries its own state
     * and supersedes older queued updates/rebinds, but preserves a pending background durability
     * barrier after the rebind. Close retains at most the latest rebind (its item metadata may be
     * needed by the final state), discards stale progress, and becomes the next command after any
     * request already in flight.
     */
    private fun enqueue(command: Command) {
        val accepted =
            synchronized(commandLock) {
                when (command) {
                    is Command.Update -> enqueueUpdate(command)
                    is Command.Background -> {
                        pendingCommands.removeAll {
                            it is Command.Update || it is Command.Background
                        }
                        pendingCommands.addLast(command)
                        true
                    }
                    is Command.Rebind -> {
                        val preserveBackground = pendingCommands.any { it is Command.Background }
                        pendingCommands.removeAll {
                            it is Command.Update || it is Command.Background || it is Command.Rebind
                        }
                        pendingCommands.addLast(command)
                        if (preserveBackground) {
                            // Rebind is newer than the original signal and carries the same player
                            // clock sampled against the new queue, so use it for the durable flush.
                            pendingCommands.addLast(Command.Background(command.state))
                        }
                        true
                    }
                    is Command.Close -> {
                        val latestRebind = pendingCommands.lastOrNull { it is Command.Rebind }
                        pendingCommands.clear()
                        latestRebind?.let(pendingCommands::addLast)
                        pendingCommands.addLast(command)
                        true
                    }
                }
            }
        if (accepted) commandWakeups.trySend(Unit)
    }

    private fun enqueueUpdate(command: Command.Update): Boolean {
        if (pendingCommands.lastOrNull() is Command.Update) {
            pendingCommands.removeLast()
            pendingCommands.addLast(command)
            return true
        }
        if (pendingCommands.size < MAX_PENDING_REPORT_COMMANDS) {
            pendingCommands.addLast(command)
            return true
        }
        val staleUpdate = pendingCommands.indexOfFirst { it is Command.Update }
        if (staleUpdate < 0) return false
        pendingCommands.removeAt(staleUpdate)
        pendingCommands.addLast(command)
        return true
    }

    private suspend fun handleUpdate(state: PlaybackState) {
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        val terminal = state.ended
        if (terminal && index == terminalIndex && activeIndex < 0) return
        if (index != activeIndex) {
            stopActive(PlaybackSyncTrigger.Stop)
            activeIndex = index
            terminalIndex = -1
            activeSessionId = sessionIdFor(index)
            activePositionMs = state.positionMs
            activeDurationMs = state.durationMs
            activePaused = !state.playing
            reportedPositionMs = state.positionMs
            val item = items[index]
            activePlayMethod = state.playMethodFor(item)
            activeItemId = item.id
            activeBindingSessionId = item.playSessionId
            recordCrossPlatform(item, PlaybackSyncTrigger.Started)
            sink.startedWithMethod(
                item.id,
                activeSessionId,
                state.positionMs.toTicks(),
                activePaused,
                activePlayMethod,
            )
            if (!terminal) return
        }

        if (terminal) {
            activePositionMs = state.positionMs
            activeDurationMs = state.durationMs
            activePaused = !state.playing
            terminalIndex = index
            stopActive(PlaybackSyncTrigger.Completed)
            return
        }

        val seeked = abs(state.positionMs - activePositionMs) >= SEEK_THRESHOLD_MS
        activePositionMs = state.positionMs
        activeDurationMs = state.durationMs
        val paused = !state.playing
        activePlayMethod = state.playMethodFor(items[index])
        val pauseChanged = paused != activePaused
        val positionDue =
            reportedPositionMs == Long.MIN_VALUE ||
                abs(state.positionMs - reportedPositionMs) >= REPORT_INTERVAL_MS
        if (pauseChanged || positionDue || seeked) {
            activePaused = paused
            reportedPositionMs = state.positionMs
            val item = items[index]
            val trigger =
                when {
                    seeked -> PlaybackSyncTrigger.Seek
                    pauseChanged && paused -> PlaybackSyncTrigger.Pause
                    else -> PlaybackSyncTrigger.Periodic
                }
            recordCrossPlatform(item, trigger)
            sink.progressWithMethod(
                item.id,
                activeSessionId,
                state.positionMs.toTicks(),
                paused,
                activePlayMethod,
            )
        }
    }

    /** Writes the freshest sample even when the ten-second network/report interval is not due. */
    private suspend fun handleBackground(state: PlaybackState) {
        if (items.isEmpty()) return
        if (state.ended) {
            handleUpdate(state)
            return
        }
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        if (index != activeIndex) handleUpdate(state)
        if (activeIndex < 0) return

        val item = items.getOrNull(activeIndex) ?: return
        activePositionMs = state.positionMs
        activeDurationMs = state.durationMs
        activePaused = !state.playing
        activePlayMethod = state.playMethodFor(item)
        reportedPositionMs = state.positionMs
        recordCrossPlatform(item, PlaybackSyncTrigger.Background)
        sink.progressWithMethod(
            itemId = item.id,
            sessionId = activeSessionId,
            positionTicks = state.positionMs.toTicks(),
            isPaused = activePaused,
            playMethod = activePlayMethod,
        )
    }

    private suspend fun handleRebind(
        newItems: List<PlayerMediaItem>,
        state: PlaybackState,
    ) {
        val oldItems = items
        val oldActive = oldItems.getOrNull(activeIndex)
        val oldActiveId = activeItemId.ifBlank { oldActive?.id.orEmpty() }
        val oldStateItem =
            if (oldItems.isEmpty()) {
                null
            } else {
                oldItems[state.currentIndex.coerceIn(0, oldItems.lastIndex)]
            }
        val stateStillNamesOldActive =
            oldStateItem?.let { item ->
                item.id == oldActiveId && item.playSessionId == activeBindingSessionId
            } == true
        val retainedIndex =
            newItems.indexOfFirst { item ->
                item.id == oldActiveId && item.playSessionId == activeBindingSessionId
            }

        if (activeIndex >= 0 && stateStillNamesOldActive && retainedIndex >= 0) {
            items = newItems
            activeIndex = retainedIndex
            return
        }

        val newIndex = state.currentIndex.coerceIn(0, newItems.lastIndex)
        val newActive = newItems.getOrNull(newIndex)
        val activeChanged =
            oldActiveId != newActive?.id ||
                activeBindingSessionId != newActive?.playSessionId
        if (activeChanged) stopActive(PlaybackSyncTrigger.Stop)
        items = newItems
        if (activeChanged) {
            terminalIndex = -1
            handleUpdate(state)
        } else {
            activeIndex = newIndex
        }
    }

    private suspend fun stopActive(trigger: PlaybackSyncTrigger) {
        val item = items.getOrNull(activeIndex)
        val itemId = activeItemId.ifBlank { item?.id.orEmpty() }
        try {
            if (activeIndex >= 0 && itemId.isNotBlank()) {
                item?.let { recordCrossPlatform(it, trigger) }
                sink.stoppedWithMethod(
                    itemId = itemId,
                    sessionId = activeSessionId,
                    positionTicks = activePositionMs.toTicks(),
                    isPaused = activePaused,
                    playMethod = activePlayMethod,
                )
            }
        } finally {
            activeIndex = -1
            activeItemId = ""
            activeBindingSessionId = ""
            activeSessionId = ""
            activePositionMs = 0L
            activeDurationMs = 0L
            activePlayMethod = DEFAULT_PLAY_METHOD
            reportedPositionMs = Long.MIN_VALUE
        }
    }

    private fun recordCrossPlatform(
        item: PlayerMediaItem,
        trigger: PlaybackSyncTrigger,
    ) {
        playbackSync?.recordPlayback(
            mediaKey = item.watchKey,
            aliases = item.matchKeys,
            positionMs = activePositionMs,
            durationMs = activeDurationMs,
            sessionId = activeSessionId,
            serverId = item.serverId,
            serverItemId = item.id,
            trigger = trigger,
        )
    }

    private fun sessionIdFor(index: Int): String =
        items.getOrNull(index)?.playSessionId?.takeIf { it.isNotBlank() }
            ?: "yfuse${Random.nextLong().toULong().toString(16)}"

    private fun Long.toTicks(): Long =
        coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / TICKS_PER_MILLISECOND) * TICKS_PER_MILLISECOND

    private val Command.logName: String
        get() =
            when (this) {
                is Command.Update -> "update"
                is Command.Background -> "background"
                is Command.Rebind -> "rebind"
                is Command.Close -> "close"
            }
}

private fun List<PlayerMediaItem>.reportingBinding(): List<Pair<String, String>> = map { it.id to it.playSessionId }

private fun PlaybackState.playMethodFor(item: PlayerMediaItem): String =
    if (transcoding) "Transcode" else item.playMethod.embyValue
