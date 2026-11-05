package com.yfuse.feature.player

import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.senplayer.SenPlayerSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, version-pinned SenPlayer runtime. Never silently substitutes another engine. */
internal class SenPlayerVideoEngine(
    context: Context,
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    startPlaybackRequested: Boolean,
    startSpeed: Float,
    private val decoderMode: DecoderMode,
    private val autoNext: Boolean,
    private val customUserAgent: String,
    private val videoCacheBytes: Long,
) : VideoEngine,
    AndroidSerializedPlayerRelease {
    private val appContext = context.applicationContext
    private var queue = items.toList()
    private val worker =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Yfuse-SenPlayer").apply { isDaemon = true }
        }
    private val released = AtomicBoolean(false)
    private val completed = CompletableDeferred<Unit>()

    @Volatile private var requested = startPlaybackRequested

    @Volatile private var session: SenPlayerSession? = null
    private var proxy: AndroidPlaybackHttpProxy? = null

    @Volatile private var attached: SenPlayerSurfaceHost? = null
    private val surfaceEpoch = AtomicLong()
    private var pauseAtEnd = false
    private val endGate = SenPlayerEndGate()
    private val videoGate = SenPlayerVideoReadinessGate()
    private var loadPending = true
    private var pendingSeekMs: Long? = null
    private var mediaPoll = 0
    private val bufferSession =
        java.util.UUID
            .randomUUID()
            .toString()
    private var bufferGeneration = 0L
    private val mutableState =
        MutableStateFlow(
            PlaybackState(
                currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                itemCount = items.size,
                positionMs = startPositionMs.coerceAtLeast(0),
                speed = startSpeed,
                diagnostics =
                    PlaybackDiagnostics(
                        engine = "SenPlayer（实验）",
                        decoder = "等待 SenPlayer 许可验证",
                        videoReadiness = PlaybackOutputReadiness.Unknown,
                        audioReadiness = PlaybackOutputReadiness.Unknown,
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() =
        requested &&
            !released.get() &&
            !state.value.ended &&
            state.value.error == null
    override val releaseCompleted: Boolean get() = completed.isCompleted && !completed.isCancelled

    private fun submit(block: () -> Unit) {
        if (released.get()) return
        try {
            worker.execute {
                if (!released.get()) {
                    try {
                        block()
                    } catch (error: Exception) {
                        fail(error)
                    } catch (error: LinkageError) {
                        fail(error)
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            if (!released.get()) fail(error)
        }
    }

    private fun fail(error: Throwable) {
        if (released.get()) return
        val license =
            generateSequence(error) {
                it.cause
            }.take(8).filterIsInstance<SenPlayerSession.LicenseUnavailable>().firstOrNull()
        mutableState.update {
            if (released.get()) return@update it
            it.forSenPlayerFailure(
                message =
                    if (license != null) {
                        "SenPlayer 许可未通过（${license.status}）。需要原许可服务允许当前 Yfuse 版本；请手动选择其他内核。"
                    } else {
                        "SenPlayer 实验内核不可用（${error.javaClass.simpleName}），请手动选择其他内核。"
                    },
                kind = if (license != null) PlaybackFailureKind.Authorization else PlaybackFailureKind.Unknown,
            )
        }
        session?.pauseForFailure()
        session?.cancelPendingRead()
    }

    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val view = attached ?: return
                if (!view.owns(holder)) return
                val epoch = surfaceEpoch.incrementAndGet()
                submit {
                    if (attached !== view || surfaceEpoch.get() != epoch) return@submit
                    if (state.value.error != null) return@submit
                    if (!view.ready) {
                        detachNativeSurface()
                        return@submit
                    }
                    if (session?.isUsable() == false) closeSession()
                    if (session == null) {
                        mutableState.update { it.copy(buffering = true, error = null) }
                        val options =
                            linkedMapOf(
                                "hwdec" to (if (decoderMode == DecoderMode.Software) "no" else "auto"),
                                "user-agent" to customUserAgent,
                            )
                        val created =
                            try {
                                SenPlayerSession(
                                    appContext,
                                    view.video.holder.surface,
                                    view.subtitles.holder.surface,
                                    view.display,
                                    options,
                                )
                            } catch (error: Exception) {
                                if (released.get() || attached !== view || surfaceEpoch.get() != epoch) return@submit
                                throw error
                            }
                        if (released.get() || attached !== view || surfaceEpoch.get() != epoch || !view.ready) {
                            created.close()
                            return@submit
                        }
                        session = created
                        proxy = AndroidPlaybackHttpProxy(appContext, customUserAgent, videoCacheBytes)
                        loadCurrent()
                    } else {
                        val current = requireNotNull(session)
                        videoGate.reset(current.sourceId(), current.snapshot().surfaceDraws)
                        current.surface(view.video.holder.surface, view.subtitles.holder.surface)
                        if (loadPending) loadCurrent() else current.play(requested)
                    }
                    if (attached === view && surfaceEpoch.get() == epoch && !released.get()) {
                        session?.resize(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                    }
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                val view = attached ?: return
                if (!view.owns(holder)) return
                val epoch = surfaceEpoch.get()
                submit {
                    if (attached === view && surfaceEpoch.get() == epoch && view.ready && state.value.error == null) {
                        session?.resize(view.video.width.coerceAtLeast(1), view.video.height.coerceAtLeast(1))
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val view = attached ?: return
                if (!view.owns(holder)) return
                val epoch = surfaceEpoch.incrementAndGet()
                submit {
                    if (attached === view && surfaceEpoch.get() == epoch) {
                        detachNativeSurface()
                    }
                }
            }
        }

    fun attach(view: SenPlayerSurfaceHost) {
        if (released.get()) return
        if (attached === view) return
        attached?.removeCallbacks(surfaceCallback)
        attached = view
        surfaceEpoch.incrementAndGet()
        view.addCallbacks(surfaceCallback)
        surfaceCallback.surfaceCreated(view.video.holder)
    }

    fun detach(view: SenPlayerSurfaceHost) {
        view.removeCallbacks(surfaceCallback)
        if (attached === view) {
            attached = null
            val epoch = surfaceEpoch.incrementAndGet()
            submit {
                if (attached == null && surfaceEpoch.get() == epoch) {
                    detachNativeSurface()
                }
            }
        }
    }

    private fun detachNativeSurface() {
        videoGate.reset(session?.sourceId() ?: 0L)
        session?.takeIf { it.isUsable() }?.surface(null, null)
        mutableState.update {
            if (released.get() || it.error != null) {
                it
            } else {
                it.copy(
                    diagnostics = it.diagnostics.copy(videoReadiness = PlaybackOutputReadiness.Waiting),
                )
            }
        }
    }

    private fun loadCurrent() {
        if (released.get()) return
        val item = queue.getOrNull(state.value.currentIndex) ?: error("Empty SenPlayer queue")
        loadPending = true
        endGate.reset()
        videoGate.reset()
        bufferGeneration++
        mediaPoll = 0
        mutableState.update { it.forSenPlayerLoad(bufferSession, bufferGeneration) }
        require(item.drmConfiguration == null) { "SenPlayer DRM is not integrated" }
        require(item.transportCredentials == null) { "SenPlayer file transport credentials require YCore" }
        if (session?.isUsable() == false) closeSession()
        val current = session
        if (current == null) {
            attached
                ?.takeIf { it.ready }
                ?.video
                ?.holder
                ?.let(surfaceCallback::surfaceCreated)
            return
        }
        val upstream = item.url
        val url =
            if (upstream.startsWith("http://", true) || upstream.startsWith("https://", true)) {
                requireNotNull(proxy).localUrl(
                    upstream,
                    cacheable = item.persistentPlaybackCacheUrl(item.startsWithServerTranscode()) == upstream.trim(),
                    widgetRequest = item.widgetPlaybackRequest(),
                )
            } else {
                require(item.customHeaders == null) { "Custom headers require an HTTP source" }
                upstream
            }
        val previousDraws = current.snapshot().surfaceDraws
        current.open(url, emptyMap(), if (pendingSeekMs == null) state.value.positionMs else 0, requested)
        if (released.get()) return
        videoGate.reset(current.sourceId(), previousDraws)
        current.option("speed", state.value.speed.toString())
        // A pause can arrive while original native open is blocking on this worker.
        current.play(requested)
        loadPending = false
    }

    init {
        worker.scheduleWithFixedDelay({
            if (!released.get() && !loadPending && session != null && state.value.error == null) {
                try {
                    poll()
                } catch (error: Exception) {
                    fail(error)
                } catch (error: LinkageError) {
                    fail(error)
                }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        val current = session ?: return
        val sample = current.snapshot()
        if (released.get() || sample.source == 0L || sample.source != current.sourceId()) return
        if (sample.phase == "failed" || sample.errorCode != 0) {
            fail(IllegalStateException("native_${sample.errorCode}"))
            return
        }
        val target = senPlayerPendingSeekTarget(pendingSeekMs, sample.durationMs, sample.phase)
        if (target != null) {
            current.seek(target)
            pendingSeekMs = null
            videoGate.reset(current.sourceId(), sample.surfaceDraws)
            mutableState.update { if (released.get()) it else it.copy(positionMs = target, ended = false) }
            return
        }
        val ended = sample.phase == "ended"
        val rendered =
            videoGate.observe(sample.source, sample.surfaceDraws, sample.width, sample.height, attached?.ready == true)
        val cache =
            senPlayerCacheState(
                bufferSession,
                bufferGeneration,
                sample.cacheSnapshotAvailable,
                sample.cacheRanges,
                sample.positionMs,
                sample.durationMs,
                sample.cacheEndMs,
                sample.cacheDurationMs,
            )
        mutableState.update {
            if (released.get()) return@update it
            it.copy(
                playing = requested && sample.phase == "playing",
                buffering = sample.phase in setOf("loading", "buffering", "seeking"),
                positionMs = senPlayerReportedPosition(pendingSeekMs, sample.positionMs, sample.phase),
                durationMs = sample.durationMs.coerceAtLeast(0),
                bufferedPositionMs = cache.endMs,
                bufferSnapshot = cache.snapshot,
                videoHeight = sample.height,
                ended = ended,
                diagnostics =
                    it.diagnostics.copy(
                        decoder = sample.decoder.ifBlank { "SenPlayer 原生解码" },
                        videoWidth = sample.width,
                        videoReadiness =
                            if (rendered) PlaybackOutputReadiness.Rendering else PlaybackOutputReadiness.Waiting,
                        videoOutput = sample.videoOutput,
                        audioOutput = sample.audioOutput,
                        bufferedDurationMs = cache.durationMs,
                    ),
            )
        }
        if (released.get()) return
        if (endGate.advance(ended, requested, autoNext, pauseAtEnd, state.value.hasNext)) {
            pendingSeekMs = null
            mutableState.update { it.copy(currentIndex = it.currentIndex + 1, positionMs = 0) }
            loadCurrent()
            return
        }
        if (!ended && mediaPoll++ % 8 == 0) updateTracks(current.media())
    }

    private fun updateTracks(media: JSONObject) {
        val tracks = media.optJSONArray("tracks") ?: return

        fun ofKind(kind: String): List<EngineTrack> =
            buildList {
                for (index in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(index)
                    if (track.optString("kind") == kind) {
                        add(
                            EngineTrack(
                                id = track.getLong("id").toString(),
                                label =
                                    track
                                        .optString(
                                            "title",
                                        ).ifBlank {
                                            track
                                                .optString(
                                                    "language",
                                                ).ifBlank { "$kind ${track.getLong("id")}" }
                                        },
                                language = track.optString("language").ifBlank { null },
                                selected = track.optBoolean("selected"),
                                codec = track.optString("codec").ifBlank { null },
                                forced = if (track.has("forced")) track.optBoolean("forced") else null,
                            ),
                        )
                    }
                }
            }
        mutableState.update { it.copy(audioTracks = ofKind("audio"), subtitleTracks = ofKind("sub")) }
    }

    override fun play() {
        if (released.get()) return
        requested = true
        submit {
            if (state.value.error != null) return@submit
            if (state.value.ended) {
                mutableState.update { it.copy(positionMs = 0) }
                loadCurrent()
            } else {
                session?.play(requested)
            }
        }
    }

    override fun pause() {
        if (released.get()) return
        requested = false
        mutableState.update { it.copy(playing = false) }
        submit { if (state.value.error == null) session?.play(requested) }
    }

    override fun seekTo(positionMs: Long) {
        submit {
            val position = senPlayerSeekPosition(positionMs, state.value.durationMs)
            val wasEnded = state.value.ended
            val current = session?.takeIf { state.value.error == null && !loadPending && state.value.durationMs > 0 }
            mutableState.update { it.copy(positionMs = position, ended = false) }
            endGate.reset()
            if (wasEnded && current != null) {
                pendingSeekMs = null
                loadCurrent()
                return@submit
            }
            if (current != null) {
                val draws = current.snapshot().surfaceDraws
                current.seek(position)
                pendingSeekMs = null
                videoGate.reset(current.sourceId(), draws)
            } else {
                pendingSeekMs = position
                // Saving intent has not moved the native source or invalidated its displayed frame.
            }
        }
    }

    override fun setSpeed(speed: Float) {
        if (!speed.isFinite() || speed <= 0) return
        submit {
            session?.option("speed", speed.toString())
            mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun selectAudioTrack(id: String) {
        submit { session?.track(0, id) }
    }

    override fun selectSubtitleTrack(id: String) {
        submit { session?.track(1, id) }
    }

    override val supportsAudioDelay: Boolean get() = true

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        submit { session?.option("audio-delay", (delayMs / 1000.0).toString()) }
        return true
    }

    override val supportsSubtitleOffset: Boolean get() = true

    override fun setSubtitleOffsetMs(offsetMs: Long): Boolean {
        submit { session?.option("sub-delay", (offsetMs / 1000.0).toString()) }
        return true
    }

    override fun setPauseAtEndOfCurrentItem(enabled: Boolean) {
        submit { pauseAtEnd = enabled }
    }

    override fun selectItem(index: Int) {
        submit {
            if (index !in queue.indices) return@submit
            pendingSeekMs = null
            mutableState.update { it.copy(currentIndex = index, positionMs = 0) }
            loadCurrent()
        }
    }

    override fun currentPositionMs(): Long = state.value.positionMs

    override fun retry() {
        submit { loadCurrent() }
    }

    override fun appendItems(items: List<PlayerMediaItem>): Boolean {
        if (released.get()) return false
        val additions = items.toList()
        submit {
            queue = queue + additions
            mutableState.update { it.copy(itemCount = queue.size) }
        }
        return true
    }

    private fun closeSession() {
        try {
            session?.close()
        } finally {
            session = null
            try {
                proxy?.close()
            } finally {
                proxy = null
            }
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        requested = false
        attached?.removeCallbacks(surfaceCallback)
        attached = null
        surfaceEpoch.incrementAndGet()
        mutableState.update {
            it.copy(
                playing = false,
                buffering = false,
                bufferedPositionMs = 0,
                bufferSnapshot = YBufferSnapshot(sessionId = bufferSession, generation = bufferGeneration),
                diagnostics =
                    it.diagnostics.copy(
                        videoReadiness = PlaybackOutputReadiness.Released,
                        bufferedDurationMs = 0,
                    ),
            )
        }
        session?.cancelPendingRead()
        worker.execute {
            try {
                closeSession()
                completed.complete(Unit)
            } catch (error: Throwable) {
                completed.completeExceptionally(error)
            } finally {
                worker.shutdownNow()
            }
        }
    }

    override suspend fun releaseAndJoin() {
        release()
        completed.await()
    }
}

@Composable
internal fun SenPlayerSurface(
    engine: SenPlayerVideoEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> SenPlayerSurfaceHost(context).also(engine::attach) },
        modifier = modifier,
        update = engine::attach,
        onRelease = engine::detach,
    )
}

/** Original vp3 layout uses a dedicated transparent native subtitle output above video. */
internal class SenPlayerSurfaceHost(
    context: Context,
) : FrameLayout(context) {
    val video = SurfaceView(context)
    val subtitles =
        SurfaceView(context).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
        }
    val ready: Boolean get() = video.holder.surface.isValid && subtitles.holder.surface.isValid

    fun owns(holder: SurfaceHolder): Boolean = holder === video.holder || holder === subtitles.holder

    init {
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitles, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun addCallbacks(callback: SurfaceHolder.Callback) {
        video.holder.addCallback(callback)
        subtitles.holder.addCallback(callback)
    }

    fun removeCallbacks(callback: SurfaceHolder.Callback) {
        video.holder.removeCallback(callback)
        subtitles.holder.removeCallback(callback)
    }
}
