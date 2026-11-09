package com.yfuse.feature.player

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.yfuse.core.data.SenPlayerEnhancementLevel
import com.yfuse.core.data.SenPlayerPictureSettings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.senplayer.SenPlayerSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
    private val pictureSettings: StateFlow<SenPlayerPictureSettings>? = null,
    private val initialTrackSelections: Map<String, YInitialTrackSelection> = emptyMap(),
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
    private val surfaceProbe = SenPlayerSurfaceProbe()
    private val outputGeneration = AtomicLong()
    private var boundSurfaceEpoch = -1L
    private var outputWatchdog = SenPlayerOutputWatchdog()
    private var rebuffers = SenPlayerRebufferTracker()
    private var surfaceRecoveries = 0
    private var nextOutputLogAt = 0L
    private var lastOutputPhase = ""
    private var loadPending = true
    private var pendingSeekMs: Long? = null
    private var mediaPoll = 0
    private val subtitleScope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    private val subtitles = SenPlayerSubtitles(appContext, subtitleScope, { mediaPoll = 0 }, ::fail)
    private val pictureController = SenPlayerPictureController()
    private var pictureDynamicRange: String? = null
    private var pictureDolbyProfile = 0
    private var pictureUpscalingAvailable: Boolean? = null
    private var pictureDurationMs = 0L
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
                        senPlayerPicture = SenPlayerPictureState(),
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

    private val surfaceCallback: SurfaceHolder.Callback =
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
                                "sub-fonts-dir" to "/system/fonts",
                                "secondary-sub-fonts-dir" to "/system/fonts",
                                "sub-font-provider" to "none",
                                "secondary-sub-font-provider" to "none",
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
                        boundSurfaceEpoch = epoch
                        proxy = AndroidPlaybackHttpProxy(appContext, customUserAgent, videoCacheBytes)
                        loadCurrent()
                    } else {
                        val current = requireNotNull(session)
                        resetVideoEvidence(current.sourceId(), current.snapshot(), "surface_created")
                        current.surface(view.video.holder.surface, view.subtitles.holder.surface)
                        subtitles.surfaceChanged()
                        boundSurfaceEpoch = epoch
                        pictureController.reset()
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
                        if (session == null) {
                            surfaceCallback.surfaceCreated(holder)
                            return@submit
                        }
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
        boundSurfaceEpoch = -1L
        subtitles.surfaceChanged()
        pictureController.reset()
        resetVideoEvidence(session?.sourceId() ?: 0L, reason = "surface_detached")
        session?.takeIf { it.isUsable() }?.surface(null, null)
        mutableState.update {
            if (released.get() || it.error != null) {
                it
            } else {
                it.copy(
                    diagnostics =
                        it.diagnostics.copy(
                            videoReadiness = PlaybackOutputReadiness.Waiting,
                            senPlayerPicture = pictureController.state,
                        ),
                )
            }
        }
    }

    private fun loadCurrent() {
        if (released.get()) return
        val item = queue.getOrNull(state.value.currentIndex) ?: error("Empty SenPlayer queue")
        loadPending = true
        endGate.reset()
        outputWatchdog = SenPlayerOutputWatchdog()
        rebuffers = SenPlayerRebufferTracker()
        surfaceRecoveries = 0
        bufferGeneration++
        mediaPoll = 0
        pictureController.reset()
        pictureDynamicRange = null
        pictureDolbyProfile = 0
        pictureUpscalingAvailable = null
        pictureDurationMs = 0L
        mutableState.update { it.forSenPlayerLoad(bufferSession, bufferGeneration, item) }
        resetVideoEvidence(0L, reason = "source_loading")
        subtitles.clear()
        val upstream = item.senPlayerPlaybackUrl()
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
        val previousOutput = current.snapshot()
        // Remove the previous source's effects before native open starts the next file.
        applyPictureSettings(current)
        current.open(url, emptyMap(), if (pendingSeekMs == null) state.value.positionMs else 0, requested)
        if (released.get()) return
        subtitles.open(current, item, customUserAgent, initialTrackSelections[item.id])
        resetVideoEvidence(current.sourceId(), previousOutput, "source_opened")
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
        applyPictureSettings(current)
        val sample = current.snapshot()
        if (released.get() || sample.source == 0L || sample.source != current.sourceId()) return
        if (sample.phase == "failed" || sample.errorCode != 0) {
            fail(IllegalStateException("native_${sample.errorCode}"))
            return
        }
        pictureUpscalingAvailable = sample.upscalingAvailable
        pictureDurationMs = sample.durationMs
        val target = senPlayerPendingSeekTarget(pendingSeekMs, sample.durationMs, sample.phase)
        if (target != null) {
            beginVideoSeek(current.sourceId(), sample)
            current.seek(target)
            pendingSeekMs = null
            mutableState.update { if (released.get()) it else it.copy(positionMs = target, ended = false) }
            return
        }
        val ended = sample.phase == "ended"
        val now = SystemClock.elapsedRealtime()
        val epoch = surfaceEpoch.get()
        val view = attached
        val surfaceReady = view?.ready == true && boundSurfaceEpoch == epoch
        val token = SenPlayerOutputToken(sample.source, outputGeneration.get(), epoch)
        val pixels = surfaceProbe.result(token)
        val rendered =
            videoGate.observe(
                sample.source,
                sample.surfaceDraws,
                sample.width,
                sample.height,
                surfaceReady,
                surfaceFrames = sample.surfaceFrames,
                changingPicture = pixels?.changingPicture == true,
            )
        rebuffers.observe(now, sample.phase, requested)
        val outputAction =
            outputWatchdog.observe(
                now,
                eligible =
                    surfaceReady && requested && sample.phase == "playing" && sample.width > 0 && sample.height > 0,
                rendered = rendered,
                positionMs = sample.positionMs,
                decodedFrames = sample.videoFrames,
            )
        if (!rendered &&
            surfaceReady &&
            view != null &&
            sample.width > 0 &&
            sample.height > 0 &&
            requested &&
            sample.phase == "playing"
        ) {
            surfaceProbe.request(view.video, token) {
                !released.get() &&
                    attached === view &&
                    surfaceEpoch.get() == token.surfaceEpoch &&
                    outputGeneration.get() == token.generation &&
                    session?.sourceId() == token.source
            }
        }
        recordOutputSample(sample, token, surfaceReady, rendered, pixels, now, outputAction)
        if (outputAction == SenPlayerOutputAction.Rebind && view != null && surfaceReady) {
            recoverVideoSurface(current, sample, view, epoch)
            return
        }
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
            val currentRendered =
                rendered &&
                    attached === view &&
                    view?.ready == true &&
                    surfaceEpoch.get() == epoch &&
                    boundSurfaceEpoch == epoch
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
                        senPlayerPicture =
                            pictureController.state.copy(
                                sdrToHdr =
                                    pictureController.state.sdrToHdr.copy(
                                        active = sample.sdrToHdrActive.takeIf { currentRendered },
                                    ),
                                upscaling =
                                    pictureController.state.upscaling.copy(
                                        active = sample.upscalingActive.takeIf { currentRendered },
                                    ),
                            ),
                        decoder = sample.decoder.ifBlank { "SenPlayer 原生解码" },
                        videoWidth = sample.width,
                        videoReadiness =
                            if (currentRendered) PlaybackOutputReadiness.Rendering else PlaybackOutputReadiness.Waiting,
                        videoOutput =
                            when {
                                currentRendered -> sample.videoOutput.ifBlank { "视频画面已输出" }
                                outputWatchdog.warning -> "未确认视频画面，请尝试切换解码方式或内核"
                                else -> "正在确认视频画面"
                            },
                        audioOutput = sample.audioOutput,
                        bufferedDurationMs = cache.durationMs,
                        bufferEvents = rebuffers.events,
                        rebufferDurationMs = rebuffers.durationMs,
                        longestRebufferMs = rebuffers.longestMs,
                        outputEvidence =
                            it.diagnostics.outputEvidence.copy(
                                surfaceRebuildCount = surfaceRecoveries,
                                rendererDetail =
                                    "SenPlayer decoded=${sample.videoFrames} surface=${sample.surfaceFrames} " +
                                        "draws=${sample.surfaceDraws} pixelCopy=${pixels?.result ?: "unavailable"} " +
                                        "pixelChanging=${pixels?.changingPicture == true}",
                            ),
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

    private fun resetVideoEvidence(
        source: Long,
        sample: SenPlayerSession.Snapshot? = null,
        reason: String,
    ) {
        val generation = outputGeneration.incrementAndGet()
        surfaceProbe.invalidate()
        videoGate.reset(source, sample?.surfaceDraws ?: 0, sample?.surfaceFrames ?: 0)
        nextOutputLogAt = 0
        mutableState.update {
            if (released.get()) {
                it
            } else {
                it.copy(
                    diagnostics =
                        it.diagnostics.copy(
                            videoReadiness = PlaybackOutputReadiness.Waiting,
                            senPlayerPicture = it.diagnostics.senPlayerPicture?.withoutActiveEvidence(),
                            outputEvidenceGeneration = generation,
                            outputEvidenceResetReason = reason,
                        ),
                )
            }
        }
    }

    private fun beginVideoSeek(
        source: Long,
        sample: SenPlayerSession.Snapshot,
    ) {
        rebuffers.seeking(SystemClock.elapsedRealtime())
        outputWatchdog = SenPlayerOutputWatchdog()
        resetVideoEvidence(source, sample, "seek")
    }

    private fun recoverVideoSurface(
        current: SenPlayerSession,
        sample: SenPlayerSession.Snapshot,
        view: SenPlayerSurfaceHost,
        epoch: Long,
    ) {
        if (released.get() || attached !== view || surfaceEpoch.get() != epoch || !view.ready) return
        resetVideoEvidence(sample.source, sample, "no_picture_surface_rebind")
        current.surface(null, null)
        // Surface destruction can race the native detach. Its queued callback owns cleanup.
        if (released.get() || attached !== view || surfaceEpoch.get() != epoch || !view.ready) return
        current.surface(view.video.holder.surface, view.subtitles.holder.surface)
        pictureController.reset()
        subtitles.surfaceChanged()
        current.resize(view.video.width.coerceAtLeast(1), view.video.height.coerceAtLeast(1))
        current.play(requested)
        surfaceRecoveries++
    }

    private fun recordOutputSample(
        sample: SenPlayerSession.Snapshot,
        token: SenPlayerOutputToken,
        surfaceReady: Boolean,
        rendered: Boolean,
        pixels: SenPlayerSurfaceSample?,
        now: Long,
        action: SenPlayerOutputAction,
    ) {
        if (now < nextOutputLogAt && lastOutputPhase == sample.phase && action == SenPlayerOutputAction.None) return
        nextOutputLogAt = now + 5_000
        lastOutputPhase = sample.phase
        val attributes =
            mapOf(
                "source" to token.source.toString(),
                "generation" to token.generation.toString(),
                "surfaceEpoch" to token.surfaceEpoch.toString(),
                "phase" to sample.phase,
                "positionMs" to sample.positionMs.toString(),
                "decoder" to sample.decoder,
                "nativeVideoOutput" to sample.videoOutput,
                "width" to sample.width.toString(),
                "height" to sample.height.toString(),
                "decodedFrames" to sample.videoFrames.toString(),
                "surfaceFrames" to sample.surfaceFrames.toString(),
                "surfaceDraws" to sample.surfaceDraws.toString(),
                "surfaceReady" to surfaceReady.toString(),
                "rendered" to rendered.toString(),
                "pixelCopyResult" to (pixels?.result?.toString() ?: "unavailable"),
                "pixelLumaRange" to (pixels?.lumaRange?.toString() ?: "unavailable"),
                "pixelChanging" to (pixels?.changingPicture == true).toString(),
                "rebufferEvents" to rebuffers.events.toString(),
                "rebufferMs" to rebuffers.durationMs.toString(),
                "action" to action.name,
            )
        if (action == SenPlayerOutputAction.None) {
            AppLog.info("player.senplayer", "video_output_sample", "SenPlayer video output evidence", attributes)
        } else {
            AppLog.warning(
                category = "player.senplayer",
                event = "video_output_recovery",
                message = "SenPlayer has media progress without confirmed video output",
                attributes = attributes,
            )
        }
    }

    private fun updateTracks(media: JSONObject) {
        val tracks = media.optJSONArray("tracks") ?: return
        val videoTracks =
            (0 until tracks.length())
                .mapNotNull { tracks.optJSONObject(it) }
                .filter { it.optString("kind") == "video" }
        val video = videoTracks.firstOrNull { it.optBoolean("selected") } ?: videoTracks.firstOrNull()
        pictureDynamicRange = video?.optString("dynamicRange")?.takeIf { it.isNotBlank() }
        pictureDolbyProfile = video?.optInt("dvProfile") ?: 0

        var secondaryTrackId: String? = null

        fun ofKind(kind: String): List<EngineTrack> =
            buildList {
                for (index in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(index)
                    if (track.optString("kind") == kind) {
                        val nativeId = track.getLong("id").toString()
                        val id = if (kind == "sub") subtitles.publicId(nativeId) else nativeId
                        val selected = track.optBoolean("selected")
                        val secondary = kind == "sub" && selected && track.optInt("slot", 0) == 1
                        if (secondary) secondaryTrackId = id
                        add(
                            EngineTrack(
                                id = id,
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
                                selected = selected && !secondary,
                                codec = track.optString("codec").ifBlank { null },
                                forced = if (track.has("forced")) track.optBoolean("forced") else null,
                            ),
                        )
                    }
                }
            }
        val audio = ofKind("audio")
        val captions = subtitles.merge(ofKind("sub"))
        if (state.value.subtitleTracks != captions || state.value.secondarySubtitleTrackId != secondaryTrackId) {
            AppLog.info(
                "player.senplayer",
                "subtitle_tracks_changed",
                "Subtitle tracks reported by current source",
                mapOf(
                    "embedded" to captions.count { it.id.toLongOrNull() != null }.toString(),
                    "external" to captions.count { it.id.toLongOrNull() == null }.toString(),
                    "primary" to (captions.firstOrNull { it.selected }?.id ?: "off"),
                    "secondary" to (secondaryTrackId ?: "off"),
                ),
            )
        }
        mutableState.update {
            it.copy(audioTracks = audio, subtitleTracks = captions, secondarySubtitleTrackId = secondaryTrackId)
        }
        if (tracks.length() > 0) {
            subtitles.observeSecondary(secondaryTrackId != null)
            subtitles.selectDefault(captions)
        }
    }

    private fun applyPictureSettings(current: SenPlayerSession) {
        val view = attached ?: return
        val epoch = surfaceEpoch.get()

        fun valid() =
            !released.get() &&
                attached === view &&
                view.ready &&
                surfaceEpoch.get() == epoch &&
                boundSurfaceEpoch == epoch &&
                session === current
        if (!valid()) return
        val supportsHdr10 =
            runCatching {
                view.display
                    ?.takeIf { it.isValid }
                    ?.hdrCapabilities
                    ?.supportedHdrTypes
                    ?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
            }.getOrDefault(false)
        val eligibility =
            senPlayerPictureEligibility(
                displaySupportsHdr10 = supportsHdr10,
                dynamicRange = pictureDynamicRange,
                dolbyVisionProfile = pictureDolbyProfile,
                durationMs = pictureDurationMs,
                upscalingAvailable = pictureUpscalingAvailable,
            )
        val result =
            pictureController.apply(
                pictureSettings?.value ?: SenPlayerPictureSettings(),
                eligibility,
            ) { feature, level ->
                if (valid()) sendPictureOption(current, feature, level) else SenPlayerEnhancementState(level)
            }
        if (!valid()) {
            pictureController.reset()
            return
        }
        // Preserve an observed active flag only while this exact option acknowledgement is unchanged.
        mutableState.update { previous ->
            if (!valid()) return@update previous
            val old = previous.diagnostics.senPlayerPicture

            fun preserveActive(
                next: SenPlayerEnhancementState,
                before: SenPlayerEnhancementState?,
            ) = if (before?.copy(active = null) == next) next.copy(active = before.active) else next
            previous.copy(
                diagnostics =
                    previous.diagnostics.copy(
                        senPlayerPicture =
                            result.copy(
                                sdrToHdr = preserveActive(result.sdrToHdr, old?.sdrToHdr),
                                upscaling = preserveActive(result.upscaling, old?.upscaling),
                            ),
                    ),
            )
        }
    }

    private fun sendPictureOption(
        current: SenPlayerSession,
        feature: SenPlayerPictureFeature,
        level: SenPlayerEnhancementLevel,
    ): SenPlayerEnhancementState =
        try {
            when (feature) {
                SenPlayerPictureFeature.Upscaling -> current.setUpscalingLevel(level.nativeValue)
                SenPlayerPictureFeature.SdrToHdr -> current.setSdrToHdrLevel(level.nativeValue)
            }
            SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Accepted)
        } catch (error: SenPlayerSession.EnhancementOptionRejected) {
            SenPlayerEnhancementState(level, SenPlayerEnhancementStatus.Rejected, nativeCode = error.nativeCode)
        } catch (error: Exception) {
            // A timed-out/cancelled native operation retires its session; never hide that as an optional failure.
            if (!current.isUsable()) throw error
            SenPlayerEnhancementState(
                level,
                SenPlayerEnhancementStatus.Failed,
                detail = "应用失败（${error.javaClass.simpleName}）",
            )
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
                beginVideoSeek(current.sourceId(), current.snapshot())
                current.seek(position)
                pendingSeekMs = null
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
        submit { subtitles.select(SenPlayerSubtitles.PRIMARY, id) }
    }

    override val supportsAudioDelay: Boolean get() = true

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        submit { session?.option("audio-delay", (delayMs / 1000.0).toString()) }
        return true
    }

    override val supportsSubtitleOffset: Boolean get() = true
    override val supportsSecondarySubtitleTrack: Boolean get() = true
    override val supportsSecondarySubtitleOffset: Boolean get() = true
    override val supportsSubtitlePosition: Boolean get() = true
    override val supportsSubtitleScale: Boolean get() = true
    override val supportsSubtitleAppearance: Boolean get() = true
    override val supportsSubtitleBrightness: Boolean get() = true

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        if (released.get()) return false
        submit { subtitles.select(SenPlayerSubtitles.SECONDARY, id) }
        return true
    }

    private fun updateSubtitleStyle(change: (SenSubtitleStyle) -> SenSubtitleStyle): Boolean {
        if (released.get()) return false
        submit { subtitles.setStyle(change(subtitles.style)) }
        return true
    }

    override fun setSubtitleOffsetMs(offsetMs: Long): Boolean =
        updateSubtitleStyle { it.copy(primaryDelayMs = offsetMs) }

    override fun setSecondarySubtitleOffsetMs(offsetMs: Long): Boolean =
        updateSubtitleStyle {
            mutableState.update { state -> state.copy(secondarySubtitleOffsetMs = offsetMs) }
            it.copy(secondaryDelayMs = offsetMs)
        }

    override fun setSubtitleScale(scale: Float): Boolean = updateSubtitleStyle { it.copy(primaryScale = scale) }

    override fun setSubtitlePosition(position: Float): Boolean = updateSubtitleStyle { it.copy(position = position) }

    fun setSubtitleMargins(useBlackBar: Boolean): Boolean = updateSubtitleStyle { it.copy(useBlackBar = useBlackBar) }

    fun setSecondarySubtitleScale(scale: Float): Boolean = updateSubtitleStyle { it.copy(secondaryScale = scale) }

    override fun setSubtitleBrightness(brightness: Float): Boolean =
        updateSubtitleStyle { it.copy(brightness = brightness) }

    override fun setSubtitleAppearance(appearance: SubtitleAppearance): Boolean =
        updateSubtitleStyle { it.copy(appearance = appearance) }

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
            subtitles.clear()
            pictureController.reset()
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
        outputGeneration.incrementAndGet()
        surfaceProbe.invalidate()
        mutableState.update {
            it.copy(
                playing = false,
                buffering = false,
                bufferedPositionMs = 0,
                bufferSnapshot = YBufferSnapshot(sessionId = bufferSession, generation = bufferGeneration),
                diagnostics =
                    it.diagnostics.copy(
                        videoReadiness = PlaybackOutputReadiness.Released,
                        senPlayerPicture = null,
                        bufferedDurationMs = 0,
                    ),
            )
        }
        subtitleScope.cancel()
        session?.cancelPendingRead()
        worker.execute {
            try {
                closeSession()
                completed.complete(Unit)
            } catch (error: Throwable) {
                completed.completeExceptionally(error)
            } finally {
                worker.shutdown()
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
    controls: SubtitleControlState,
    videoSize: IntSize,
    modifier: Modifier = Modifier,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    val picture = core2SurfaceSize(container = container, video = videoSize, scaleMode = VideoScaleMode.Fit)
    val region = nativeSubtitleRegion(container, picture, controls)
    LaunchedEffect(engine, region.useBlackBar) { engine.setSubtitleMargins(region.useBlackBar) }
    AndroidView(
        factory = { context -> SenPlayerSurfaceHost(context).also(engine::attach) },
        modifier = modifier.onSizeChanged { container = it },
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
        if (Build.VERSION.SDK_INT >= 34) {
            video.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            subtitles.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
        }
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
