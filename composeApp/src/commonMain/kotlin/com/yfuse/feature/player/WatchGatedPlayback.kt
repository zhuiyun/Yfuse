package com.yfuse.feature.player

import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.parseEpisodeWatchKey
import kotlin.math.abs

/** How often a guest re-checks its drift against the room's timeline. */
const val GUEST_RECONCILE_TICK_MS = 1_000L

/** Below this, drift is imperceptible and left alone. */
const val NUDGE_THRESHOLD_MS = 50L

/** Above this, nudging would take too long to feel right — jump instead. */
const val HARD_SEEK_THRESHOLD_MS = 2_000L

/** Speed offset used to close a nudge-range gap without an audible/visible jump. */
const val NUDGE_FRACTION = 0.02f

/** Avoids reissuing `setSpeed` every tick for a rate that hasn't materially changed. */
const val RATE_EPSILON = 0.001f

/**
 * How long a guest tolerates a room timeline that names media it doesn't have before saying
 * so. A couple of ticks of grace keeps an ordinary episode change — where the host's anchor
 * lands before this device has finished switching entries — from flashing a warning.
 */
private const val MISMATCH_GRACE_TICKS = 3

/**
 * The single gate every play/pause/seek/episode change passes through on its way to a
 * [VideoEngine].
 *
 * Reaching the engine directly breaks watch-together in two directions at once. A guest's
 * input lands, then gets silently undone by the reconcile loop a second later — the control
 * looks broken with no explanation. A host's input lands but is never published as a room
 * anchor, so everyone else keeps following a stale one, permanently, because the host has no
 * reason to touch anything again.
 *
 * The in-player controls handled both by hand at each call site. The media session, the
 * notification buttons, the picture-in-picture action and the mini player each went straight
 * at the engine and missed both halves. Routing every surface through here is what stops that
 * recurring as entry points are added.
 */
class WatchGatedPlayback(
    private val watchTogether: WatchTogetherClient,
    private val items: () -> List<PlayerMediaItem>,
    private val engine: () -> VideoEngine?,
    private val onLocked: () -> Unit = {},
) {
    private var observedIndex: Int? = null

    /**
     * Read straight off the engine rather than from a separately tracked copy: gating
     * decisions compare against "playing right now", and a state mirrored through
     * recomposition is a frame behind.
     */
    private val state: PlaybackState? get() = engine()?.state?.value

    /** True while someone else owns the room's timeline, so local input must be refused. */
    val locked: Boolean
        get() = watchTogether.state.value.let { it.connected && !it.isHost }

    fun togglePlayPause(): Boolean = gated { engine ->
        val willPlay = state?.playing != true
        if (willPlay) engine.play() else engine.pause()
        publish(paused = !willPlay)
    }

    fun play(): Boolean = gated { engine ->
        engine.play()
        publish(paused = false)
    }

    fun pause(): Boolean = gated { engine ->
        engine.pause()
        publish(paused = true)
    }

    fun seekTo(positionMs: Long): Boolean = gated { engine ->
        engine.seekTo(positionMs)
        publish(positionMs = positionMs)
    }

    fun selectItem(index: Int): Boolean {
        if (index !in items().indices) return false
        return gated { engine ->
            engine.selectItem(index)
            observedIndex = index
            publish(index = index, positionMs = 0L, paused = false)
        }
    }

    fun selectNext(): Boolean = selectItem((state?.currentIndex ?: 0) + 1)

    fun selectPrevious(): Boolean = selectItem((state?.currentIndex ?: 0) - 1)

    fun setSpeed(speed: Float): Boolean = gated { engine ->
        engine.setSpeed(speed)
        publish(rate = speed)
    }

    /**
     * Re-anchors the room on the entry the engine moved to on its own. Engines advance
     * through the queue internally when auto-next is on, so an episode ending is the one
     * timeline change no control surface can report.
     */
    fun onPlaybackIndexChanged(index: Int) {
        val previous = observedIndex
        observedIndex = index
        if (previous == null || previous == index || locked) return
        publish(index = index, positionMs = engine()?.currentPositionMs() ?: 0L, paused = false)
    }

    /** Publishes wherever playback actually is, for (re)gaining control of a room. */
    fun publishCurrent() {
        if (locked) return
        publish()
    }

    /** Retry is local recovery, but a guest still must not restart its engine behind the host. */
    fun retry(): Boolean = gated(VideoEngine::retry)

    private inline fun gated(action: (VideoEngine) -> Unit): Boolean {
        if (locked) {
            onLocked()
            return false
        }
        val engine = engine() ?: return false
        action(engine)
        return true
    }

    /**
     * [paused] and [rate] are passed explicitly by callers that just asked for a change: the
     * engine's own state flow only ticks a couple of times a second, so reading it back here
     * would publish the value the action was about to replace.
     */
    private fun publish(
        index: Int = state?.currentIndex ?: 0,
        positionMs: Long? = null,
        paused: Boolean = state?.playing != true,
        rate: Float = nominalRate(),
    ) {
        val item = items().getOrNull(index) ?: return
        watchTogether.publishTimeline(
            mediaKey = item.watchKey,
            positionMs = positionMs ?: engine()?.currentPositionMs() ?: return,
            paused = paused,
            rate = rate,
        )
    }

    /**
     * The rate a host should publish.
     *
     * A guest runs playback a couple of percent off the room's rate to close small gaps. A
     * guest promoted to host mid-nudge would otherwise publish that off-nominal number as the
     * room's new official rate — where everyone else nudges it again, and again on the next
     * host change, so the drift compounds. Anything inside the nudge band is reported as the
     * room's own rate; a genuine 倍速 change is well outside it and passes through.
     */
    private fun nominalRate(): Float {
        val measured = state?.speed ?: 1f
        val room = watchTogether.timeline.value?.rate ?: return measured
        return nominalWatchRate(measured, room)
    }
}

internal fun nominalWatchRate(measured: Float, room: Float): Float {
    val band = room * NUDGE_FRACTION + RATE_EPSILON
    return if (abs(measured - room) <= band) room else measured
}

/**
 * Tracks whether a guest can actually follow the room, so "connected but silently not
 * syncing" stops being indistinguishable from working.
 *
 * The room identifies media by a cross-server key, which degrades to a server-specific
 * `emby:<id>` whenever provider ids are missing — common for episodes. Two people on
 * different servers then hold the same file under keys that never match, and the reconcile
 * loop simply finds nothing to do, forever, while the UI keeps saying 房主控制播放.
 */
class WatchMediaMatcher(private val onWarning: (String?) -> Unit) {
    private var missedTicks = 0

    /** Returns the queue index to follow, or null when the room is playing something else. */
    fun resolve(items: List<PlayerMediaItem>, mediaKey: String?): Int? {
        if (mediaKey == null) {
            reset()
            return null
        }
        val index = items.indexOfFirst { it.watchKey == mediaKey }
        if (index >= 0) {
            reset()
            return index
        }
        // Same episode, different spelling. `episodeWatchKey` names an episode after its
        // show, and the two devices can still disagree about which provider id names the
        // show — one library has the series' Tmdb id, the other only its Tvdb id. The
        // season and episode numbers do not disagree, and this queue is one show: it was
        // built by resolving this room's media in the first place, so "the room is on
        // s2e5" identifies an entry in it without needing the show's name to match.
        parseEpisodeWatchKey(mediaKey)?.let { coordinate ->
            val byCoordinate = items.indexOfFirst {
                it.episodeNumber == coordinate.episodeNumber &&
                    (it.seasonNumber ?: 0) == coordinate.seasonNumber
            }
            if (byCoordinate >= 0) {
                reset()
                return byCoordinate
            }
        }
        missedTicks++
        if (missedTicks == MISMATCH_GRACE_TICKS) {
            onWarning("房间在播放你的媒体库里没有的内容，无法同步进度")
        }
        return null
    }

    fun reset() {
        if (missedTicks >= MISMATCH_GRACE_TICKS) onWarning(null)
        missedTicks = 0
    }
}
