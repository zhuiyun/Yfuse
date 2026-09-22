package com.yfuse.feature.player

import android.content.Context
import com.yfuse.BuildConfig
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.redactDiagnosticText
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core2.android.FfmpegNativeBridge
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Latest credential-free playback facts included by the existing one-tap diagnostics export. */
internal object PlaybackDiagnosticReportRegistry {
    private data class Snapshot(
        val content: String? = null,
        val observedAtEpochMs: Long = 0L,
        val currentProcess: Boolean = false,
    )

    private val initialized = AtomicBoolean(false)
    private val latest = AtomicReference(Snapshot())
    private val timelineLock = Any()
    private val timeline = ArrayDeque<String>()
    private var lastTimelineFingerprint: String? = null
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences
            .getString(KEY_LATEST_REPORT, null)
            ?.takeIf(String::isNotBlank)
            ?.let { stored ->
                latest.set(
                    Snapshot(
                        content = restorePlaybackReportOrigin(redactDiagnosticText(stored)).take(MAX_REPORT_CHARS),
                        observedAtEpochMs = preferences.getLong(KEY_OBSERVED_AT, 0L),
                        currentProcess = false,
                    ),
                )
            }
        preferences
            .getString(KEY_LATEST_TIMELINE, null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.toList()
            ?.takeLast(MAX_TIMELINE_ENTRIES)
            ?.forEach { entry ->
                synchronized(timelineLock) {
                    timeline.addLast(
                        restorePlaybackTimelineOrigin(redactDiagnosticText(entry)).take(MAX_TIMELINE_ENTRY_CHARS),
                    )
                }
            }
        DiagnosticLogStore.registerExportArtifact("playback-report.txt") {
            val snapshot = latest.get()
            buildString {
                appendLine("Yfuse playback diagnostic")
                appendLine("app.version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("app.build.revision=${BuildConfig.BUILD_REVISION}")
                appendLine("app.version.scope=exporting-package")
                appendLine(
                    "session.origin=" +
                        when {
                            snapshot.content == null -> "none"
                            snapshot.currentProcess -> "current-process"
                            else -> "previous-process"
                        },
                )
                appendLine("session.observedAtEpochMs=${snapshot.observedAtEpochMs}")
                append(
                    snapshot.content
                        ?: "No playback session has been observed in this or the previous process.\n",
                )
                appendLine()
                appendLine("session.timeline:")
                synchronized(timelineLock) {
                    if (timeline.isEmpty()) {
                        appendLine("none")
                    } else {
                        timeline.forEach(::appendLine)
                    }
                }
                appendLine()
                append(PlaybackRemotePolicyRegistry.diagnosticSummary())
                append(AndroidNativeCrashMonitor.diagnosticSummary())
                append(nativeLibraryReport())
            }
        }
    }

    private data class ReportInput(
        val diagnostics: PlaybackDiagnostics,
        val subtitleSelection: String,
        val selectedEngine: PlayerEngine,
        val fallbackChain: List<PlayerEngine>,
        val nativeOnly: Boolean,
    )

    private var lastReportInput: ReportInput? = null
    private val sequence = AtomicLong()
    private var lastAppliedSequence = 0L

    fun nextSequence(): Long = sequence.incrementAndGet()

    @Synchronized
    fun update(
        state: PlaybackState,
        selectedEngine: PlayerEngine,
        fallbackChain: List<PlayerEngine>,
        nativeOnly: Boolean = false,
        updateSequence: Long = nextSequence(),
    ) {
        if (updateSequence <= lastAppliedSequence) return
        lastAppliedSequence = updateSequence
        val diagnostics = state.diagnostics
        recordTimeline(state, selectedEngine, nativeOnly)
        val subtitleSelection = playbackSubtitleDiagnosticSelection(state)
        val input = ReportInput(diagnostics, subtitleSelection, selectedEngine, fallbackChain.toList(), nativeOnly)
        if (input == lastReportInput) return
        val evidence = diagnostics.outputEvidence
        val mpv = diagnostics.mpvDolbyRuntimeEvidence()
        val report =
            redactDiagnosticText(
                buildString {
                    appendLine("session.metadata=2")
                    appendLine("session.appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("session.appBuild=${BuildConfig.BUILD_REVISION}")
                    appendLine("session.process=${DiagnosticLogStore.processSessionId}")
                    appendLine("subtitle.selection=$subtitleSelection")
                    appendLine(
                        "engine.selected=" +
                            if (nativeOnly) YCORE2_NATIVE_ENGINE_LABEL else selectedEngine.name,
                    )
                    appendLine("engine.actual=${diagnostics.engine.ifBlank { "unknown" }}")
                    // The selection is what the user asked for; the binding is what runs. YCore 2.0
                    // also backs a session selected as another engine, so a report that carries only
                    // the selection reads as a contradiction against every engine log line.
                    appendLine("engine.binding=${engineBindingLabel(diagnostics, selectedEngine, nativeOnly)}")
                    appendLine("route=${diagnostics.plannedRenderPath.ifBlank { "unknown" }}")
                    appendLine(
                        "fallback.chain=" +
                            if (nativeOnly) {
                                "YCore2Native"
                            } else {
                                fallbackChain.joinToString(" -> ") { it.name }
                            },
                    )
                    appendLine("fallback.reason=${diagnostics.fallbackReason.orEmpty()}")
                    appendLine(
                        "video.decoder=" +
                            evidence.videoDecoder.ifBlank { "unknown" },
                    )
                    appendLine("video.codec=${diagnostics.videoCodec}")
                    appendLine(
                        "audio.decoder=" +
                            evidence.audioDecoder.ifBlank { "unknown" },
                    )
                    appendLine("dynamicRange.input=${evidence.inputDynamicRange.ifBlank { diagnostics.dynamicRange }}")
                    appendLine("dynamicRange.output=${evidence.outputDynamicRange.ifBlank { "unknown" }}")
                    appendLine("dynamicRange.mode=${evidence.dynamicRangeOutputMode.name}")
                    appendLine("video.readiness=${diagnostics.effectiveVideoReadiness.name}")
                    appendLine("audio.readiness=${diagnostics.effectiveAudioReadiness.name}")
                    appendLine("audio.mode=${evidence.audioMode.name}")
                    appendLine("audio.passthrough=${evidence.audioMode == PlaybackAudioOutputMode.Passthrough}")
                    appendLine("audio.immersiveCarrier=${diagnostics.immersiveAudioCarrierOutput}")
                    appendLine("audio.spatializedPcm=${diagnostics.spatialAudioOutput}")
                    appendLine("audio.headTracker=${diagnostics.headTrackingAvailable}")
                    appendLine("audio.pcm=${evidence.audioMode == PlaybackAudioOutputMode.Pcm}")
                    appendLine("dropped.frames=${state.diagnostics.droppedFrames}")
                    appendLine("dropped.measured=${evidence.droppedFramesMeasured}")
                    appendLine("buffer.events=${diagnostics.bufferEvents}")
                    appendLine("rebuffer.total.ms=${diagnostics.rebufferDurationMs}")
                    appendLine("rebuffer.longest.ms=${diagnostics.longestRebufferMs}")
                    appendLine("buffered.ms=${diagnostics.bufferedDurationMs}")
                    appendLine("audio.underruns=${evidence.audioUnderrunCount}")
                    appendLine("source.queue.bytes=${diagnostics.sourceQueueBytes}")
                    appendLine("source.buffered.ms=${diagnostics.sourceBufferedMs}")
                    appendLine("source.starvations=${diagnostics.sourceStarvationCount}")
                    appendLine("codec.resets=${evidence.codecResetCount}")
                    appendLine("av.offset.ms=${diagnostics.avSyncOffsetMs?.toString() ?: "unavailable"}")
                    appendLine("av.measurement=${diagnostics.avSyncMeasurement}")
                    appendLine("surface.rebuilds=${evidence.surfaceRebuildCount}")
                    appendLine("render.api=${evidence.renderApi.name}")
                    appendLine("render.detail=${evidence.rendererDetail}")
                    appendLine("output.evidence.generation=${diagnostics.outputEvidenceGeneration}")
                    appendLine("output.evidence.reset=${diagnostics.outputEvidenceResetReason}")
                    appendLine("mpv.rpu.rendered=${evidence.dolbyVisionRpuRendered || mpv.rpuRendered}")
                    appendLine("mpv.fel.composed=${evidence.dolbyVisionFelComposed || mpv.felComposed}")
                    appendLine("mpv.evidence.generation=${mpv.generation}")
                },
            ).take(MAX_REPORT_CHARS)
        lastReportInput = input
        val previous = latest.get()
        if (previous.currentProcess && previous.content == report) return
        val now = System.currentTimeMillis()
        latest.set(Snapshot(report, now, currentProcess = true))
        if (::appContext.isInitialized) {
            appContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LATEST_REPORT, report)
                .putLong(KEY_OBSERVED_AT, now)
                .apply()
        }
    }

    private fun recordTimeline(
        state: PlaybackState,
        selectedEngine: PlayerEngine,
        nativeOnly: Boolean,
    ) {
        val diagnostics = state.diagnostics
        val evidence = diagnostics.outputEvidence
        val fingerprint =
            listOf(
                state.currentIndex,
                state.playing,
                state.buffering,
                state.ended,
                state.error
                    ?.javaClass
                    ?.simpleName
                    .orEmpty(),
                diagnostics.engine,
                diagnostics.fallbackReason.orEmpty(),
                diagnostics.effectiveVideoReadiness,
                diagnostics.effectiveAudioReadiness,
                diagnostics.bufferEvents,
                diagnostics.networkRecoveryAttempts,
                diagnostics.networkRecoverySuccesses,
                evidence.surfaceRebuildCount,
                playbackSubtitleDiagnosticSelection(state),
            ).joinToString("|")
        synchronized(timelineLock) {
            if (fingerprint == lastTimelineFingerprint) return
            lastTimelineFingerprint = fingerprint
            val entry =
                redactDiagnosticText(
                    buildString {
                        append("timelineVersion=2 appVersion=${BuildConfig.VERSION_NAME}_${BuildConfig.VERSION_CODE}")
                        append(
                            " appBuild=${BuildConfig.BUILD_REVISION} process=${DiagnosticLogStore.processSessionId} ",
                        )
                        append("at=")
                        append(System.currentTimeMillis())
                        append(" positionMs=")
                        append(state.positionMs.coerceAtLeast(0L))
                        append(" item=")
                        append(state.currentIndex)
                        append(" state=")
                        append(
                            when {
                                state.error != null -> "error"
                                state.ended -> "ended"
                                state.buffering -> "buffering"
                                state.playing -> "playing"
                                else -> "paused"
                            },
                        )
                        append(" engine=")
                        append(engineBindingLabel(diagnostics, selectedEngine, nativeOnly))
                        append(" video=")
                        append(diagnostics.effectiveVideoReadiness.name)
                        append(" audio=")
                        append(diagnostics.effectiveAudioReadiness.name)
                        append(" bufferEvents=")
                        append(diagnostics.bufferEvents)
                        append(" recovery=")
                        append(diagnostics.networkRecoverySuccesses)
                        append('/')
                        append(diagnostics.networkRecoveryAttempts)
                        append(" surfaceRebuilds=")
                        append(evidence.surfaceRebuildCount)
                        append(" subtitles=")
                        append(playbackSubtitleDiagnosticSelection(state))
                        diagnostics.fallbackReason?.takeIf(String::isNotBlank)?.let { reason ->
                            append(" fallback=")
                            append(reason)
                        }
                    },
                ).take(MAX_TIMELINE_ENTRY_CHARS)
            timeline.addLast(entry)
            while (timeline.size > MAX_TIMELINE_ENTRIES) timeline.removeFirst()
            if (::appContext.isInitialized) {
                appContext
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LATEST_TIMELINE, timeline.joinToString("\n"))
                    .apply()
            }
        }
    }

    private fun nativeLibraryReport(): String {
        val capabilities = installedMpvNativeBuildCapabilities
        return buildString {
            appendLine("native.mpv.core.revision=${capabilities.mpvCoreRevision ?: "unknown"}")
            appendLine("native.mpv.android.revision=${capabilities.libmpvAndroidRevision ?: "unknown"}")
            appendLine("native.ffmpeg.revision=${capabilities.ffmpegRevision ?: "unknown"}")
            appendLine("native.libplacebo.revision=${capabilities.libplaceboRevision ?: "unknown"}")
            appendLine("native.libbluray.revision=${capabilities.libblurayRevision ?: "unknown"}")
            appendLine("native.libudfread.revision=${capabilities.libudfreadRevision ?: "unknown"}")
            appendLine("native.mdk.compile.version=$MDK_SDK_COMPILE_VERSION")
            appendLine("native.ycore.gpu.packaged=${BuildConfig.YFUSE_YCORE_GPU_INCLUDED}")
            appendLine("native.package.profile=${BuildConfig.YFUSE_PACKAGE_PROFILE}")
            // The demux bridge has no revision string of its own; whether it can explain a
            // failed open is the one observable that separates the current artifact from the
            // older one whose statuses this build cannot decode.
            appendLine("native.ycore.demux.loaded=${FfmpegNativeBridge.available}")
            appendLine(
                "native.ycore.demux.openFailureDetail=" +
                    if (FfmpegNativeBridge.openFailureDetailAvailable) "available" else "legacy",
            )
            appendLine(
                "native.ycore.demux.handleContract=" +
                    if (FfmpegNativeBridge.registryHandles) "registry" else "pointer",
            )
            nativeLibraryFingerprints().forEach { (name, hash) ->
                appendLine("native.$name.sha256=$hash")
            }
        }
    }

    private fun nativeLibraryFingerprints(): Map<String, String> {
        val wanted =
            setOf(
                "libmpv.so",
                "libmdk.so",
                "libyfuse-mdk-jni.so",
                "libycore_demux.so",
                "libycore_gpu.so",
            )
        val result = sortedMapOf<String, String>()
        val nativeDirectory = File(appContext.applicationInfo.nativeLibraryDir.orEmpty())
        wanted.forEach { name ->
            nativeDirectory.resolve(name).takeIf(File::isFile)?.let { file ->
                result[name] = file.inputStream().use(::sha256)
            }
        }
        if (result.keys.containsAll(wanted)) return result
        val apkPaths =
            listOfNotNull(appContext.applicationInfo.sourceDir) +
                appContext.applicationInfo.splitSourceDirs.orEmpty()
        apkPaths.forEach { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { entry ->
                            !entry.isDirectory && entry.name.substringAfterLast('/') in wanted
                        }.forEach { entry ->
                            val name = entry.name.substringAfterLast('/')
                            result.putIfAbsent(name, zip.getInputStream(entry).use(::sha256))
                        }
                }
            }
        }
        wanted.minus(result.keys).forEach { result[it] = "not-packaged" }
        return result
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val PREFERENCES_NAME = "playback_diagnostic_report"
    private const val KEY_LATEST_REPORT = "latest_report"
    private const val KEY_LATEST_TIMELINE = "latest_timeline"
    private const val KEY_OBSERVED_AT = "observed_at_epoch_ms"
    private const val MAX_REPORT_CHARS = 64 * 1024
    private const val MAX_TIMELINE_ENTRIES = 80
    private const val MAX_TIMELINE_ENTRY_CHARS = 768
}

/** Old persisted lines do not carry enough evidence to assign them to the exporting build. */
internal fun restorePlaybackTimelineOrigin(entry: String): String =
    if (
        entry.startsWith("timelineVersion=") &&
        listOf(" appVersion=", " appBuild=", " process=").all(entry::contains)
    ) {
        entry
    } else {
        "timelineVersion=legacy-unknown appVersion=unknown appBuild=unknown process=unknown $entry"
    }

internal fun restorePlaybackReportOrigin(report: String): String =
    if (
        report.startsWith("session.metadata=") &&
        listOf("\nsession.appVersion=", "\nsession.appBuild=", "\nsession.process=").all(report::contains)
    ) {
        report
    } else {
        "session.metadata=legacy-unknown\nsession.appVersion=unknown\nsession.appBuild=unknown\nsession.process=unknown\n$report"
    }

/** Track ordinals and format/language only: external-track IDs and labels can contain URLs. */
internal fun playbackSubtitleDiagnosticSelection(state: PlaybackState): String {
    fun format(index: Int): String {
        val track = state.subtitleTracks.getOrNull(index) ?: return "off-or-unavailable"

        fun token(value: String?) =
            value?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./+-]{0,63}")) } ?: "unknown"
        return "$index:${token(track.language)}:${token(track.codec)}"
    }
    val primary = state.subtitleTracks.indexOfFirst { it.selected }
    val secondary = state.subtitleTracks.indexOfFirst { it.id == state.secondarySubtitleTrackId }
    return "primary[${format(primary)}],secondary[${format(secondary)}]"
}

/**
 * The engine that actually owns the session, for every line a reader compares against the logs.
 *
 * `nativeOnly` is a selection-time decision that requires the engine choice to be Auto, so it says
 * nothing about a YCore 2.0 binding reached from an explicitly selected engine. Deriving the label
 * from it alone made the report claim one engine while every `engine_attached` line named another.
 */
internal fun engineBindingLabel(
    diagnostics: PlaybackDiagnostics,
    selectedEngine: PlayerEngine,
    nativeOnly: Boolean,
): String =
    when {
        nativeOnly || diagnostics.isNativeCore2Binding -> YCORE2_NATIVE_ENGINE_LABEL
        else -> diagnostics.engine.ifBlank { selectedEngine.name }
    }
