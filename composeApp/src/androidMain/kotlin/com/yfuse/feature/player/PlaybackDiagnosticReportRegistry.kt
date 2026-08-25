package com.yfuse.feature.player

import android.content.Context
import com.yfuse.BuildConfig
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.model.PlayerEngine
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Latest credential-free playback facts included by the existing one-tap diagnostics export. */
internal object PlaybackDiagnosticReportRegistry {
    private val latest = AtomicReference("No playback session has been observed in this process.\n")
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        DiagnosticLogStore.registerExportArtifact("playback-report.txt") {
            buildString {
                appendLine("Yfuse playback diagnostic")
                appendLine("app.version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                append(latest.get())
                appendLine()
                append(nativeLibraryReport())
            }
        }
    }

    fun update(
        state: PlaybackState,
        selectedEngine: PlayerEngine,
        fallbackChain: List<PlayerEngine>,
    ) {
        val diagnostics = state.diagnostics
        val evidence = diagnostics.outputEvidence
        val mpv = diagnostics.mpvDolbyRuntimeEvidence()
        latest.set(
            buildString {
                appendLine("engine.selected=${selectedEngine.name}")
                appendLine("engine.actual=${diagnostics.engine.ifBlank { "unknown" }}")
                appendLine("route=${diagnostics.plannedRenderPath.ifBlank { "unknown" }}")
                appendLine("fallback.chain=${fallbackChain.joinToString(" -> ") { it.name }}")
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
                appendLine("audio.pcm=${evidence.audioMode == PlaybackAudioOutputMode.Pcm}")
                appendLine("dropped.frames=${state.diagnostics.droppedFrames}")
                appendLine("dropped.measured=${evidence.droppedFramesMeasured}")
                appendLine("buffer.events=${diagnostics.bufferEvents}")
                appendLine("buffered.ms=${diagnostics.bufferedDurationMs}")
                appendLine("audio.underruns=${evidence.audioUnderrunCount}")
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
        )
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
            nativeLibraryFingerprints().forEach { (name, hash) ->
                appendLine("native.$name.sha256=$hash")
            }
        }
    }

    private fun nativeLibraryFingerprints(): Map<String, String> {
        val wanted = setOf("libmpv.so", "libmdk.so", "libyfuse-mdk-jni.so", "libycore_demux.so")
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
}
