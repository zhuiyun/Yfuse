package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.sqrt

internal interface AssSubtitleBackend {
    fun open(
        source: YAssSubtitleSource,
        width: Int,
        height: Int,
        cacheMegabytes: Int,
        styleOverrides: List<String>,
    ): Long

    fun render(
        handle: Long,
        positionUs: Long,
        revision: Long,
        cues: List<YSubtitleCue>,
    ): List<YSubtitlePayload.BitmapArgb>?

    fun close(handle: Long)
}

private object NativeAssSubtitleBackend : AssSubtitleBackend {
    override fun open(
        source: YAssSubtitleSource,
        width: Int,
        height: Int,
        cacheMegabytes: Int,
        styleOverrides: List<String>,
    ): Long = FfmpegNativeBridge.createAssRenderer(source, width, height, cacheMegabytes, styleOverrides)

    override fun render(
        handle: Long,
        positionUs: Long,
        revision: Long,
        cues: List<YSubtitleCue>,
    ): List<YSubtitlePayload.BitmapArgb>? {
        val events = cues.filter { (it.payload as YSubtitlePayload.AssEvent).packet != null }
        val encoded =
            FfmpegNativeBridge.renderAss(
                handle,
                positionUs,
                revision,
                events.map { requireNotNull((it.payload as YSubtitlePayload.AssEvent).packet) }.toTypedArray(),
                events.map { it.startUs }.toLongArray(),
                events.map { it.endUs - it.startUs }.toLongArray(),
            ) ?: return null
        if (encoded.isEmpty()) return emptyList()
        return encoded
            .toBitmapSubtitleCues(
                YCompressedSample(YTrackId(0), byteArrayOf(), positionUs.coerceAtLeast(0), durationUs = 1_000L),
            ).map { it.payload as YSubtitlePayload.BitmapArgb }
    }

    override fun close(handle: Long) = FfmpegNativeBridge.closeAssRenderer(handle)
}

/**
 * One isolated subtitle owner per display channel. Pending clock requests are conflated, so an
 * expensive font lookup cannot build a backlog or make the video/network owners wait for libass.
 */
internal class AndroidAssSubtitleRenderer(
    private val backend: AssSubtitleBackend = NativeAssSubtitleBackend,
    private val acquireMemory: (Long) -> PlaybackMemoryLease = {
        AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Subtitle, it)
    },
) : Closeable {
    private data class Request(
        val cues: List<YSubtitleCue>,
        val visibleCues: List<YSubtitleCue>,
        val timelineGeneration: Long,
        val positionUs: Long,
        val width: Int,
        val height: Int,
        val styleOverrides: List<String>,
        val generation: Long,
    )

    private class Track(
        val source: YAssSubtitleSource,
        val handle: Long,
        val width: Int,
        val height: Int,
        val memory: PlaybackMemoryLease,
        val styleOverrides: List<String>,
    ) {
        var cues: List<YSubtitleCue> = emptyList()
        var revision = 0L
        var bitmap: List<YSubtitlePayload.BitmapArgb> = emptyList()
    }

    private data class SourceClock(
        val source: YAssSubtitleSource,
        val offsetUs: Long,
    )

    private val gate = Any()
    private val executor =
        Executors.newSingleThreadExecutor {
            Thread(
                it,
                "YCore-ASS-Render",
            ).apply { isDaemon = true }
        }
    private var pending: Request? = null
    private var lastSubmitted: Request? = null
    private var generation = 0L
    private var lastFailureKind: String? = null
    private var draining = false
    private var closed = false
    private val tracks = mutableMapOf<SourceClock, Track>()
    private val output = MutableStateFlow<List<YSubtitlePayload.BitmapArgb>>(emptyList())
    val bitmaps: StateFlow<List<YSubtitlePayload.BitmapArgb>> = output

    fun submit(
        cues: List<YSubtitleCue>,
        positionUs: Long,
        width: Int = 0,
        height: Int = 0,
        styleOverrides: List<String> = emptyList(),
        timelineGeneration: Long = 0L,
    ) {
        synchronized(gate) {
            if (closed) return
            val assCues = cues.filter { it.payload is YSubtitlePayload.AssEvent }
            val visibleCues = assCues.filter { positionUs >= it.startUs && positionUs < it.endUs }
            val previous = lastSubmitted
            val invalidated =
                assCues.isEmpty() ||
                    previous != null &&
                    (
                        timelineGeneration != previous.timelineGeneration ||
                            visibleCues != previous.visibleCues ||
                            positionUs < previous.positionUs ||
                            positionUs - previous.positionUs > 500_000L ||
                            width != previous.width ||
                            height != previous.height ||
                            styleOverrides != previous.styleOverrides ||
                            assCues.firstOrNull()?.let { (it.payload as YSubtitlePayload.AssEvent).source } !==
                            previous.cues.firstOrNull()?.let { (it.payload as YSubtitlePayload.AssEvent).source }
                    )
            if (invalidated) {
                generation++
                output.value = emptyList()
            }
            val request =
                Request(assCues, visibleCues, timelineGeneration, positionUs, width, height, styleOverrides, generation)
            lastSubmitted = request
            pending = request
            schedule()
        }
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            pending = null
            output.value = emptyList()
            schedule()
        }
    }

    private fun schedule() {
        if (draining) return
        draining = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request =
                synchronized(gate) {
                    if (closed) {
                        null
                    } else {
                        pending?.also { pending = null } ?: run {
                            draining = false
                            return
                        }
                    }
                }
            if (request == null) {
                tracks.values.forEach { runCatching { releaseTrack(it) } }
                tracks.clear()
                executor.shutdown()
                return
            }
            val rendered =
                try {
                    render(request).also { lastFailureKind = null }
                } catch (exception: Exception) {
                    tracks.values.forEach { runCatching { releaseTrack(it) } }
                    tracks.clear()
                    // Script/font failures remain isolated from playback; never log source text or URI.
                    val kind = exception.javaClass.simpleName
                    if (lastFailureKind != kind) {
                        lastFailureKind = kind
                        runCatching {
                            AppLog.warning("player.core2", "subtitle_render_failed", "ASS rendering failed ($kind)")
                        }
                    }
                    emptyList()
                }
            synchronized(gate) {
                // A newer clock tick can wait while a valid frame is displayed. Requiring an
                // empty queue here starves every frame when rendering is slower than vsync.
                if (!closed && request.generation == generation) output.value = rendered
            }
        }
    }

    private fun render(request: Request): List<YSubtitlePayload.BitmapArgb> {
        val groups =
            request.cues.groupBy {
                SourceClock((it.payload as YSubtitlePayload.AssEvent).source, it.sourceTimeOffsetUs)
            }
        tracks.keys.filter { it !in groups }.forEach { source -> tracks.remove(source)?.let(::releaseTrack) }
        return groups.flatMap { (clock, cues) ->
            val source = clock.source
            var track = tracks[clock]
            if (track != null) {
                val desired = dimensions(source, request, track.memory.limitBytes)
                if (desired.first != track.width ||
                    desired.second != track.height ||
                    request.styleOverrides != track.styleOverrides
                ) {
                    tracks.remove(clock)
                    releaseTrack(track)
                    track = null
                }
            }
            val active = track ?: openTrack(source, request).also { tracks[clock] = it }
            val visible = cues.filter { request.positionUs >= it.startUs && request.positionUs < it.endUs }
            if (visible != active.cues) {
                active.cues = visible
                active.revision++
            }
            // Keep a full script's original events even in gaps; libass selects the clock interval.
            val sourceCues =
                if (clock.offsetUs == 0L) {
                    visible
                } else {
                    visible.map {
                        it.copy(
                            startUs = (it.startUs - clock.offsetUs).coerceAtLeast(0L),
                            endUs = (it.endUs - clock.offsetUs).coerceAtLeast(1L),
                            sourceTimeOffsetUs = 0L,
                        )
                    }
                }
            backend
                .render(
                    active.handle,
                    (request.positionUs - clock.offsetUs).coerceAtLeast(0L),
                    active.revision,
                    sourceCues,
                )?.let { active.bitmap = it }
            if (visible.isEmpty()) emptyList() else active.bitmap
        }
    }

    private fun openTrack(
        source: YAssSubtitleSource,
        request: Request,
    ): Track {
        val width = request.width.takeIf { it > 0 } ?: source.canvasWidth.takeIf { it > 0 } ?: 1280
        val height = request.height.takeIf { it > 0 } ?: source.canvasHeight.takeIf { it > 0 } ?: 720
        val memory =
            acquireMemory(
                sourceBytes(source) * 2L + width.toLong() * height * BYTES_PER_CANVAS_PIXEL + CACHE_BYTES,
            )
        try {
            val size = dimensions(source, request, memory.limitBytes)
            val handle = backend.open(source, size.first, size.second, 1, request.styleOverrides)
            return Track(source, handle, size.first, size.second, memory, request.styleOverrides)
        } catch (exception: Throwable) {
            memory.close()
            throw exception
        }
    }

    private fun dimensions(
        source: YAssSubtitleSource,
        request: Request,
        limitBytes: Long,
    ): Pair<Int, Int> {
        val width = request.width.takeIf { it > 0 } ?: source.canvasWidth.takeIf { it > 0 } ?: 1280
        val height = request.height.takeIf { it > 0 } ?: source.canvasHeight.takeIf { it > 0 } ?: 720
        val available = limitBytes - sourceBytes(source) * 2L - CACHE_BYTES
        check(available >= 64L * 64L * BYTES_PER_CANVAS_PIXEL) { "ASS fonts exceed the subtitle memory budget" }
        val ratio = sqrt(available.toDouble() / (width.toDouble() * height * BYTES_PER_CANVAS_PIXEL)).coerceAtMost(1.0)
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    private fun sourceBytes(source: YAssSubtitleSource): Long =
        source.data.size.toLong() + source.fonts.sumOf { it.data.size.toLong() }

    private fun releaseTrack(track: Track) {
        try {
            backend.close(track.handle)
        } finally {
            track.memory.close()
        }
    }
}

// Native composition, JNI bytes, managed pixels and Compose Bitmap can briefly overlap.
private const val BYTES_PER_CANVAS_PIXEL = 24L
private const val CACHE_BYTES = 1024L * 1024L
