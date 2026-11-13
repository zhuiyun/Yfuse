package com.yfuse.feature.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yfuse.core2.android.BufferedScrubMedia
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PREVIEW_SCHEME = "yfuse-preview://"
private val previewSessions = ConcurrentHashMap<String, LocalFramePreviewSession>()
private val previewDecoder = Dispatchers.IO.limitedParallelism(1)

@Composable
internal fun rememberLocalFrameStoryboard(
    item: PlayerMediaItem?, playback: State<PlaybackState>, engineKey: Any?, durationMs: Long,
): TrickplayStoryboard? {
    val session = remember(item?.id, item?.serverId, item?.versionId, item?.activeVersion?.id,
        item?.url, item?.transcodeUrl, item?.playMethod, item?.drmConfiguration, item?.activeVersion?.drmConfiguration,
        item?.customHeaders, item?.transportCredentials, playback, engineKey) {
        if (item == null || Build.VERSION.SDK_INT < 27 || item.drmConfiguration != null ||
            item.activeVersion?.drmConfiguration != null || item.rawDiscUri != null || item.activeVersion?.discSource == true
        ) null else LocalFramePreviewSession(item, playback)
    }
    DisposableEffect(session) {
        session?.let { it.attach(); previewSessions[it.id] = it }
        onDispose { session?.let { previewSessions.remove(it.id); it.close() } }
    }
    val duration = durationMs.takeIf { it > 0 } ?: item?.durationMsHint ?: 0
    return remember(session, duration) {
        session?.takeIf { duration > 0 }?.let {
            TrickplayStoryboard("$PREVIEW_SCHEME${it.id}/{index}",320,180,1,1,1_000,
                (duration / 1_000 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
}

internal fun isLocalFramePreview(frame: TrickplayFrame) = frame.url.startsWith(PREVIEW_SCHEME)

internal fun YBufferSnapshot.hasScrubPosition(position: Long): Boolean =
    coverage != YBufferRangeCoverage.Unknown && position >= 0 &&
        ranges.any { position >= it.startMs && position < it.endMs }

@Composable
internal fun rememberScrubBitmap(frame: TrickplayFrame, positionMs: Long): ImageBitmap? {
    val uri = remember(frame.url) { Uri.parse(frame.url) }
    val session = previewSessions[uri.host] ?: return null
    val evidence = session.playback.value.bufferSnapshot
    if (!evidence.hasScrubPosition(positionMs)) return null
    val range = evidence.ranges.first { positionMs >= it.startMs && positionMs < it.endMs }
    val frameMs = maxOf(range.startMs, positionMs / 1_000L * 1_000L)
    var result by remember(session, frameMs, evidence.sessionId, evidence.generation) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, frameMs, evidence.sessionId, evidence.generation) {
        delay(60)
        val bitmap = session.decode(frameMs)
        ensureActive()
        val now = session.playback.value.bufferSnapshot
        if (bitmap != null && now.generation == evidence.generation && now.sessionId == evidence.sessionId &&
            now.hasScrubPosition(frameMs)) result = bitmap.asImageBitmap()
    }
    return result
}

private class LocalFramePreviewSession(
    private val item: PlayerMediaItem, val playback: State<PlaybackState>,
) {
    val id = UUID.randomUUID().toString()
    @Volatile private var buffer: BufferedScrubMedia.Session? = null
    private val source = if (item.startsWithServerTranscode()) item.transcodeUrl else item.url
    fun attach() {
        buffer = BufferedScrubMedia.attach(source, item.customHeaders?.values.orEmpty(), item.transportCredentials)
    }
    fun close() { buffer?.close(); buffer = null }

    suspend fun decode(timeMs: Long): Bitmap? = withContext(previewDecoder) {
        val session = buffer ?: return@withContext null
        runInterruptible {
            val (data, timeUs) = session.input(timeMs * 1_000L) ?: return@runInterruptible null
            data.use {
                val decoder = MediaMetadataRetriever()
                try {
                    decoder.setDataSource(data)
                    val bitmap = decoder.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 320, 180)
                    if (data.valid()) bitmap else { bitmap?.recycle(); null }
                } catch (_: Exception) { null } finally { runCatching { decoder.release() } }
            }
        }
    }
}
