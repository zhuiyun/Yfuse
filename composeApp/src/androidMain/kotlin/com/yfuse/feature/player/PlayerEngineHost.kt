package com.yfuse.feature.player

import androidx.compose.runtime.RememberObserver
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.android.AndroidPlayerReleaseBarrier
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns one playback backend for exactly as long as Compose remembers it.
 *
 * The backend used to be a bare `remember { createVideoEngine(...) }` value that a
 * `DisposableEffect` released. A composition abandoned before it applied never ran that effect, so
 * the decoder threads the constructor had already started were never released. A
 * [RememberObserver] is told about that case as well.
 *
 * The backend is still constructed while the host is being remembered rather than in
 * [onRemembered]: every effect below it reads the engine together with the keys it was built for
 * (`kind`, decoder mode, the Core2 session switches). One pass that paired new keys with the
 * outgoing engine would let the fallback chain blame, and then skip, an engine that never ran.
 * What must not overlap — the outgoing decoder and its replacement — is kept apart by
 * [PlayerEngineHandover] before those keys change at all.
 */
internal class PlayerEngineHost(
    val engine: VideoEngine,
    private val handover: PlayerEngineHandover,
) : RememberObserver {
    private val serializedRelease: AndroidSerializedPlayerRelease? = engine.serializedRelease()
    private var crashMonitorDisarmed = false

    /** True once [VideoEngine.release] has been asked for; the engine must not be read for a handover again. */
    var retired: Boolean = false
        private set

    /** Whether [VideoEngine.release] merely starts a teardown that finishes on another thread. */
    val releasesAsynchronously: Boolean get() = serializedRelease != null

    /**
     * Closes this backend's crash-attribution window. It has to happen before the replacement arms
     * its own: disarming afterwards cleared the replacement's context and credited its crash streak
     * with the outgoing engine's success.
     */
    fun disarmCrashMonitor() {
        if (crashMonitorDisarmed) return
        crashMonitorDisarmed = true
        AndroidNativeCrashMonitor.disarm(
            successful =
                engine.state.value.diagnostics.effectiveVideoReadiness ==
                    PlaybackOutputReadiness.Rendering,
        )
    }

    /** Idempotent. Every engine ignores a second [VideoEngine.release]. */
    fun retire() {
        if (retired) return
        retired = true
        disarmCrashMonitor()
        engine.release()
        serializedRelease?.let(handover::retire)
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = retire()

    override fun onAbandoned() = retire()
}

/** The part of a backend whose `release()` returns before its MediaCodec/native context is gone. */
private fun VideoEngine.serializedRelease(): AndroidSerializedPlayerRelease? =
    when (this) {
        is AndroidSerializedPlayerRelease -> this
        is YPlayerVideoEngineAdapter -> player as? AndroidSerializedPlayerRelease
        else -> null
    }

/**
 * Keeps a replacement backend from being constructed beside a decoder that is still tearing down.
 *
 * mpv and the Core2 routers finish their release on another thread. Building the next engine in
 * the same frame put two decoders and two AudioTracks side by side; on a constrained device the
 * new codec then failed to allocate and the failure was recorded against a healthy engine. A
 * rebuild therefore retires such a backend first and changes the engine keys only once it has
 * really let go. A backend whose release is synchronous keeps the old single-frame swap.
 *
 * Main-thread confined, like the composition state it commits.
 */
internal class PlayerEngineHandover(
    private val scope: CoroutineScope,
) {
    private val barriers = ArrayDeque<AndroidPlayerReleaseBarrier>()
    private val commits = ArrayList<() -> Unit>()
    private var drain: Job? = null

    /** True while a requested rebuild is still waiting for an outgoing decoder. */
    val inFlight: Boolean get() = drain?.isActive == true

    /** Registers a backend whose teardown has been started but not yet finished. */
    fun retire(release: AndroidSerializedPlayerRelease) {
        barriers.removeAll { it.idle }
        barriers.addLast(AndroidPlayerReleaseBarrier().also { it.retire(release) })
    }

    /**
     * Runs [commit] — the state writes that make Compose build the next backend — once nothing
     * retired is still releasing. Requests that arrive while one is waiting are committed in the
     * same turn as it, so they still produce a single rebuild.
     */
    fun rebuild(
        outgoing: PlayerEngineHost,
        reason: String,
        commit: () -> Unit,
    ) {
        outgoing.disarmCrashMonitor()
        if (outgoing.releasesAsynchronously) outgoing.retire()
        if (!inFlight && barriers.all { it.idle }) {
            commit()
            return
        }
        commits.add(commit)
        if (inFlight) return
        AppLog.info(
            category = "player.handover",
            event = "engine_rebuild_deferred",
            message = "Engine rebuild is waiting for the outgoing decoder to finish releasing",
            attributes =
                mapOf(
                    "reason" to reason,
                    "implementation" to outgoing.engine::class.java.name,
                ),
        )
        drain =
            scope.launch {
                awaitReleased(reason)
                val ready = commits.toList()
                commits.clear()
                ready.forEach { it() }
            }
    }

    private suspend fun awaitReleased(reason: String) {
        while (true) {
            val next = barriers.firstOrNull() ?: return
            try {
                next.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalStateException) {
                // Core2 fails closed on this inside its own router. The screen cannot: refusing to
                // build any backend would strand the viewer on a released player, which is worse
                // than the overlap the wait exists to avoid.
                AppLog.warning(
                    category = "player.handover",
                    event = "engine_release_barrier_timeout",
                    message = "Outgoing decoder did not confirm its release; rebuilding anyway",
                    throwable = failure,
                    attributes = mapOf("reason" to reason),
                )
            }
            barriers.remove(next)
        }
    }
}
