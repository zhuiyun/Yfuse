package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Retirement outlives the UI that requested it. A timeout never grants a new decoder lease. */
internal class PlaybackEngineRetirements(
    private val scope: CoroutineScope,
    private val release: suspend (VideoEngine) -> Unit,
    private val completed: (VideoEngine) -> Boolean? = { null },
) {
    private class Retirement {
        val result = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<() -> Unit>()

        @Volatile var started = false
    }

    private val pending = linkedMapOf<VideoEngine, Retirement>()
    private var construction: CompletableDeferred<Unit>? = null

    @Synchronized
    fun retire(
        engine: VideoEngine,
        onComplete: () -> Unit = {},
    ) {
        if (engine is PreparingVideoEngine) return
        pending[engine]?.let {
            it.callbacks += onComplete
            return
        }
        val retirement = Retirement().also { it.callbacks += onComplete }
        pending[engine] = retirement
        scope.launch {
            try {
                retirement.started = true
                release(engine)
                check(completed(engine) != false) { "Previous playback resources are still releasing" }
                notifyReleased(retirement)
                retirement.result.complete(Unit)
            } catch (error: Throwable) {
                retirement.result.completeExceptionally(error)
            }
        }
    }

    suspend fun await() {
        while (true) {
            val building = synchronized(this) { construction }
            if (building != null) {
                building.await()
                synchronized(this) { if (construction === building) construction = null }
                continue
            }
            val entry = synchronized(this) { pending.entries.firstOrNull()?.let { it.key to it.value } } ?: return
            if (!entry.second.started || completed(entry.first) != true) entry.second.result.await()
            check(completed(entry.first) != false) { "Previous playback resources are still releasing" }
            notifyReleased(entry.second)
            synchronized(this) { pending.remove(entry.first) }
        }
    }

    suspend fun reserveConstruction(): CompletableDeferred<Unit> {
        while (true) {
            await()
            synchronized(this) {
                if (pending.isEmpty() && construction == null) {
                    return CompletableDeferred<Unit>().also { construction = it }
                }
            }
        }
    }

    private fun notifyReleased(retirement: Retirement) {
        val callbacks = synchronized(this) { retirement.callbacks.toList().also { retirement.callbacks.clear() } }
        callbacks.forEach { runCatching(it) }
    }
}

private fun VideoEngine.serializedRelease(): AndroidSerializedPlayerRelease? =
    this as? AndroidSerializedPlayerRelease
        ?: (this as? YPlayerVideoEngineAdapter)?.player as? AndroidSerializedPlayerRelease

internal object AndroidPlaybackEngineRetirements {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val registry =
        PlaybackEngineRetirements(
            scope = scope,
            release = { engine ->
                engine.release()
                engine.serializedRelease()?.releaseAndJoin()
            },
            completed = { it.serializedRelease()?.releaseCompleted },
        )
}

internal data class PlaybackEngineInput(
    val items: List<PlayerMediaItem>,
    val handover: PlaybackHandoverSnapshot,
)

internal class PlaybackEngineRequest(
    val input: PlaybackEngineInput,
    val kind: PlayerEngine? = null,
    val create: suspend (PlaybackEngineInput, String) -> VideoEngine,
)

internal data class PlaybackEngineBinding(
    val engine: VideoEngine,
    val input: PlaybackEngineInput,
    val crashOwner: String? = null,
    val kind: PlayerEngine? = null,
)

/** One presentation slot, with no native construction in Compose's remember calculation. */
internal class PlaybackEngineSlot(
    initial: PlaybackEngineInput,
    private val scope: CoroutineScope,
    private val retirements: PlaybackEngineRetirements,
    private val waitTimeoutMs: Long = 15_000L,
    private val newOwner: () -> String = { UUID.randomUUID().toString() },
    private val onAbandonedOwner: (String) -> Unit = {},
    private val onReleased: (PlaybackEngineBinding, Boolean) -> Unit = { _, _ -> },
) {
    private val mutableBinding = MutableStateFlow(PlaybackEngineBinding(PreparingVideoEngine(initial), initial))
    val binding: StateFlow<PlaybackEngineBinding> = mutableBinding.asStateFlow()
    private var request: PlaybackEngineRequest? = null
    private var build: Job? = null
    private var generation = 0L
    private var closed = false

    fun request(next: PlaybackEngineRequest) {
        if (closed || request === next) return
        request = next
        start(next, next.input)
    }

    private fun start(
        next: PlaybackEngineRequest,
        input: PlaybackEngineInput,
    ) {
        val token = ++generation
        build?.cancel()
        retire(mutableBinding.value)
        val preparing = PreparingVideoEngine(input)
        preparing.onRetry = { if (!closed && request === next) start(next, preparing.snapshot()) }
        mutableBinding.value = PlaybackEngineBinding(preparing, input, kind = next.kind)
        build =
            scope.launch {
                var created: VideoEngine? = null
                var published = false
                var owner: String? = null
                var construction: CompletableDeferred<Unit>? = null
                try {
                    withTimeout(waitTimeoutMs) { construction = retirements.reserveConstruction() }
                    coroutineContext.ensureActive()
                    if (closed || generation != token) return@launch
                    val snapshot = preparing.snapshot()
                    val allocatedOwner = newOwner().also { owner = it }
                    created = next.create(snapshot, allocatedOwner)
                    coroutineContext.ensureActive()
                    if (closed || generation != token) return@launch
                    // A suspending factory may have yielded while controls remained usable.
                    val latest = preparing.snapshot()
                    if (latest != snapshot) {
                        if (latest.items != snapshot.items) {
                            check(created.updateQueue(latest.items, latest.handover.itemIndex)) {
                                "播放列表已改变，请重试切换播放器"
                            }
                        } else if (latest.handover.itemIndex != snapshot.handover.itemIndex) {
                            created.selectItem(latest.handover.itemIndex)
                        }
                        created.seekTo(latest.handover.positionMs)
                        created.setSpeed(latest.handover.speed)
                        if (latest.handover.playbackRequested) created.play() else created.pause()
                    }
                    mutableBinding.value = PlaybackEngineBinding(created, latest, allocatedOwner, next.kind)
                    published = true
                } catch (cancelled: CancellationException) {
                    if (closed || generation != token || !coroutineContext[Job]!!.isActive) throw cancelled
                    preparing.failed("上一个播放器仍在释放资源，请稍后重试")
                } catch (error: Throwable) {
                    preparing.failed(error.message ?: "播放器切换未完成，请重试")
                } finally {
                    try {
                        if (!published) {
                            val abandoned = created
                            if (abandoned != null) {
                                retire(PlaybackEngineBinding(abandoned, preparing.snapshot(), owner))
                            } else {
                                owner?.let { runCatching { onAbandonedOwner(it) } }
                            }
                        }
                    } catch (error: Throwable) {
                        // A resource that failed to enter retirement must keep construction blocked.
                        construction?.completeExceptionally(error)
                        throw error
                    } finally {
                        construction?.complete(Unit)
                    }
                }
            }
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        build?.cancel()
        retire(mutableBinding.value)
    }

    private fun retire(binding: PlaybackEngineBinding) {
        val successful =
            binding.engine.state.value.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
        retirements.retire(binding.engine) { onReleased(binding, successful) }
    }
}

/** Keeps the complete PlayerRoot composition and user intent alive while resources retire. */
internal class PreparingVideoEngine(
    initial: PlaybackEngineInput,
) : VideoEngine {
    private var items = initial.items.toList()
    private var requested = initial.handover.playbackRequested
    private val handover = initial.handover
    private val mutableState =
        MutableStateFlow(
            PlaybackState(
                playing = false,
                buffering = true,
                currentIndex = handover.itemIndex,
                itemCount = items.size.coerceAtLeast(1),
                positionMs = handover.positionMs,
                speed = handover.speed,
                diagnostics = PlaybackDiagnostics(engine = "正在切换播放器"),
            ),
        )
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() = requested
    var onRetry: () -> Unit = {}

    fun snapshot(): PlaybackEngineInput =
        PlaybackEngineInput(
            items,
            handover.copy(
                itemIndex = state.value.currentIndex,
                positionMs = state.value.positionMs,
                playbackRequested = requested,
                speed = state.value.speed,
            ),
        )

    fun failed(message: String) {
        mutableState.update { it.copy(buffering = false, error = message, automaticFallbackBlocked = true) }
    }

    override fun play() {
        requested = true
    }

    override fun pause() {
        requested = false
    }

    override fun prepareForHandover() = Unit

    override fun seekTo(positionMs: Long) {
        mutableState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    override fun setSpeed(speed: Float) {
        if (speed.isFinite() && speed > 0f) mutableState.update { it.copy(speed = speed) }
    }

    override fun selectAudioTrack(id: String) = Unit

    override fun selectSubtitleTrack(id: String) = Unit

    override fun selectItem(index: Int) {
        if (index in items.indices) mutableState.update { it.copy(currentIndex = index, positionMs = 0L) }
    }

    override fun appendItems(items: List<PlayerMediaItem>): Boolean {
        this.items = this.items + items
        mutableState.update { it.copy(itemCount = this.items.size.coerceAtLeast(1)) }
        return true
    }

    override fun updateQueue(
        items: List<PlayerMediaItem>,
        currentIndex: Int,
    ): Boolean {
        if (currentIndex !in items.indices) return false
        this.items = items.toList()
        mutableState.update { it.copy(currentIndex = currentIndex, itemCount = items.size) }
        return true
    }

    override fun currentPositionMs(): Long = state.value.positionMs

    override fun retry() = onRetry()

    override fun release() = Unit
}
