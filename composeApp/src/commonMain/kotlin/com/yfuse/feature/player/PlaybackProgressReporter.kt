package com.yfuse.feature.player

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.SavedServer
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

internal interface PlaybackEventSink {
    suspend fun started(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)
    suspend fun progress(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)
    suspend fun stopped(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)

    suspend fun startedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = started(itemId, sessionId, positionTicks, isPaused)

    suspend fun progressWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = progress(itemId, sessionId, positionTicks, isPaused)

    suspend fun stoppedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = stopped(itemId, sessionId, positionTicks, isPaused)

    /** Ends only the server's encoder job; the logical playback session remains active. */
    suspend fun stopEncoding(sessionId: String): Boolean = true
}

internal class EmbyPlaybackEventSink(
    private val repo: EmbyRepository,
    private val server: SavedServer,
) : PlaybackEventSink {
    override suspend fun started(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean) {
        repo.reportPlaybackStarted(server, itemId, sessionId, positionTicks, isPaused)
    }

    override suspend fun progress(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean) {
        repo.reportPlaybackProgress(server, itemId, sessionId, positionTicks, isPaused)
    }

    override suspend fun stopped(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean) {
        repo.reportPlaybackStopped(server, itemId, sessionId, positionTicks, isPaused)
        // Belt and braces. `Playing/Stopped` is the polite request; some server versions
        // leave the ffmpeg process running anyway, and an orphaned encoding is what makes
        // the *next* attempt at the same file fail with a 4xx instead of playing.
        stopEncoding(sessionId)
    }

    override suspend fun startedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo.reportPlaybackStarted(server, itemId, sessionId, positionTicks, isPaused, playMethod)
    }

    override suspend fun progressWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo.reportPlaybackProgress(server, itemId, sessionId, positionTicks, isPaused, playMethod)
    }

    override suspend fun stoppedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo.reportPlaybackStopped(server, itemId, sessionId, positionTicks, isPaused, playMethod)
        stopEncoding(sessionId)
    }

    override suspend fun stopEncoding(sessionId: String): Boolean =
        repo.stopTranscoding(server, sessionId).isSuccess
}

/**
 * Serializes Emby playback events so item transitions always stop the old
 * session before starting the new one. Position updates are throttled to ten
 * seconds, but pause/resume and seeks are reported immediately.
 */
internal class PlaybackProgressReporter(
    items: List<PlayerMediaItem>,
    private val sink: PlaybackEventSink,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val sourcePreloader: PlaybackSourcePreloader? = runCatching {
        GlobalContext.get().get<PlaybackSourcePreloader>()
    }.getOrNull(),
) {
    private sealed interface Command {
        data class Update(val state: PlaybackState) : Command
        data class Rebind(val items: List<PlayerMediaItem>, val state: PlaybackState) : Command
        data class Close(val state: PlaybackState) : Command
    }

    private var items = items
    private var observedBinding = items.reportingBinding()
    private val commandLock = Any()
    private val pendingCommands = ArrayDeque<Command>()
    private val commandWakeups = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    private var closed = false
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
    private var activePaused = true
    private var activePlayMethod = "DirectPlay"
    private var reportedPositionMs = Long.MIN_VALUE
    private var terminalIndex = -1

    init {
        scope.launch {
            for (ignored in commandWakeups) {
                while (true) {
                    val command = synchronized(commandLock) {
                        pendingCommands.removeFirstOrNull()
                    } ?: break
                    when (command) {
                        is Command.Update -> handleUpdate(command.state)
                        is Command.Rebind -> handleRebind(command.items, command.state)
                        is Command.Close -> {
                            if (activeIndex >= 0 || !command.state.ended) {
                                handleUpdate(command.state)
                            }
                            stopActive()
                            commandWakeups.close()
                            return@launch
                        }
                    }
                }
            }
        }
    }

    fun update(state: PlaybackState) {
        if (closed || items.isEmpty()) return
        preloadNextIfNeeded(state)

        val itemChanged = state.currentIndex != observedIndex
        val playStateChanged = state.playing != observedPlaying
        val seeked = abs(state.positionMs - observedPositionMs) >= SEEK_THRESHOLD_MS
        val periodic = enqueuedPositionMs == Long.MIN_VALUE ||
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
    fun rebind(items: List<PlayerMediaItem>, state: PlaybackState) {
        if (closed || items.isEmpty()) return
        val binding = items.reportingBinding()
        if (binding == observedBinding) return
        observedBinding = binding
        enqueue(Command.Rebind(items, state))
    }

    fun close(state: PlaybackState) {
        if (closed) return
        closed = true
        enqueue(Command.Close(state))
    }

    /**
     * Keeps report memory independent of seek frequency and server latency.
     *
     * Updates are snapshots, so only the newest pending one after a control command matters.
     * A rebind carries its own state and supersedes older queued updates/rebinds. Close retains
     * at most the latest rebind (its item metadata may be needed by the final state), discards
     * stale progress, and becomes the next command after any request already in flight.
     */
    private fun enqueue(command: Command) {
        val accepted = synchronized(commandLock) {
            when (command) {
                is Command.Update -> {
                    if (pendingCommands.lastOrNull() is Command.Update) {
                        pendingCommands.removeLast()
                        pendingCommands.addLast(command)
                        true
                    } else if (pendingCommands.size < MAX_PENDING_REPORT_COMMANDS) {
                        pendingCommands.addLast(command)
                        true
                    } else {
                        val staleUpdate = pendingCommands.indexOfFirst { it is Command.Update }
                        if (staleUpdate < 0) {
                            false
                        } else {
                            pendingCommands.removeAt(staleUpdate)
                            pendingCommands.addLast(command)
                            true
                        }
                    }
                }

                is Command.Rebind -> {
                    pendingCommands.removeAll { it is Command.Update || it is Command.Rebind }
                    pendingCommands.addLast(command)
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

    private suspend fun handleUpdate(state: PlaybackState) {
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        // Engine errors are recoverable inside PlayerRoot: another URL, decoder stack or
        // physical version may start immediately with this same PlaySessionId. Treating the
        // intermediate error as terminal schedules a delayed DELETE that can kill the new
        // engine's ffmpeg after it has started. Natural end is terminal; final screen exit,
        // item/version rebind and explicit close stop everything else in actor order.
        val terminal = state.ended
        if (terminal && index == terminalIndex && activeIndex < 0) return
        if (index != activeIndex) {
            stopActive()
            activeIndex = index
            terminalIndex = -1
            activeSessionId = sessionIdFor(index)
            activePositionMs = state.positionMs
            activePaused = !state.playing
            reportedPositionMs = state.positionMs
            val item = items[index]
            activePlayMethod = state.playMethodFor(item)
            activeItemId = item.id
            activeBindingSessionId = item.playSessionId
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
            activePaused = !state.playing
            terminalIndex = index
            stopActive()
            return
        }

        val seeked = abs(state.positionMs - activePositionMs) >= SEEK_THRESHOLD_MS
        activePositionMs = state.positionMs
        val paused = !state.playing
        activePlayMethod = state.playMethodFor(items[index])
        val pauseChanged = paused != activePaused
        val positionDue = reportedPositionMs == Long.MIN_VALUE ||
            abs(state.positionMs - reportedPositionMs) >= REPORT_INTERVAL_MS
        if (pauseChanged || positionDue || seeked) {
            activePaused = paused
            reportedPositionMs = state.positionMs
            val item = items[index]
            sink.progressWithMethod(
                item.id,
                activeSessionId,
                state.positionMs.toTicks(),
                paused,
                activePlayMethod,
            )
        }
    }

    private suspend fun handleRebind(newItems: List<PlayerMediaItem>, state: PlaybackState) {
        val oldItems = items
        val oldActive = oldItems.getOrNull(activeIndex)
        val oldActiveId = activeItemId.ifBlank { oldActive?.id.orEmpty() }
        val oldStateItem = if (oldItems.isEmpty()) {
            null
        } else {
            oldItems[state.currentIndex.coerceIn(0, oldItems.lastIndex)]
        }
        val stateStillNamesOldActive = oldStateItem?.let { item ->
            item.id == oldActiveId && item.playSessionId == activeBindingSessionId
        } == true
        val retainedIndex = newItems.indexOfFirst { item ->
            item.id == oldActiveId && item.playSessionId == activeBindingSessionId
        }

        // Queue refresh reports the old engine's numeric index until its deliberate rebuild.
        // Follow the active (item, session) pair to its new index instead of interpreting that
        // stale number against the reordered/shorter queue and briefly reporting the wrong item.
        if (activeIndex >= 0 && stateStillNamesOldActive && retainedIndex >= 0) {
            items = newItems
            activeIndex = retainedIndex
            return
        }

        val newIndex = state.currentIndex.coerceIn(0, newItems.lastIndex)
        val newActive = newItems.getOrNull(newIndex)
        val activeChanged = oldActiveId != newActive?.id ||
            activeBindingSessionId != newActive?.playSessionId
        if (activeChanged) stopActive()
        items = newItems
        if (activeChanged) {
            terminalIndex = -1
            handleUpdate(state)
        } else {
            // The same session survived a shrink that clamped its index. Keep the actor's
            // numeric cursor valid so the next update/close does not start it a second time.
            activeIndex = newIndex
        }
    }

    private suspend fun stopActive() {
        val itemId = activeItemId.ifBlank { items.getOrNull(activeIndex)?.id.orEmpty() }
        try {
            if (activeIndex >= 0 && itemId.isNotBlank()) {
                sink.stoppedWithMethod(
                    itemId = itemId,
                    sessionId = activeSessionId,
                    positionTicks = activePositionMs.toTicks(),
                    isPaused = activePaused,
                    playMethod = activePlayMethod,
                )
            }
        } finally {
            // Always clear actor state, even when the queue was shortened underneath the old
            // numeric index (or a sink implementation throws). Otherwise the next update starts
            // the same session again and the eventual close may stop the wrong entry.
            activeIndex = -1
            activeItemId = ""
            activeBindingSessionId = ""
            activeSessionId = ""
            activePlayMethod = "DirectPlay"
            reportedPositionMs = Long.MIN_VALUE
        }
    }

    /**
     * The id the entry's stream URLs already carry, so the server can tie these reports to
     * the encoding it started.
     *
     * This used to mint a fresh id here. It was never wrong on its own terms — Emby accepted
     * it — but it named a session the stream URLs knew nothing about, so `Playing/Stopped`
     * could not end the transcode it was reporting the end of. Offline entries and queues
     * marshalled by an older build carry no id, and still get a generated one.
     */
    private fun sessionIdFor(index: Int): String =
        items.getOrNull(index)?.playSessionId?.takeIf { it.isNotBlank() }
            ?: "yfuse${Random.nextLong().toULong().toString(16)}"

    private fun Long.toTicks(): Long =
        coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / TICKS_PER_MILLISECOND) * TICKS_PER_MILLISECOND
}

private fun List<PlayerMediaItem>.reportingBinding(): List<Pair<String, String>> =
    map { it.id to it.playSessionId }

private fun PlaybackState.playMethodFor(item: PlayerMediaItem): String =
    if (transcoding) "Transcode" else item.playMethod.embyValue
