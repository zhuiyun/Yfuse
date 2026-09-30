package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 本集结束 arms once the item is this close to its end, so a later stop still counts as its end. */
internal const val END_OF_EPISODE_ARM_WINDOW_MS = 2_000L
internal const val SLEEP_TIMER_TICK_MS = 1_000L
internal const val SLEEP_TIMER_PAUSED_POLL_MS = 500L

/**
 * 睡眠定时 for the life of the player: the option picked and, for 本集结束, which item's end it waits
 * for — on this device, or in which cast session. PlayerRoot's effects read it and pause playback
 * when it goes off; the controls pick an option, and every way of moving to another item moves the
 * wait along with it.
 *
 * Snapshot state, since effects are keyed on all of it. Touched on the main thread only.
 */
@Stable
internal class PlayerSleepTimer {
    var option: SleepTimerOption by mutableStateOf(SleepTimerOption.Off)
        private set

    /** For 本集结束, the queue index whose end pauses playback. */
    var endIndex: Int? by mutableStateOf(null)
        private set

    /** For 本集结束 picked while casting, the cast session that item plays in. */
    var endSessionRevision: Long? by mutableStateOf(null)
        private set

    /** The armed item came within [END_OF_EPISODE_ARM_WINDOW_MS] of its end on this device. */
    var armedItemReachedEnd: Boolean by mutableStateOf(false)
        private set

    /** Bumped by every pick, so choosing the same duration again starts the countdown over. */
    var revision: Int by mutableIntStateOf(0)
        private set

    /**
     * The viewer picked [option] at [currentIndex]; [castSessionRevision] is the live cast session's
     * revision, or null when not casting.
     */
    fun select(
        option: SleepTimerOption,
        currentIndex: Int,
        castSessionRevision: Long?,
    ) {
        this.option = option
        endIndex = currentIndex.takeIf { option == SleepTimerOption.EndOfEpisode }
        endSessionRevision = castSessionRevision.takeIf { option == SleepTimerOption.EndOfEpisode }
        armedItemReachedEnd = false
        revision++
    }

    /**
     * Playback moved to [index], in the cast session [castSessionRevision] when casting: 本集结束 now
     * waits for that item's end instead. Nothing for any other option.
     */
    fun follow(
        index: Int,
        castSessionRevision: Long?,
    ) {
        if (option != SleepTimerOption.EndOfEpisode) return
        endIndex = index
        endSessionRevision = castSessionRevision
        armedItemReachedEnd = false
    }

    /** Local playback reported [current]; the armed item nearing its end arms the pause. */
    fun observe(current: PlaybackState) {
        if (option == SleepTimerOption.EndOfEpisode &&
            endIndex == current.currentIndex &&
            current.durationMs > 0L &&
            current.remainingMs <= END_OF_EPISODE_ARM_WINDOW_MS
        ) {
            armedItemReachedEnd = true
        }
    }

    /** The timer went off and paused playback: back to 关闭. The countdown is not restarted. */
    fun finish() {
        option = SleepTimerOption.Off
        endIndex = null
        endSessionRevision = null
        armedItemReachedEnd = false
    }
}

/**
 * Returns once [durationMs] of playback has passed. Counts playback, not wall-clock: a pause to
 * answer the door must not use up the timer. [playing] is polled while paused.
 */
internal suspend fun awaitSleepTimerPlayback(
    durationMs: Long,
    playing: () -> Boolean,
) {
    var remainingMs = durationMs
    while (remainingMs > 0L) {
        if (!playing()) {
            delay(SLEEP_TIMER_PAUSED_POLL_MS)
            continue
        }
        val step = minOf(SLEEP_TIMER_TICK_MS, remainingMs)
        delay(step)
        remainingMs -= step
    }
}
