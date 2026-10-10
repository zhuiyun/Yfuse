package com.yfuse.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Seek requests inside this window collapse into one, always at the latest target. */
internal const val SEEK_MERGE_DEBOUNCE_MS = 120L

/**
 * What the picture's gestures ask of the player root: the seeks a moving finger or a held key
 * proposes, and 长按中间's rate while the middle of the picture is held.
 *
 * The controls' own half — what a gesture is in the middle of, and what the HUD says about it — is
 * [PlayerGestureState]. This is the root's half, held apart from the root's composition so neither a
 * drag nor a hold recomposes it: seeks never become state at all, and the held rate is read only by
 * the effect that applies it (PlayerTrackEffects), so a shift of gear recomposes that effect alone.
 *
 * Remembered once for the life of the player. Touched on the main thread only: the controls'
 * callbacks and the effect delivering the seeks.
 */
@Stable
internal class PlayerGestureCommands(
    private val currentContext: () -> PlaybackInteractionContext? = { null },
) {
    private data class ContextKey(
        val context: PlaybackInteractionContext?,
        val revision: Long,
    )

    private data class SeekRequest(
        val positionMs: Long,
        val owner: ContextKey,
    )

    private var revision = 0L

    val contextKey: Any get() = ContextKey(currentContext(), revision)
    val localContextKey: Any get() = ContextKey(currentContext()?.local(), revision)
    val localMediaKey: Any? get() = currentContext()?.local()

    /** Called before replacing media, retrying, changing output or leaving the player. */
    fun invalidate() {
        revision++
        seekRequests.tryReceive()
        heldBoost = null
        boostOwner = null
        boostResumedPlayback = false
    }

    // Seek requests travel on a conflating channel rather than through composition. As a
    // `sequence` counter in a MutableState, a held rewind key re-keyed the delivering effect — and
    // so recomposed the entire player root — every 300ms while the finger stayed down.
    private val seekRequests = Channel<SeekRequest>(Channel.CONFLATED)

    /** Proposes a seek to [positionMs]; a newer proposal inside the merge window replaces it. */
    fun seek(positionMs: Long) {
        seek(positionMs, contextKey)
    }

    /** A control removed by a media replacement cannot submit its old drag on cancellation. */
    fun seek(
        positionMs: Long,
        owner: Any,
    ) {
        if (owner != contextKey) return
        seekRequests.trySend(SeekRequest(positionMs.coerceAtLeast(0L), ContextKey(currentContext(), revision)))
    }

    /**
     * Hands proposed seeks to [seekTo] until cancelled, each once no newer one has arrived for
     * [SEEK_MERGE_DEBOUNCE_MS]: a burst of proposals reaches a local engine or a Cast receiver as a
     * single seek to where the user stopped.
     */
    suspend fun deliverSeeks(seekTo: suspend (Long) -> Unit) {
        for (offered in seekRequests) {
            var request = offered
            // Trailing debounce: a newer target arriving inside the window replaces this one
            // and restarts it, so only the position the user stopped on is ever sent.
            while (true) {
                delay(SEEK_MERGE_DEBOUNCE_MS)
                request = seekRequests.tryReceive().getOrNull() ?: break
            }
            if (request.owner == ContextKey(currentContext(), revision)) seekTo(request.positionMs)
        }
    }

    /**
     * 长按中间: the rate while the middle of the picture is held, over the chosen one; null when it is
     * not held. Only the engine sees it — never the room, the series memory or the preference.
     */
    private var heldBoost: Float? by mutableStateOf(null)
    private var boostOwner: ContextKey? = null

    val boost: Float?
        get() = heldBoost.takeIf { boostOwner == ContextKey(currentContext(), revision) }

    // Whether the hold started playback from a pause. Nothing composes from it, so a plain field.
    private var boostResumedPlayback = false

    /**
     * The controls report the middle held at [rate], a new rate as the hold shifts gear, and null
     * when it lets go. A hold that began from a pause starts playback and puts the pause back when it
     * lets go, unless the viewer has since lost control of the room.
     */
    fun holdBoost(
        rate: Float?,
        playbackRequested: () -> Boolean,
        play: () -> Boolean,
        pause: () -> Unit,
        locked: () -> Boolean,
    ) {
        if (rate != null) {
            val owner = ContextKey(currentContext(), revision)
            if (heldBoost == null || boostOwner != owner) {
                boostOwner = owner
                // Judged on the play intent, not on frames: a stream that is
                // buffering towards playback is not paused.
                boostResumedPlayback = !playbackRequested() && play()
            }
            heldBoost = rate
        } else if (heldBoost != null) {
            heldBoost = null
            if (
                boostOwner == ContextKey(currentContext(), revision) &&
                boostResumedPlayback &&
                playbackRequested() &&
                !locked()
            ) {
                pause()
            }
            boostResumedPlayback = false
            boostOwner = null
        }
    }
}
