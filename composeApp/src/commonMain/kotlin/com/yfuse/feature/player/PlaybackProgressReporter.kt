package com.yfuse.feature.player

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

private const val REPORT_INTERVAL_MS = 10_000L
private const val SEEK_THRESHOLD_MS = 5_000L
private const val TICKS_PER_MILLISECOND = 10_000L

internal interface PlaybackEventSink {
    suspend fun started(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)
    suspend fun progress(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)
    suspend fun stopped(itemId: String, sessionId: String, positionTicks: Long, isPaused: Boolean)
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
        repo.stopTranscoding(server, sessionId)
    }
}

/**
 * Serializes Emby playback events so item transitions always stop the old
 * session before starting the new one. Position updates are throttled to ten
 * seconds, but pause/resume and seeks are reported immediately.
 */
internal class PlaybackProgressReporter(
    private val items: List<PlayerMediaItem>,
    private val sink: PlaybackEventSink,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private sealed interface Command {
        data class Update(val state: PlaybackState) : Command
        data class Close(val state: PlaybackState) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var closed = false
    private var observedIndex = -1
    private var observedPlaying: Boolean? = null
    private var observedPositionMs = 0L
    private var enqueuedPositionMs = Long.MIN_VALUE

    private var activeIndex = -1
    private var activeSessionId = ""
    private var activePositionMs = 0L
    private var activePaused = true
    private var reportedPositionMs = Long.MIN_VALUE
    private var terminalIndex = -1

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Update -> handleUpdate(command.state)
                    is Command.Close -> {
                        if (activeIndex >= 0 || (!command.state.ended && command.state.error == null)) {
                            handleUpdate(command.state)
                        }
                        stopActive()
                    }
                }
            }
        }
    }

    fun update(state: PlaybackState) {
        if (closed || items.isEmpty()) return
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
            commands.trySend(Command.Update(state))
        }
    }

    fun close(state: PlaybackState) {
        if (closed) return
        closed = true
        commands.trySend(Command.Close(state))
        commands.close()
    }

    private suspend fun handleUpdate(state: PlaybackState) {
        val index = state.currentIndex.coerceIn(0, items.lastIndex)
        val terminal = state.ended || state.error != null
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
            sink.started(item.id, activeSessionId, state.positionMs.toTicks(), activePaused)
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
        val pauseChanged = paused != activePaused
        val positionDue = reportedPositionMs == Long.MIN_VALUE ||
            abs(state.positionMs - reportedPositionMs) >= REPORT_INTERVAL_MS
        if (pauseChanged || positionDue || seeked) {
            activePaused = paused
            reportedPositionMs = state.positionMs
            val item = items[index]
            sink.progress(item.id, activeSessionId, state.positionMs.toTicks(), paused)
        }
    }

    private suspend fun stopActive() {
        val item = items.getOrNull(activeIndex) ?: return
        sink.stopped(
            itemId = item.id,
            sessionId = activeSessionId,
            positionTicks = activePositionMs.toTicks(),
            isPaused = activePaused,
        )
        activeIndex = -1
        activeSessionId = ""
        reportedPositionMs = Long.MIN_VALUE
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
            ?: "yfuse-${Random.nextLong().toULong().toString(16)}"

    private fun Long.toTicks(): Long =
        coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / TICKS_PER_MILLISECOND) * TICKS_PER_MILLISECOND
}
