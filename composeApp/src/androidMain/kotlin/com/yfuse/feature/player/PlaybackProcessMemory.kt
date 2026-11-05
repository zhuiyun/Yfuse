package com.yfuse.feature.player

import android.os.Debug
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Local diagnostics only. PSS includes mapped/native pages; native heap excludes driver allocations. */
@Composable
internal fun ObservePlaybackProcessMemory(state: androidx.compose.runtime.State<PlaybackState>) {
    val latest by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(10_000L)
            val current = latest.value
            if (current.ended || current.error != null) continue
            val memory = withContext(Dispatchers.IO) { samplePlaybackProcessMemory() }
            AppLog.info(
                category = "player.performance",
                event = "process_memory",
                message = "Playback process memory sample",
                attributes =
                    memory +
                        mapOf(
                            "engine" to current.diagnostics.engine,
                            "subtitleMode" to
                                when {
                                    current.secondarySubtitleTrackId != null &&
                                        current.secondarySubtitleTrackId != EngineTrack.OFF -> "dual"
                                    current.subtitleTracks.any { it.selected && it.id != EngineTrack.OFF } -> "single"
                                    else -> "off_or_unknown"
                                },
                            "bufferSource" to current.bufferSnapshot.source.name,
                        ),
            )
        }
    }
}

internal fun samplePlaybackProcessMemory(): Map<String, String> {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    val runtime = Runtime.getRuntime()
    val rssKb =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines
                    .firstOrNull {
                        it.startsWith(
                            "VmRSS:",
                        )
                    }?.substringAfter(':')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLongOrNull()
            }
        }.getOrNull()
    return buildMap {
        put("processPssBytes", (info.totalPss.toLong() * 1024L).toString())
        put("nativePssBytes", (info.nativePss.toLong() * 1024L).toString())
        put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize().toString())
        put("javaHeapUsedBytes", (runtime.totalMemory() - runtime.freeMemory()).toString())
        rssKb?.let { put("processRssBytes", (it * 1024L).toString()) }
        put("memoryScope", "process_not_decoder_exclusive")
    }
}
