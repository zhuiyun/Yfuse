package com.yfuse.core.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.russhwolf.settings.Settings
import com.yfuse.MainActivity
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.redactDiagnosticText
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.validateEmbyServerEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal lateinit var offlineApplicationContext: Context

internal const val OFFLINE_STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
internal const val OFFLINE_WAKE_WORK_NAME = "yfuse.offline.download.wake.v1"
internal const val MAX_OFFLINE_SUBTITLE_BYTES = 16L * 1024L * 1024L

private const val OFFLINE_NOTIFICATION_CHANNEL_ID = "yfuse_downloads"
private const val OFFLINE_SERVICE_NOTIFICATION_ID = 2410
private const val OFFLINE_WORK_NOTIFICATION_ID = 2411

private class OfflineHttpException(
    val statusCode: Int,
) : IOException("HTTP $statusCode")

private class OfflineStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private class OfflineSubtitleTooLargeException(
    maxBytes: Long,
) : IOException("字幕文件超过 $maxBytes 字节上限")

private inline fun <T> offlineStorageWrite(block: () -> T): T =
    try {
        block()
    } catch (error: IOException) {
        throw OfflineStorageException("无法写入离线文件，请检查存储空间", error)
    }

private fun offlineFailureKind(error: Throwable): DownloadFailureKind =
    when (error) {
        is OfflineHttpException ->
            when (error.statusCode) {
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> DownloadFailureKind.Authentication
                in 500..599, HttpURLConnection.HTTP_CLIENT_TIMEOUT, 429 -> DownloadFailureKind.Server
                else -> DownloadFailureKind.Source
            }
        is OfflineStorageException -> DownloadFailureKind.Storage
        is IOException -> DownloadFailureKind.Network
        is IllegalStateException -> DownloadFailureKind.Source
        else -> DownloadFailureKind.Unknown
    }

private fun offlineFailureMessage(
    kind: DownloadFailureKind,
    retry: OfflineRetryPlan?,
    error: Throwable,
): String =
    when (kind) {
        DownloadFailureKind.Authentication -> "登录已失效，请重新登录服务器后重试"
        DownloadFailureKind.Network ->
            if (retry != null) {
                "网络中断，已保留进度，将自动重试（第 ${retry.retryCount}/$MAX_OFFLINE_RETRY_COUNT 次）"
            } else {
                "网络持续不可用，已停止自动重试，可点按手动重试"
            }
        DownloadFailureKind.Server ->
            if (retry != null) {
                "服务器暂时不可用，已保留进度，将自动重试（第 ${retry.retryCount}/$MAX_OFFLINE_RETRY_COUNT 次）"
            } else {
                "服务器持续不可用，已停止自动重试，可点按手动重试"
            }
        DownloadFailureKind.Storage ->
            redactDiagnosticText(
                error.message ?: "存储空间不足，请清理空间后重试",
            )
        DownloadFailureKind.Source ->
            when (error) {
                is OfflineHttpException -> "下载源不可用（HTTP ${error.statusCode}），请检查服务器或媒体源"
                else -> redactDiagnosticText(error.message ?: "下载源不可用，请重新选择媒体源")
            }
        DownloadFailureKind.Unknown -> redactDiagnosticText(error.message ?: "下载失败，可点按重试")
    }

internal fun offlineWakeRequest(
    wifiOnly: Boolean,
    initialDelayMs: Long = 0L,
): OneTimeWorkRequest =
    OneTimeWorkRequest
        .Builder(OfflineDownloadWorker::class.java)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build(),
        ).setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        ).addTag(OFFLINE_WAKE_WORK_NAME)
        .build()

internal fun hasSufficientOfflineStorage(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = OFFLINE_STORAGE_RESERVE_BYTES,
): Boolean {
    val usable = usableSpace.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    return usable >= reserve && required <= usable - reserve
}

internal fun missingOfflineStorageBytes(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = OFFLINE_STORAGE_RESERVE_BYTES,
): Long {
    val usable = usableSpace.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    if (required > Long.MAX_VALUE - reserve) return Long.MAX_VALUE
    val totalRequired = required + reserve
    return (totalRequired - usable).coerceAtLeast(0L)
}

internal fun sameOfflineMediaSource(
    itemId: String,
    first: String?,
    second: String?,
): Boolean = (first ?: itemId) == (second ?: itemId)

private val offlineContentRangePattern =
    Regex("""(?i)^bytes\s+(\d+)-(\d+)/(?:\d+|\*)$""")

internal fun offlineContentRangeStartsAt(
    value: String?,
    expectedOffset: Long,
): Boolean {
    if (expectedOffset < 0L) return false
    val match = value?.trim()?.let(offlineContentRangePattern::matchEntire) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    return start == expectedOffset && end >= start
}

internal fun isOfflineArtifactName(name: String): Boolean =
    name.endsWith(".media") ||
        name.endsWith(".part") ||
        name.endsWith(".srt")

internal fun validateOfflineSubtitleContentLength(
    contentLength: Long,
    maxBytes: Long = MAX_OFFLINE_SUBTITLE_BYTES,
) {
    require(maxBytes >= 0L) { "字幕大小上限无效" }
    if (contentLength >= 0L && contentLength > maxBytes) {
        throw OfflineSubtitleTooLargeException(maxBytes)
    }
}

/** Copies an untrusted subtitle response without allowing an unknown-length body to fill storage. */
internal fun copyOfflineSubtitleBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_OFFLINE_SUBTITLE_BYTES,
    isCurrent: () -> Boolean = { true },
): Long? {
    require(maxBytes >= 0L) { "字幕大小上限无效" }
    var writtenBytes = 0L
    val buffer = ByteArray(32 * 1024)
    while (true) {
        if (!isCurrent()) return null
        val read = input.read(buffer)
        if (read < 0) return writtenBytes
        if (!isCurrent()) return null
        if (read.toLong() > maxBytes - writtenBytes) {
            throw OfflineSubtitleTooLargeException(maxBytes)
        }
        offlineStorageWrite { output.write(buffer, 0, read) }
        writtenBytes += read
    }
}

private fun offlineArtifactPrefix(value: String) =
    value.encodeToByteArray().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

/** Exact artifact names that may still be referenced after process-death recovery. */
internal fun retainedOfflineArtifactNames(
    directory: File,
    items: List<OfflineMedia>,
): Set<String> {
    val root = directory.absoluteFile
    return buildSet {
        items.forEach { item ->
            val prefix = offlineArtifactPrefix(item.id)
            add("$prefix.part")
            add("$prefix.media")
            add("$prefix.srt")
            add("$prefix.${item.downloadRevision}.media")
            add("$prefix.${item.downloadRevision}.subtitle.part")

            // Re-enqueueing an already-completed variant advances the generation while retaining
            // the verified file. Preserve that exact indexed path as well as the current target.
            listOfNotNull(item.localPath, item.subtitlePath).forEach { path ->
                val indexed = File(path).absoluteFile
                if (indexed.parentFile == root) add(indexed.name)
            }
        }
    }
}

internal fun cleanupOrphanedOfflineArtifacts(
    directory: File,
    items: List<OfflineMedia>,
) {
    val retainedNames = retainedOfflineArtifactNames(directory, items)
    directory.listFiles()
        ?.filter(File::isFile)
        ?.filter { it.name !in retainedNames && isOfflineArtifactName(it.name) }
        ?.forEach(File::delete)
}

internal fun canAppendOfflineRange(
    existingBytes: Long,
    statusCode: Int,
    contentRange: String?,
    expectedValidator: String?,
    responseValidator: String?,
): Boolean =
    existingBytes > 0L &&
        statusCode == HttpURLConnection.HTTP_PARTIAL &&
        offlineContentRangeStartsAt(contentRange, existingBytes) &&
        !expectedValidator.isNullOrBlank() &&
        expectedValidator == responseValidator

internal data class OfflineEnqueuePlan(
    val item: OfflineMedia,
    val sourceChanged: Boolean,
)

internal fun planOfflineEnqueue(
    old: OfflineMedia?,
    request: OfflineDownloadRequest,
    nowMs: Long,
): OfflineEnqueuePlan {
    val sourceChanged =
        old != null &&
            !sameOfflineDownloadVariant(
                itemId = request.itemId,
                oldSourceId = old.mediaSourceId,
                newSourceId = request.mediaSourceId,
                oldQuality = old.quality,
                newQuality = request.quality,
                oldSubtitleIndex = old.subtitleStreamIndex,
                newSubtitleIndex = request.subtitleStreamIndex,
            )
    val nextRevision =
        old?.downloadRevision?.let {
            if (it == Long.MAX_VALUE) 0L else it + 1L
        } ?: 1L
    return OfflineEnqueuePlan(
        item =
            OfflineMedia(
                id = "${request.serverId}#${request.itemId}",
                serverId = request.serverId,
                itemId = request.itemId,
                title = request.title,
                mediaSourceId = request.mediaSourceId,
                quality = request.quality,
                subtitleStreamIndex = request.subtitleStreamIndex,
                subtitleCodec = request.subtitleCodec,
                subtitleLanguage = request.subtitleLanguage,
                legacySourceUrl = null,
                posterUrl = null,
                localPath = old?.localPath.takeUnless { sourceChanged },
                subtitlePath = old?.subtitlePath.takeUnless { sourceChanged },
                downloadedBytes = old?.downloadedBytes?.takeUnless { sourceChanged } ?: 0L,
                totalBytes =
                    old?.totalBytes?.takeUnless { sourceChanged }
                        ?: request.estimatedBytes?.coerceAtLeast(0L)
                        ?: 0L,
                downloadRevision = nextRevision,
                resumeValidator = old?.resumeValidator.takeUnless { sourceChanged },
                status =
                    if (!sourceChanged && old?.playable == true) {
                        DownloadStatus.Completed
                    } else {
                        DownloadStatus.Queued
                    },
                updatedAtEpochMs = nowMs,
            ),
        sourceChanged = sourceChanged,
    )
}

actual fun createOfflineMediaManager(
    settings: Settings,
    registry: ServerRegistry,
): OfflineMediaManager = AndroidOfflineMediaManager(offlineApplicationContext, settings, registry)

internal fun sanitizeLegacyOfflineItem(item: OfflineMedia): OfflineMedia =
    item.copy(
        mediaSourceId = item.mediaSourceId ?: item.legacySourceUrl.queryParameter("MediaSourceId"),
        legacySourceUrl = null,
        // The download UI never consumes this field. Dropping it also removes legacy api_key
        // query parameters from the persisted index.
        posterUrl = null,
        error = item.error?.let(::redactDiagnosticText),
    )

internal fun resolveOfflineSourceUrl(
    item: OfflineMedia,
    registry: ServerRegistry,
): String {
    val server =
        registry.serverById(item.serverId)
            ?: error("服务器已移除，无法继续下载")
    return if (item.quality == OfflineDownloadQuality.Original) {
        EmbyStream.directPlay(
            baseUrl = server.baseUrl,
            itemId = item.itemId,
            token = server.accessToken,
            mediaSourceId = item.mediaSourceId,
        )
    } else {
        EmbyStream.progressiveTranscode(
            baseUrl = server.baseUrl,
            itemId = item.itemId,
            token = server.accessToken,
            maxWidth = requireNotNull(item.quality.maxWidth),
            videoBitrate = requireNotNull(item.quality.videoBitrateBps),
            mediaSourceId = item.mediaSourceId,
        )
    }
}

internal fun resolveOfflineSubtitleUrl(
    item: OfflineMedia,
    registry: ServerRegistry,
): String? {
    val index = item.subtitleStreamIndex ?: return null
    val server =
        registry.serverById(item.serverId)
            ?: error("服务器已移除，无法继续下载字幕")
    return EmbyStream.subtitle(
        baseUrl = server.baseUrl,
        itemId = item.itemId,
        mediaSourceId = item.mediaSourceId ?: item.itemId,
        streamIndex = index,
        token = server.accessToken,
        // SRT is broadly readable and asks Emby to convert embedded text subtitles.
        format = "srt",
    )
}

private fun String?.queryParameter(name: String): String? {
    val query =
        this
            ?.substringAfter('?', missingDelimiterValue = "")
            ?.substringBefore('#')
            .orEmpty()
    return query
        .split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = part)
            if (key.equals(name, ignoreCase = true)) part.substringAfter('=', "") else null
        }.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
        }
}

private fun HttpURLConnection.offlineResumeValidator(): String? {
    val strongEtag =
        getHeaderField("ETag")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return "etag:$strongEtag"
    return getHeaderField("Last-Modified")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "last-modified:$it" }
}

private fun String.resumeValidatorHeaderValue(): String = substringAfter(':')

/**
 * Raw offline transfers bypass Ktor, so they validate the user-configured HTTP/HTTPS endpoint
 * before opening a socket. Redirects stay disabled: authenticated Emby download URLs carry
 * api_key in the query and must never be replayed to a different authority.
 */
internal fun requireAllowedOfflineTransferUrl(
    value: String,
    localCleartextConfirmed: Boolean,
): URL {
    val url = URL(value)
    require(url.userInfo == null && url.ref == null) { "下载地址不安全" }
    val validation =
        validateEmbyServerEndpoint(
            "${url.protocol}://${url.authority}",
            localCleartextConfirmed,
        )
    require(validation.allowed) { validation.message ?: "下载地址不安全" }
    return url
}

private fun Long.nextOfflineRevision(): Long = if (this == Long.MAX_VALUE) 0L else this + 1L

/**
 * Publishes the selected subtitle and creates the completed index value while the manager's
 * index lock is held by the caller. The revision-specific subtitle part means a superseded request
 * can never overwrite the sidecar prepared by its replacement.
 */
internal fun publishOfflineCompletionLocked(
    current: OfflineMedia?,
    snapshot: OfflineMedia,
    videoTarget: File,
    subtitlePart: File?,
    subtitleTarget: File,
    nowMs: Long,
): OfflineMedia? {
    if (
        current == null ||
        current.downloadRevision != snapshot.downloadRevision ||
        current.status != DownloadStatus.Downloading
    ) {
        subtitlePart?.delete()
        return null
    }
    if (!videoTarget.isFile) throw OfflineStorageException("离线视频文件不存在")

    val publishedSubtitle =
        subtitlePart
            ?.takeIf(File::isFile)
            ?.let { prepared ->
                runCatching {
                    offlineStorageWrite {
                        Files.move(
                            prepared.toPath(),
                            subtitleTarget.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    subtitleTarget.takeIf(File::isFile)
                }.onFailure { error ->
                    prepared.delete()
                    subtitleTarget.delete()
                    AppLog.warning(
                        category = "offline",
                        event = "subtitle_publish_failed",
                        message = "Video completed but the selected subtitle could not be published",
                        throwable = error,
                        attributes = mapOf("itemId" to snapshot.itemId),
                    )
                }.getOrNull()
            }
    if (publishedSubtitle == null) subtitleTarget.delete()

    return current.copy(
        status = DownloadStatus.Completed,
        localPath = videoTarget.absolutePath,
        subtitlePath = publishedSubtitle?.absolutePath,
        downloadedBytes = videoTarget.length(),
        totalBytes = videoTarget.length(),
        resumeValidator = null,
        error =
            if (snapshot.subtitleStreamIndex != null && publishedSubtitle == null) {
                "视频已完成，但所选字幕未能保存"
            } else {
                null
            },
        retryCount = 0,
        nextRetryAt = 0L,
        lastFailureKind = null,
        updatedAtEpochMs = nowMs,
    )
}

internal class AndroidOfflineMediaManager(
    private val context: Context,
    private val settings: Settings,
    private val registry: ServerRegistry,
) : OfflineMediaManager {
    private companion object {
        const val INDEX_KEY = "offline.media.index.v1"
        const val WIFI_KEY = "offline.media.wifiOnly"
        const val SPACE_CHECK_INTERVAL_BYTES = 8L * 1024L * 1024L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val serializer = ListSerializer(OfflineMedia.serializer())
    private val directory = File(context.filesDir, "offline-media").apply { mkdirs() }
    private val indexLock = Any()
    private val _wifiOnly = MutableStateFlow(settings.getBoolean(WIFI_KEY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val _policy = MutableStateFlow(loadOfflineDownloadPolicy(settings))
    override val policy: StateFlow<OfflineDownloadPolicy> = _policy.asStateFlow()
    private val _items = MutableStateFlow(loadIndex())
    override val items: StateFlow<List<OfflineMedia>> = _items.asStateFlow()
    private val runLock = Mutex()

    init {
        cleanupOrphanedArtifacts(_items.value)
        val recovered =
            _items.value.map { stored ->
                // v1 persisted authenticated source/poster URLs. Extract the non-secret source
                // selection once, then erase both URLs before the index is written again.
                val item = sanitizeLegacyOfflineItem(stored)
                when (item.status) {
                    DownloadStatus.Downloading -> item.copy(status = DownloadStatus.Queued)
                    DownloadStatus.Completed ->
                        if (item.localPath?.let(::File)?.exists() == true) {
                            item
                        } else {
                            item.copy(
                                status = DownloadStatus.Failed,
                                localPath = null,
                                error = "离线文件不存在",
                            )
                        }
                    else -> item
                }
            }
        commit(recovered)
        val resetCount = _items.value.count { it.status == DownloadStatus.Queued }
        val missingCount =
            _items.value.count {
                it.status == DownloadStatus.Failed && it.error == "离线文件不存在"
            }
        if (resetCount > 0 || missingCount > 0) {
            AppLog.warning(
                category = "offline",
                event = "index_recovered",
                message = "Offline download index required recovery",
                attributes =
                    mapOf(
                        "requeuedCount" to resetCount.toString(),
                        "missingFileCount" to missingCount.toString(),
                    ),
            )
        }
        rebuildWakeSchedule(ExistingWorkPolicy.KEEP)
    }

    override fun enqueue(request: OfflineDownloadRequest) {
        val id = "${request.serverId}#${request.itemId}"
        var sourceChanged = false
        lateinit var next: OfflineMedia
        synchronized(indexLock) {
            val old = _items.value.firstOrNull { it.id == id }
            val plan = planOfflineEnqueue(old, request, System.currentTimeMillis())
            sourceChanged = plan.sourceChanged
            next = plan.item
            commitLocked(_items.value.filterNot { it.id == id } + next)
            if (old != null && sourceChanged) {
                // Commit the new revision first so the active loop sees that it was superseded.
                // Keep cleanup under the same lock so a replacement cannot claim its files before
                // the old generation's deterministic artifacts have been removed.
                old.localPath?.let(::File)?.delete()
                old.subtitlePath?.let(::File)?.delete()
                completedFile(old).delete()
                legacyCompletedFile(old.id).delete()
                partFile(old).delete()
                subtitleFile(old.id).delete()
                deleteSubtitlePartFiles(old.id)
            } else if (old != null && !next.playable) {
                // Re-queuing the same variant intentionally preserves its verified range and
                // partial video. Only subtitle staging is revision-bound and cannot be resumed.
                deleteSubtitlePartFiles(old.id)
            }
        }
        AppLog.info(
            category = "offline",
            event = "download_enqueued",
            message = "Offline download enqueued",
            attributes =
                mapOf(
                    "itemId" to request.itemId,
                    "alreadyPlayable" to next.playable.toString(),
                    "sourceChanged" to sourceChanged.toString(),
                ),
        )
        if (!next.playable) kick()
    }

    override fun pause(id: String) {
        update(id) {
            if (
                it.status in
                setOf(
                    DownloadStatus.Queued,
                    DownloadStatus.WaitingForWifi,
                    DownloadStatus.Downloading,
                )
            ) {
                it.copy(
                    status = DownloadStatus.Paused,
                    error = null,
                    nextRetryAt = 0L,
                    lastFailureKind = null,
                    downloadRevision = it.downloadRevision.nextOfflineRevision(),
                    updatedAtEpochMs = now(),
                )
            } else {
                it
            }
        }
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
        AppLog.info("offline", "download_paused", "Offline download paused")
    }

    override fun pauseAll() {
        val nowMs = now()
        synchronized(indexLock) {
            commitLocked(
                _items.value.map { item ->
                    if (
                        item.status in
                        setOf(
                            DownloadStatus.Queued,
                            DownloadStatus.WaitingForWifi,
                            DownloadStatus.Downloading,
                        )
                    ) {
                        item.copy(
                            status = DownloadStatus.Paused,
                            error = null,
                            nextRetryAt = 0L,
                            lastFailureKind = null,
                            downloadRevision = item.downloadRevision.nextOfflineRevision(),
                            updatedAtEpochMs = nowMs,
                        )
                    } else {
                        item
                    }
                },
            )
        }
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
        AppLog.info("offline", "downloads_paused", "All offline downloads paused")
    }

    override fun resume(id: String) {
        update(id) {
            if (it.status == DownloadStatus.Completed || it.status == DownloadStatus.Downloading) {
                it
            } else {
                it.copy(
                    status = DownloadStatus.Queued,
                    error = null,
                    retryCount = 0,
                    nextRetryAt = 0L,
                    lastFailureKind = null,
                    downloadRevision = it.downloadRevision.nextOfflineRevision(),
                    updatedAtEpochMs = now(),
                )
            }
        }
        AppLog.info("offline", "download_resumed", "Offline download resumed")
        kick()
    }

    override fun resumeAll() {
        val nowMs = now()
        var resumed = false
        synchronized(indexLock) {
            commitLocked(
                _items.value.map { item ->
                    if (item.status in setOf(DownloadStatus.Paused, DownloadStatus.Failed)) {
                        resumed = true
                        item.copy(
                            status = DownloadStatus.Queued,
                            error = null,
                            retryCount = 0,
                            nextRetryAt = 0L,
                            lastFailureKind = null,
                            downloadRevision = item.downloadRevision.nextOfflineRevision(),
                            updatedAtEpochMs = nowMs,
                        )
                    } else {
                        item
                    }
                },
            )
        }
        AppLog.info("offline", "downloads_resumed", "Paused and failed offline downloads resumed")
        if (resumed) kick() else rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
    }

    override fun remove(id: String) {
        synchronized(indexLock) {
            _items.value.firstOrNull { it.id == id }?.let { item ->
                // Keep the index until every deterministic artifact is gone. If the process
                // dies during cleanup, startup can still reconcile the retained index instead
                // of leaving an untracked, undeletable media file behind.
                val deleting =
                    item.copy(
                        status = DownloadStatus.Paused,
                        downloadRevision = item.downloadRevision.nextOfflineRevision(),
                        updatedAtEpochMs = now(),
                    )
                // Persist the new revision before deletion so a blocked download cannot create
                // a fresh artifact after this method has removed the index entry.
                commitLocked(_items.value.map { if (it.id == id) deleting else it })
                if (deleteArtifactsLocked(deleting)) {
                    commitLocked(_items.value.filterNot { it.id == id })
                } else {
                    commitLocked(
                        _items.value.map {
                            if (it.id == id) {
                                deleting.copy(error = "无法删除全部离线文件，请重试")
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            // Cleanup by deterministic id even when a prior crash already removed the index row.
            subtitleFile(id).delete()
            deleteSubtitlePartFiles(id)
        }
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
        AppLog.info("offline", "download_removed", "Offline download removed")
    }

    override fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        settings.putBoolean(WIFI_KEY, value)
        persistPolicy(_policy.value.copy(wifiOnly = value))
        val shouldInterrupt = value && !onUnmeteredNetwork()
        synchronized(indexLock) {
            commitLocked(
                _items.value.map { item ->
                    when {
                        !value && item.status == DownloadStatus.WaitingForWifi ->
                            item.copy(
                                status = DownloadStatus.Queued,
                                updatedAtEpochMs = now(),
                            )
                        shouldInterrupt && item.status == DownloadStatus.Downloading ->
                            item.copy(
                                status = DownloadStatus.WaitingForWifi,
                                error = null,
                                downloadRevision = item.downloadRevision.nextOfflineRevision(),
                                updatedAtEpochMs = now(),
                            )
                        else -> item
                    }
                },
            )
        }
        if (!value) kick() else rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
    }

    override fun setMaxConcurrentDownloads(value: Int) {
        persistPolicy(_policy.value.copy(maxConcurrentDownloads = value).normalized())
        kick()
    }

    override fun setAutoDeleteWatched(value: Boolean) {
        persistPolicy(_policy.value.copy(autoDeleteWatched = value))
    }

    override fun onPlaybackCompleted(
        serverId: String,
        itemId: String,
    ) {
        if (!_policy.value.autoDeleteWatched) return
        _items.value
            .firstOrNull { it.serverId == serverId && it.itemId == itemId }
            ?.let { remove(it.id) }
    }

    internal suspend fun runPendingDownloads() =
        runLock.withLock {
            while (true) {
                val nowMs = now()
                val next =
                    selectPendingOfflineDownloads(
                        items = _items.value,
                        nowMs = nowMs,
                        maxConcurrentDownloads = _policy.value.maxConcurrentDownloads,
                    )
                if (next.isEmpty()) break
                if (_wifiOnly.value && !onUnmeteredNetwork()) {
                    AppLog.info(
                        category = "offline",
                        event = "waiting_for_wifi",
                        message = "Offline download is waiting for Wi-Fi",
                    )
                    next.forEach { pending ->
                        update(pending.id) {
                            it.copy(
                                status = DownloadStatus.WaitingForWifi,
                                error = null,
                                updatedAtEpochMs = now(),
                            )
                        }
                    }
                    break
                }
                coroutineScope {
                    next.map { pending -> async { download(pending) } }.awaitAll()
                }
            }
        }

    private suspend fun download(snapshot: OfflineMedia) =
        withContext(Dispatchers.IO) {
            val part = partFile(snapshot)
            part.parentFile?.mkdirs()
            var existing = part.takeIf { it.exists() }?.length() ?: 0L
            var expectedValidator = snapshot.resumeValidator?.takeIf { existing > 0L }
            if (existing > 0L && expectedValidator == null) {
                // Legacy partial files have no proof that the remote object is unchanged.
                part.delete()
                existing = 0L
            }
            var claimed = false
            update(snapshot.id) {
                if (
                    it.downloadRevision != snapshot.downloadRevision ||
                    it.status !in setOf(DownloadStatus.Queued, DownloadStatus.WaitingForWifi)
                ) {
                    it
                } else {
                    claimed = true
                    it.copy(
                        status = DownloadStatus.Downloading,
                        downloadedBytes = existing,
                        resumeValidator = expectedValidator,
                        error = null,
                        nextRetryAt = 0L,
                        lastFailureKind = null,
                        updatedAtEpochMs = now(),
                    )
                }
            }
            if (!claimed) return@withContext

            var connection: HttpURLConnection? = null
            try {
                // A process may stop after the video was fsync'ed and renamed but before its
                // subtitle and Completed index entry were published. That video is verified
                // enough to reuse: continue with the sidecar phase instead of downloading it
                // from byte zero again.
                val finalizedVideo =
                    snapshot.localPath
                        ?.let(::File)
                        ?.takeIf { it.isFile && it.length() > 0L }
                        ?: completedFile(snapshot).takeIf { it.isFile && it.length() > 0L }
                if (finalizedVideo != null) {
                    val subtitlePart = downloadSubtitlePart(snapshot)
                    if (!publishCompletedDownload(snapshot, finalizedVideo, subtitlePart)) {
                        return@withContext
                    }
                    AppLog.info(
                        category = "offline",
                        event = "download_recovered_after_video_finalize",
                        message = "Recovered offline completion after video finalization",
                        attributes = mapOf("itemId" to snapshot.itemId),
                    )
                    return@withContext
                }
                AppLog.info(
                    category = "offline",
                    event = "download_started",
                    message = "Offline media download started",
                    attributes =
                        mapOf(
                            "itemId" to snapshot.itemId,
                            "resumeBytes" to existing.toString(),
                        ),
                )
                val server =
                    registry.serverById(snapshot.serverId)
                        ?: error("服务器已移除，无法继续下载")
                val sourceUrl = resolveOfflineSourceUrl(snapshot, registry)
                val source =
                    requireAllowedOfflineTransferUrl(
                        sourceUrl,
                        server.localCleartextConfirmed,
                    )
                var append: Boolean
                var responseValidator: String?
                while (true) {
                    if (!isCurrentDownload(snapshot)) return@withContext
                    connection =
                        (source.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20_000
                            readTimeout = 30_000
                            instanceFollowRedirects = false
                            if (existing > 0L) {
                                setRequestProperty("Range", "bytes=$existing-")
                                expectedValidator?.let {
                                    setRequestProperty("If-Range", it.resumeValidatorHeaderValue())
                                }
                            }
                        }
                    val code = connection.responseCode
                    responseValidator = connection.offlineResumeValidator()
                    val rangeCanAppend =
                        canAppendOfflineRange(
                            existingBytes = existing,
                            statusCode = code,
                            contentRange = connection.getHeaderField("Content-Range"),
                            expectedValidator = expectedValidator,
                            responseValidator = responseValidator,
                        )
                    val invalidResume =
                        existing > 0L &&
                            (
                                code == 416 ||
                                    (code == HttpURLConnection.HTTP_PARTIAL && !rangeCanAppend)
                            )
                    if (invalidResume) {
                        AppLog.warning(
                            category = "offline",
                            event = "resume_rejected",
                            message = "Offline range response did not match the saved partial file",
                            attributes =
                                mapOf(
                                    "itemId" to snapshot.itemId,
                                    "status" to code.toString(),
                                ),
                        )
                        connection.disconnect()
                        connection = null
                        part.delete()
                        existing = 0L
                        expectedValidator = null
                        update(snapshot.id) {
                            if (it.downloadRevision == snapshot.downloadRevision) {
                                it.copy(
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    resumeValidator = null,
                                    updatedAtEpochMs = now(),
                                )
                            } else {
                                it
                            }
                        }
                        continue
                    }
                    if (code !in 200..299) throw OfflineHttpException(code)
                    if (code == HttpURLConnection.HTTP_PARTIAL && existing == 0L) {
                        error("服务器返回了无请求的分段响应")
                    }
                    append = rangeCanAppend
                    if (!append && existing > 0L) {
                        // If-Range correctly returns 200 when the object changed.
                        existing = 0L
                        part.delete()
                    }
                    break
                }
                val remaining = connection.contentLengthLong.coerceAtLeast(0L)
                val total =
                    if (remaining > 0L && existing <= Long.MAX_VALUE - remaining) {
                        existing + remaining
                    } else {
                        0L
                    }
                update(snapshot.id) {
                    if (it.downloadRevision == snapshot.downloadRevision) {
                        it.copy(
                            downloadedBytes = existing,
                            totalBytes = total,
                            resumeValidator = responseValidator,
                            updatedAtEpochMs = now(),
                        )
                    } else {
                        it
                    }
                }
                if (!isCurrentDownload(snapshot)) return@withContext
                // Unknown-length responses still need one complete check interval available
                // before the first byte is written; otherwise they could consume the reserve
                // before the periodic check gets its first chance to stop the stream.
                ensureStorageAvailable(
                    requiredBytes = if (remaining > 0L) remaining else SPACE_CHECK_INTERVAL_BYTES,
                )
                connection.inputStream.use { input ->
                    val output = offlineStorageWrite { FileOutputStream(part, append) }
                    try {
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = existing
                        var lastPersisted = downloaded
                        var lastSpaceCheck = downloaded
                        while (true) {
                            if (!isCurrentDownload(snapshot)) return@withContext
                            val read = input.read(buffer)
                            if (read < 0) break
                            // The request can be replaced while input.read() is blocked.
                            if (!isCurrentDownload(snapshot)) return@withContext
                            if (downloaded - lastSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                                val reportedRemaining = if (total > 0L) total - downloaded else 0L
                                val nextWindow =
                                    if (reportedRemaining > 0L) {
                                        minOf(reportedRemaining, SPACE_CHECK_INTERVAL_BYTES)
                                    } else {
                                        SPACE_CHECK_INTERVAL_BYTES
                                    }
                                ensureStorageAvailable(requiredBytes = nextWindow)
                                lastSpaceCheck = downloaded
                            }
                            offlineStorageWrite { output.write(buffer, 0, read) }
                            downloaded += read
                            if (downloaded - lastPersisted >= 512 * 1024) {
                                lastPersisted = downloaded
                                update(snapshot.id) {
                                    if (it.downloadRevision == snapshot.downloadRevision) {
                                        it.copy(
                                            downloadedBytes = downloaded,
                                            totalBytes = total,
                                            status = DownloadStatus.Downloading,
                                            updatedAtEpochMs = now(),
                                        )
                                    } else {
                                        it
                                    }
                                }
                            }
                        }
                        offlineStorageWrite { output.fd.sync() }
                    } finally {
                        offlineStorageWrite { output.close() }
                    }
                }
                if (total > 0L) {
                    if (part.length() != total) throw IOException("下载连接提前结束，内容不完整")
                }
                val target = finalizeVideo(snapshot, part) ?: return@withContext
                val subtitlePart = downloadSubtitlePart(snapshot)
                if (!publishCompletedDownload(snapshot, target, subtitlePart)) return@withContext
                AppLog.info(
                    category = "offline",
                    event = "download_completed",
                    message = "Offline media download completed",
                    attributes =
                        mapOf(
                            "itemId" to snapshot.itemId,
                            "bytes" to target.length().toString(),
                        ),
                )
            } catch (cancelled: CancellationException) {
                update(snapshot.id) {
                    if (
                        it.downloadRevision == snapshot.downloadRevision &&
                        it.status == DownloadStatus.Downloading
                    ) {
                        it.copy(
                            status =
                                if (_wifiOnly.value && !onUnmeteredNetwork()) {
                                    DownloadStatus.WaitingForWifi
                                } else {
                                    DownloadStatus.Queued
                                },
                            downloadedBytes = part.takeIf(File::exists)?.length() ?: 0L,
                            error = null,
                            nextRetryAt = 0L,
                            lastFailureKind = null,
                            updatedAtEpochMs = now(),
                        )
                    } else {
                        it
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                val current = _items.value.firstOrNull { it.id == snapshot.id }
                if (current == null || current.downloadRevision != snapshot.downloadRevision) {
                    return@withContext
                }
                val currentStatus = current.status
                if (currentStatus != DownloadStatus.Paused) {
                    AppLog.error(
                        category = "offline",
                        event = "download_failed",
                        message = "Offline media download failed",
                        throwable = error,
                        attributes = mapOf("itemId" to snapshot.itemId),
                    )
                }
                val failureKind = offlineFailureKind(error)
                val retry = planOfflineRetry(failureKind, current.retryCount, now())
                val failureMessage =
                    offlineFailureMessage(
                        kind = failureKind,
                        retry = retry,
                        error = error,
                    )
                update(snapshot.id) {
                    if (
                        it.downloadRevision != snapshot.downloadRevision ||
                        it.status == DownloadStatus.Paused
                    ) {
                        it
                    } else {
                        it.copy(
                            status = if (retry == null) DownloadStatus.Failed else DownloadStatus.Queued,
                            downloadedBytes = part.takeIf(File::exists)?.length() ?: 0L,
                            error = failureMessage,
                            retryCount = retry?.retryCount ?: it.retryCount,
                            nextRetryAt = retry?.nextRetryAt ?: 0L,
                            lastFailureKind = failureKind,
                            updatedAtEpochMs = now(),
                        )
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }

    private fun kick() {
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
    }

    internal fun rebuildWakeSchedule(
        policy: ExistingWorkPolicy,
        cancelWhenEmpty: Boolean = true,
    ) {
        val pending =
            _items.value.filter {
                it.status == DownloadStatus.Queued || it.status == DownloadStatus.WaitingForWifi
            }
        val workManager = WorkManager.getInstance(context)
        if (pending.isEmpty()) {
            if (cancelWhenEmpty) workManager.cancelUniqueWork(OFFLINE_WAKE_WORK_NAME)
            return
        }
        val nowMs = now()
        val earliest = pending.minOf { it.nextRetryAt.coerceAtLeast(0L) }
        val delayMs = (earliest - nowMs).coerceAtLeast(0L)
        workManager.enqueueUniqueWork(
            OFFLINE_WAKE_WORK_NAME,
            policy,
            offlineWakeRequest(wifiOnly = _wifiOnly.value, initialDelayMs = delayMs),
        )
    }

    private fun onUnmeteredNetwork(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isCurrentDownload(snapshot: OfflineMedia): Boolean =
        synchronized(indexLock) {
            _items.value.firstOrNull { it.id == snapshot.id }?.let {
                it.downloadRevision == snapshot.downloadRevision &&
                    it.status == DownloadStatus.Downloading
            } == true
        }

    private fun finalizeVideo(
        snapshot: OfflineMedia,
        part: File,
    ): File? =
        synchronized(indexLock) {
            val current =
                _items.value
                    .firstOrNull { it.id == snapshot.id }
                    ?.takeIf {
                        it.downloadRevision == snapshot.downloadRevision &&
                            it.status == DownloadStatus.Downloading
                    }
                    ?: return@synchronized null
            val target = completedFile(current)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw OfflineStorageException("无法保存离线文件")
            // Durably remember the finalized video before the subtitle phase. On an interrupted
            // run this lets the next worker resume from the local video, not byte zero.
            commitLocked(
                _items.value.map {
                    if (it.id == current.id) {
                        it.copy(
                            localPath = target.absolutePath,
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
                            resumeValidator = null,
                            updatedAtEpochMs = now(),
                        )
                    } else {
                        it
                    }
                },
            )
            target
        }

    private suspend fun downloadSubtitlePart(snapshot: OfflineMedia): File? =
        withContext(Dispatchers.IO) {
            val sourceUrl =
                runCatching { resolveOfflineSubtitleUrl(snapshot, registry) }
                    .onFailure { error -> logSubtitleFailure(snapshot, error) }
                    .getOrNull()
                    ?: return@withContext null
            val server =
                registry.serverById(snapshot.serverId)
                    ?: return@withContext null
            val source =
                runCatching {
                    requireAllowedOfflineTransferUrl(
                        sourceUrl,
                        server.localCleartextConfirmed,
                    )
                }.onFailure { error -> logSubtitleFailure(snapshot, error) }
                    .getOrNull()
                    ?: return@withContext null
            val part = subtitlePartFile(snapshot)
            part.delete()
            var connection: HttpURLConnection? = null
            try {
                if (!isCurrentDownload(snapshot)) return@withContext null
                connection =
                    (source.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 30_000
                        instanceFollowRedirects = false
                    }
                if (connection.responseCode !in 200..299) {
                    throw OfflineHttpException(connection.responseCode)
                }
                validateOfflineSubtitleContentLength(connection.contentLengthLong)
                connection.inputStream.use { input ->
                    val output = offlineStorageWrite { FileOutputStream(part, false) }
                    val copiedBytes =
                        try {
                            copyOfflineSubtitleBounded(input, output) {
                                isCurrentDownload(snapshot)
                            }.also { copied ->
                                if (copied != null) offlineStorageWrite { output.fd.sync() }
                            }
                        } finally {
                            offlineStorageWrite { output.close() }
                        }
                    if (copiedBytes == null) {
                        part.delete()
                        return@withContext null
                    }
                }
                if (!isCurrentDownload(snapshot)) {
                    part.delete()
                    return@withContext null
                }
                part
            } catch (cancelled: CancellationException) {
                part.delete()
                throw cancelled
            } catch (error: Throwable) {
                part.delete()
                logSubtitleFailure(snapshot, error)
                null
            } finally {
                connection?.disconnect()
                if (!isCurrentDownload(snapshot)) part.delete()
            }
        }

    private fun publishCompletedDownload(
        snapshot: OfflineMedia,
        videoTarget: File,
        subtitlePart: File?,
    ): Boolean =
        synchronized(indexLock) {
            val current = _items.value.firstOrNull { it.id == snapshot.id }
            val completed =
                publishOfflineCompletionLocked(
                    current = current,
                    snapshot = snapshot,
                    videoTarget = videoTarget,
                    subtitlePart = subtitlePart,
                    subtitleTarget = subtitleFile(snapshot.id),
                    nowMs = now(),
                ) ?: return@synchronized false
            commitLocked(_items.value.map { if (it.id == snapshot.id) completed else it })
            true
        }

    private fun logSubtitleFailure(
        snapshot: OfflineMedia,
        error: Throwable,
    ) {
        AppLog.warning(
            category = "offline",
            event = "subtitle_download_failed",
            message = "Video completed but the selected subtitle could not be saved",
            throwable = error,
            attributes = mapOf("itemId" to snapshot.itemId),
        )
    }

    private fun update(
        id: String,
        transform: (OfflineMedia) -> OfflineMedia,
    ) {
        synchronized(indexLock) {
            commitLocked(_items.value.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun commit(value: List<OfflineMedia>) {
        synchronized(indexLock) { commitLocked(value) }
    }

    private fun commitLocked(value: List<OfflineMedia>) {
        _items.value = value.sortedByDescending { it.updatedAtEpochMs }
        settings.putString(INDEX_KEY, json.encodeToString(serializer, _items.value))
    }

    private fun loadIndex(): List<OfflineMedia> {
        val raw = settings.getStringOrNull(INDEX_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(serializer, raw)
        }.onFailure {
            AppLog.error(
                category = "offline",
                event = "stored_index_invalid",
                message = "Stored offline download index could not be decoded",
                throwable = it,
            )
        }.getOrDefault(emptyList())
    }

    /**
     * The offline directory is private to this manager. Delete any deterministic artifact that
     * has no matching index row after a process death between artifact cleanup and index commit.
     * Artifacts belonging to a queued recovery are deliberately retained, including a finalized
     * video awaiting its subtitle/index publication.
     */
    private fun cleanupOrphanedArtifacts(items: List<OfflineMedia>) {
        synchronized(indexLock) {
            cleanupOrphanedOfflineArtifacts(directory, items)
        }
    }

    private fun deleteArtifactsLocked(item: OfflineMedia): Boolean {
        val artifacts =
            listOfNotNull(
                item.localPath?.let(::File),
                item.subtitlePath?.let(::File),
                completedFile(item),
                legacyCompletedFile(item.id),
                partFile(item),
                subtitleFile(item.id),
            ).distinct()
        artifacts.forEach { artifact ->
            if (artifact.exists() && !artifact.delete()) {
                AppLog.warning(
                    category = "offline",
                    event = "offline_artifact_delete_failed",
                    message = "Offline artifact could not be deleted; retaining its index entry",
                    attributes = mapOf("itemId" to item.itemId),
                )
            }
        }
        deleteSubtitlePartFiles(item.id)
        return artifacts.none(File::exists) &&
            directory.listFiles()?.none { candidate ->
                candidate.name.startsWith(safeFileName(item.id) + ".") &&
                    candidate.name.endsWith(".subtitle.part")
            } != false
    }

    private fun partFile(item: OfflineMedia) = File(directory, safeFileName(item.id) + ".part")

    // Video publication has a revision-specific name. A cancelled or superseded finalization
    // therefore cannot be mistaken for the replacement's source on a later recovery.
    private fun completedFile(item: OfflineMedia) =
        File(directory, "${safeFileName(item.id)}.${item.downloadRevision}.media")

    // v1/v2 used an id-only target. It is never selected for a new download, but removal still
    // clears it so upgraded installs cannot retain an old artifact indefinitely.
    private fun legacyCompletedFile(id: String) = File(directory, safeFileName(id) + ".media")

    private fun subtitleFile(id: String) = File(directory, safeFileName(id) + ".srt")

    private fun subtitlePartFile(item: OfflineMedia) =
        File(directory, "${safeFileName(item.id)}.${item.downloadRevision}.subtitle.part")

    private fun deleteSubtitlePartFiles(id: String) {
        val prefix = safeFileName(id) + "."
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith(prefix) && candidate.name.endsWith(".subtitle.part")) {
                candidate.delete()
            }
        }
    }

    private fun persistPolicy(value: OfflineDownloadPolicy) {
        val normalized = persistOfflineDownloadPolicy(settings, value)
        _policy.value = normalized
        _wifiOnly.value = normalized.wifiOnly
    }

    private fun ensureStorageAvailable(requiredBytes: Long) {
        // File.usableSpace excludes bytes Android can reclaim from this app's cache and can
        // therefore reject a download even when StorageManager can allocate it safely.
        val usable = allocatableOfflineBytes(context, directory)
        if (!hasSufficientOfflineStorage(usable, requiredBytes)) {
            val missingBytes = missingOfflineStorageBytes(usable, requiredBytes).coerceAtLeast(1L)
            val bytesPerMb = 1024L * 1024L
            val missingMb = ((missingBytes - 1L) / bytesPerMb) + 1L
            throw OfflineStorageException("存储空间不足，至少还需 $missingMb MB 可用空间")
        }
    }

    private fun safeFileName(value: String) = offlineArtifactPrefix(value)

    private fun now() = System.currentTimeMillis()
}

private fun allocatableOfflineBytes(
    context: Context,
    directory: File,
): Long =
    runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(directory))
    }.getOrElse {
        runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
    }

private fun ensureOfflineNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(
            OFFLINE_NOTIFICATION_CHANNEL_ID,
            "离线下载",
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

private fun offlineDownloadNotification(
    context: Context,
    title: String,
    downloaded: Long,
    total: Long,
): Notification {
    val contentIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val progress =
        if (total > 0L) {
            ((downloaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
    return Notification
        .Builder(context, OFFLINE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(if (total > 0L) "$progress%" else "正在连接服务器")
        .setContentIntent(contentIntent)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progress, total <= 0L)
        .build()
}

private fun offlineForegroundInfo(context: Context): ForegroundInfo {
    ensureOfflineNotificationChannel(context)
    val notification = offlineDownloadNotification(context, "准备下载", 0L, 0L)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            OFFLINE_WORK_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    } else {
        ForegroundInfo(OFFLINE_WORK_NOTIFICATION_ID, notification)
    }
}

class OfflineDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val manager =
            runCatching {
                GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
            }.getOrElse { error ->
                AppLog.error(
                    category = "offline",
                    event = "worker_dependency_failed",
                    message = "Offline worker could not resolve the shared download manager",
                    throwable = error,
                )
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        return try {
            setForeground(offlineForegroundInfo(applicationContext))
            coroutineScope {
                val updates =
                    launch {
                        manager.items.collectLatest { items ->
                            val active =
                                items.firstOrNull {
                                    it.status == DownloadStatus.Downloading
                                }
                            if (active != null) {
                                applicationContext
                                    .getSystemService(NotificationManager::class.java)
                                    .notify(
                                        OFFLINE_WORK_NOTIFICATION_ID,
                                        offlineDownloadNotification(
                                            context = applicationContext,
                                            title = active.title,
                                            downloaded = active.downloadedBytes,
                                            total = active.totalBytes,
                                        ),
                                    )
                            }
                        }
                    }
                try {
                    manager.runPendingDownloads()
                } finally {
                    updates.cancel()
                }
            }
            // Append behind this running worker instead of replacing/cancelling it. This is
            // needed when a recoverable item persisted a future retry timestamp.
            manager.rebuildWakeSchedule(
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                cancelWhenEmpty = false,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.error(
                category = "offline",
                event = "worker_failed",
                message = "Offline worker failed outside an individual download",
                throwable = error,
            )
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                manager.rebuildWakeSchedule(
                    policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                    cancelWhenEmpty = false,
                )
                Result.failure()
            }
        }
    }
}

class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureOfflineNotificationChannel(this)
        startForeground(
            OFFLINE_SERVICE_NOTIFICATION_ID,
            offlineDownloadNotification(this, "准备下载", 0, 0),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (work?.isActive == true) return START_NOT_STICKY
        val manager = GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
        work =
            scope.launch {
                val updates =
                    launch {
                        manager.items.collectLatest { items ->
                            val active = items.firstOrNull { it.status == DownloadStatus.Downloading }
                            if (active != null) {
                                getSystemService(NotificationManager::class.java).notify(
                                    OFFLINE_SERVICE_NOTIFICATION_ID,
                                    offlineDownloadNotification(
                                        context = this@OfflineDownloadService,
                                        title = active.title,
                                        downloaded = active.downloadedBytes,
                                        total = active.totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                try {
                    manager.runPendingDownloads()
                } finally {
                    updates.cancel()
                    manager.rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
