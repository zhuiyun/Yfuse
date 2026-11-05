package com.yfuse.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val PREVIEW_SCHEME = "yfuse-preview://"
private val previewSessions = ConcurrentHashMap<String, LocalFramePreviewSession>()
private val previewDecoder = Dispatchers.IO.limitedParallelism(1)

/** Process-local token URLs contain no source address or credentials and are never written to disk. */
@Composable
internal fun rememberLocalFrameStoryboard(
    item: PlayerMediaItem?,
    durationMs: Long,
): TrickplayStoryboard? {
    val context = LocalContext.current.applicationContext
    val eligible =
        item != null &&
            Build.VERSION.SDK_INT >= 27 &&
            item.playMethod != PlaybackMethod.Transcode &&
            item.drmConfiguration == null &&
            item.rawDiscUri == null &&
            item.activeVersion?.discSource != true &&
            Uri.parse(item.url).scheme?.lowercase() in setOf("http", "https", "file", "content") &&
            !item.url
                .substringBefore('?')
                .substringBefore('#')
                .endsWith(".m3u8", true) &&
            !item.url
                .substringBefore('?')
                .substringBefore('#')
                .endsWith(".mpd", true)
    val session =
        remember(
            item?.id,
            item?.serverId,
            item?.versionId,
            item?.activeVersion?.id,
            item?.url,
            item?.customHeaders,
            item?.transportCredentials,
            eligible,
        ) {
            if (eligible) LocalFramePreviewSession(context, requireNotNull(item)) else null
        }
    DisposableEffect(session) {
        session?.let { previewSessions[it.id] = it }
        onDispose {
            session?.let {
                previewSessions.remove(it.id)
                it.close()
            }
        }
    }
    val duration = durationMs.takeIf { it > 0L } ?: item?.durationMsHint ?: 0L
    return remember(session, duration) {
        session?.takeIf { duration > 0L }?.let {
            TrickplayStoryboard(
                "$PREVIEW_SCHEME${it.id}/{index}",
                320,
                180,
                1,
                1,
                1_000L,
                (duration / 1_000L + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
    }
}

internal fun isLocalFramePreview(frame: TrickplayFrame): Boolean = frame.url.startsWith(PREVIEW_SCHEME)

@Composable
internal fun LocalFramePreviewImage(
    frame: TrickplayFrame,
    description: String,
    modifier: Modifier,
) {
    var bitmap by remember(frame.url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frame.url) {
        delay(90) // Coalesce drag updates before starting any decoder or network request.
        val uri = Uri.parse(frame.url)
        val session = previewSessions[uri.host] ?: return@LaunchedEffect
        val seconds =
            uri.lastPathSegment?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1_000_000L }
                ?: return@LaunchedEffect
        val result = session.frame(seconds * 1_000_000L)
        ensureActive()
        bitmap = result
    }
    bitmap?.let { Image(it.asImageBitmap(), description, modifier, contentScale = ContentScale.Fit) }
}

private class LocalFramePreviewSession(
    private val context: Context,
    private val item: PlayerMediaItem,
) {
    val id: String = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()
    private val lock = Mutex()
    private val frames = LinkedHashMap<Long, Bitmap>(8, 0.75f, true)

    @Volatile private var activeSource: PreviewHttpSource? = null

    suspend fun frame(timeUs: Long): Bitmap? =
        lock.withLock {
            if (closed.get()) return@withLock null
            frames[timeUs]?.let { return@withLock it }
            val job = currentCoroutineContext()[Job]
            // A child with no work completes cancellation immediately, even while a native decoder
            // is returning. Closing its source interrupts the active HTTP call as well as future reads.
            val cancellation = Job(job)
            val cancelled = AtomicBoolean()
            val requestSource = AtomicReference<PreviewHttpSource?>()
            val cancellationHandle =
                cancellation.invokeOnCompletion { cause ->
                    if (cause != null) {
                        cancelled.set(true)
                        // A late callback from this frame must never close a subsequent frame's source.
                        requestSource.get()?.close()
                    }
                }
            try {
                val result =
                    withContext(previewDecoder) {
                        runInterruptible {
                            if (closed.get() || job?.isActive == false) return@runInterruptible null
                            val retriever = MediaMetadataRetriever()
                            try {
                                if (item.url.startsWith("http://", true) || item.url.startsWith("https://", true)) {
                                    val headers =
                                        item.customHeaders
                                            ?.values
                                            .orEmpty()
                                            .toMutableMap()
                                    (item.transportCredentials as? YTransportCredentials.UsernamePassword)?.let {
                                        headers["Authorization"] =
                                            Credentials.basic(it.username, it.password, Charsets.UTF_8)
                                    }
                                    val source = PreviewHttpSource(item.url, headers, cancelled)
                                    requestSource.set(source)
                                    activeSource = source
                                    // close() may have seen no active source before this publication.
                                    if (closed.get() || cancelled.get() || job?.isActive == false) {
                                        source.close()
                                        return@runInterruptible null
                                    }
                                    retriever.setDataSource(source)
                                } else {
                                    retriever.setDataSource(context, Uri.parse(item.url))
                                }
                                if (closed.get() || job?.isActive == false) return@runInterruptible null
                                retriever
                                    .getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                                    ?.let { bitmap ->
                                        if (bitmap.allocationByteCount <= 512 * 1024) {
                                            bitmap
                                        } else {
                                            bitmap.recycle()
                                            null
                                        }
                                    }
                            } catch (_: Exception) {
                                null
                            } finally {
                                cancelled.set(true)
                                requestSource.getAndSet(null)?.close()
                                activeSource = null
                                runCatching { retriever.release() }
                            }
                        }
                    }
                currentCoroutineContext().ensureActive()
                if (!closed.get() && result != null) {
                    frames[timeUs] = result
                    while (frames.size > 8) frames.remove(frames.keys.first())
                }
                result
            } finally {
                cancellationHandle.dispose()
                cancellation.complete()
            }
        }

    fun close() {
        closed.set(true)
        activeSource?.close()
    }
}
