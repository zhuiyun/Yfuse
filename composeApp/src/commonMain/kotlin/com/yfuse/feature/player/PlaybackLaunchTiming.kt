package com.yfuse.feature.player

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.TimeSource

internal fun releasesPlaybackBackgroundWork(
    stage: String,
    mediaType: String?,
): Boolean =
    stage == "first_video_output" ||
        stage == "startup_error" ||
        (stage == "first_audio_output" && mediaType.equals("Audio", ignoreCase = true))

/** One tap, across detail resolution, prepared-store handoff, Activity and decoder output. */
internal class PlaybackLaunchTiming {
    private val started = TimeSource.Monotonic.markNow()
    private val outputOrFailure = CompletableDeferred<Unit>()
    private val id = Random.nextLong().toULong().toString(16)
    private val reported = mutableSetOf<String>()
    private var claimed = false
    private var sessionId: String? = null

    fun claim(): Boolean =
        synchronized(reported) {
            if (claimed) {
                false
            } else {
                claimed = true
                true
            }
        }

    /**
     * Whether this launch still holds background priority: claimed, no first output or startup
     * error yet, and inside [BACKGROUND_PRIORITY_DEADLINE_MS]. Non-suspending, for pollers
     * (calendar fan-out, playback-sync applies) that must step aside for a tap in flight without
     * blocking on it the way [awaitOutputOrDeadline] does for detail work queued behind it.
     */
    fun holdsBackgroundPriority(): Boolean =
        synchronized(reported) { claimed } &&
            !outputOrFailure.isCompleted &&
            elapsedMs() < BACKGROUND_PRIORITY_DEADLINE_MS

    fun bindSession(value: String?) =
        synchronized(reported) {
            if (!value.isNullOrBlank()) sessionId = value
        }

    fun matchesSession(value: String): Boolean = synchronized(reported) { sessionId == value }

    fun elapsedMs(): Long = started.elapsedNow().inWholeMilliseconds

    fun stage(
        name: String,
        output: Boolean = false,
    ) {
        if (output) outputOrFailure.complete(Unit)
        if (!synchronized(reported) { reported.add(name) }) return
        AppLog.info(
            category = "player",
            event = "playback_launch_stage",
            message = "Playback launch reached $name",
            attributes = mapOf("launchId" to id, "stage" to name, "tapElapsedMs" to elapsedMs().toString()),
        )
    }

    suspend fun awaitForeground() {
        if (!synchronized(reported) { claimed }) return
        awaitOutputOrDeadline()
    }

    /** Detail work queued behind a tap waits even if the player has not mounted yet. */
    suspend fun awaitOutputOrDeadline() {
        // Background catalogs still recover when no player UI is mounted or output never arrives.
        val released =
            withTimeoutOrNull(BACKGROUND_PRIORITY_DEADLINE_MS) {
                outputOrFailure.await()
                true
            } == true
        stage(if (released) "background_requests_released" else "background_priority_deadline")
    }

    private companion object {
        /** Matches the 30s deadline already used by [awaitOutputOrDeadline]. */
        const val BACKGROUND_PRIORITY_DEADLINE_MS = 30_000L
    }
}

internal object PlaybackLaunchTimings {
    private val entries = LinkedHashMap<Pair<String, String>, PlaybackLaunchTiming>()

    /**
     * True while any recently registered launch still holds background priority - see
     * [PlaybackLaunchTiming.holdsBackgroundPriority]. Calendar fan-out and playback-sync applies
     * poll this instead of each holding a reference to one specific launch, since either can run
     * while zero, one, or (rarely, e.g. handoff) more than one launch is in flight.
     */
    fun anyHoldsBackgroundPriority(): Boolean =
        synchronized(entries) { entries.values.toList() }.any { it.holdsBackgroundPriority() }

    fun register(
        serverId: String,
        itemId: String,
        timing: PlaybackLaunchTiming,
    ) = synchronized(entries) {
        entries.entries.removeAll { it.value.elapsedMs() > 120_000L }
        entries[serverId to itemId] = timing
        while (entries.size > 8) entries.remove(entries.keys.first())
    }

    fun find(
        serverId: String?,
        itemId: String?,
        playSessionId: String? = null,
    ): PlaybackLaunchTiming? =
        synchronized(entries) {
            entries[serverId to itemId]?.takeIf {
                it.elapsedMs() <= 120_000L && (playSessionId == null || it.matchesSession(playSessionId))
            }
        }

    fun remove(
        serverId: String,
        itemId: String,
    ) = synchronized(entries) { entries.remove(serverId to itemId) }
}
