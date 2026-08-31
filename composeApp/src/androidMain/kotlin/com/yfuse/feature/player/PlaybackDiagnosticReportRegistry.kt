package com.yfuse.feature.player

import android.content.Context
import com.yfuse.BuildConfig
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.logging.redactDiagnosticText
import com.yfuse.core.model.PlayerEngine
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
                        content = redactDiagnosticText(stored).take(MAX_REPORT_CHARS),
                        observedAtEpochMs = preferences.getLong(KEY_OBSERVED_AT, 0L),
                        currentProcess = false,
                    ),
                )
            }
        DiagnosticLogStore.registerExportArtifact("playback-report.txt") {
            val snapshot = latest.get()
            buildString {
                appendLine("Yfuse playback diagnostic")
                appendLine("app.version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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
                append(PlaybackRemotePolicyRegistry.diagnosticSummary())
                append(AndroidNativeCrashMonitor.diagnosticSummary())
                append(nativeLibraryReport())
            }
        }
    }

    fun update(
        state: PlaybackState,
        selectedEngine: PlayerEngine,
        fallbackChain: List<PlayerEngine>,
        nativeOnly: Boolean = false,
    ) {
        val diagnostics = state.diagnostics
        val evidence = diagnostics.outputEvidence
        val mpv = diagnostics.mpvDolbyRuntimeEvidence()
        val report =
            redactDiagnosticText(
                buildString {
                    appendLine(
                        "engine.selected=" +
                            if (nativeOnly) "YCore2Native" else selectedEngine.name,
                    )
                    appendLine("engine.actual=${diagnostics.engine.ifBlank { "unknown" }}")
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
                    appendLine("video.decoder=${evidence.videoDecoder.ifBlank { diagnostics.decoder }}")
                    appendLine("video.codec=${diagnostics.videoCodec}")
                    appendLine("audio.decoder=${evidence.audioDecoder.ifBlank { diagnostics.audioFormat }}")
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
                    appendLine("mpv.rpu.rendered=${evidence.dolbyVisionRpuRendered || mpv.rpuRendered}")
                    appendLine("mpv.fel.composed=${evidence.dolbyVisionFelComposed || mpv.felComposed}")
                    appendLine("mpv.evidence.generation=${mpv.generation}")
                },
            ).take(MAX_REPORT_CHARS)
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
    private const val KEY_OBSERVED_AT = "observed_at_epoch_ms"
    private const val MAX_REPORT_CHARS = 64 * 1024
}
