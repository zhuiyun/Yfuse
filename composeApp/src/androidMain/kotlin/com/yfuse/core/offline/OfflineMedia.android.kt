package com.yfuse.core.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import com.russhwolf.settings.Settings
import com.yfuse.MainActivity
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.redactDiagnosticText
import com.yfuse.core.network.EmbyStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext

internal lateinit var offlineApplicationContext: Context

internal const val OFFLINE_STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L

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

internal fun offlineContentRangeStartsAt(value: String?, expectedOffset: Long): Boolean {
    if (expectedOffset < 0L) return false
    val match = value?.trim()?.let(offlineContentRangePattern::matchEntire) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    return start == expectedOffset && end >= start
}

internal fun canAppendOfflineRange(
    existingBytes: Long,
    statusCode: Int,
    contentRange: String?,
    expectedValidator: String?,
    responseValidator: String?,
): Boolean = existingBytes > 0L &&
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
    val sourceChanged = old != null && !sameOfflineMediaSource(
        itemId = request.itemId,
        first = old.mediaSourceId,
        second = request.mediaSourceId,
    )
    val nextRevision = old?.downloadRevision?.let {
        if (it == Long.MAX_VALUE) 0L else it + 1L
    } ?: 1L
    return OfflineEnqueuePlan(
        item = OfflineMedia(
            id = "${request.serverId}#${request.itemId}",
            serverId = request.serverId,
            itemId = request.itemId,
            title = request.title,
            mediaSourceId = request.mediaSourceId,
            legacySourceUrl = null,
            posterUrl = null,
            localPath = old?.localPath.takeUnless { sourceChanged },
            downloadedBytes = old?.downloadedBytes?.takeUnless { sourceChanged } ?: 0L,
            totalBytes = old?.totalBytes?.takeUnless { sourceChanged } ?: 0L,
            downloadRevision = nextRevision,
            resumeValidator = old?.resumeValidator.takeUnless { sourceChanged },
            status = if (!sourceChanged && old?.playable == true) {
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
): OfflineMediaManager =
    AndroidOfflineMediaManager(offlineApplicationContext, settings, registry)

internal fun sanitizeLegacyOfflineItem(item: OfflineMedia): OfflineMedia = item.copy(
    mediaSourceId = item.mediaSourceId ?: item.legacySourceUrl.queryParameter("MediaSourceId"),
    legacySourceUrl = null,
    // The download UI never consumes this field. Dropping it also removes legacy api_key
    // query parameters from the persisted index.
    posterUrl = null,
    error = item.error?.let(::redactDiagnosticText),
)

internal fun resolveOfflineSourceUrl(item: OfflineMedia, registry: ServerRegistry): String {
    val server = registry.serverById(item.serverId)
        ?: error("服务器已移除，无法继续下载")
    return EmbyStream.directPlay(
        baseUrl = server.baseUrl,
        itemId = item.itemId,
        token = server.accessToken,
        mediaSourceId = item.mediaSourceId,
    )
}

private fun String?.queryParameter(name: String): String? {
    val query = this?.substringAfter('?', missingDelimiterValue = "")
        ?.substringBefore('#')
        .orEmpty()
    return query.split('&')
        .asSequence()
        .mapNotNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = part)
            if (key.equals(name, ignoreCase = true)) part.substringAfter('=', "") else null
        }
        .firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded ->
            runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
        }
}

private fun HttpURLConnection.offlineResumeValidator(): String? {
    val strongEtag = getHeaderField("ETag")
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return "etag:$strongEtag"
    return getHeaderField("Last-Modified")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "last-modified:$it" }
}

private fun String.resumeValidatorHeaderValue(): String = substringAfter(':')

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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(OfflineMedia.serializer())
    private val directory = File(context.filesDir, "offline-media").apply { mkdirs() }
    private val indexLock = Any()
    private val _items = MutableStateFlow(loadIndex())
    override val items: StateFlow<List<OfflineMedia>> = _items.asStateFlow()
    private val _wifiOnly = MutableStateFlow(settings.getBoolean(WIFI_KEY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val runLock = Any()
    private var running = false

    init {
        val recovered = _items.value.map { stored ->
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
        val missingCount = _items.value.count {
            it.status == DownloadStatus.Failed && it.error == "离线文件不存在"
        }
        if (resetCount > 0 || missingCount > 0) {
            AppLog.warning(
                category = "offline",
                event = "index_recovered",
                message = "Offline download index required recovery",
                attributes = mapOf(
                    "requeuedCount" to resetCount.toString(),
                    "missingFileCount" to missingCount.toString(),
                ),
            )
        }
        if (recovered.any { it.status == DownloadStatus.Queued }) kick()
    }

    override fun enqueue(request: OfflineDownloadRequest) {
        val id = "${request.serverId}#${request.itemId}"
        var old: OfflineMedia? = null
        var sourceChanged = false
        lateinit var next: OfflineMedia
        synchronized(indexLock) {
            old = _items.value.firstOrNull { it.id == id }
            val plan = planOfflineEnqueue(old, request, System.currentTimeMillis())
            sourceChanged = plan.sourceChanged
            next = plan.item
            commitLocked(_items.value.filterNot { it.id == id } + next)
        }
        if (sourceChanged && old != null) {
            // Commit the new revision first so the active loop sees that it was superseded
            // before its old file handles are removed.
            old?.localPath?.let(::File)?.delete()
            old?.let(::completedFile)?.delete()
            old?.let(::partFile)?.delete()
        }
        AppLog.info(
            category = "offline",
            event = "download_enqueued",
            message = "Offline download enqueued",
            attributes = mapOf(
                "itemId" to request.itemId,
                "alreadyPlayable" to next.playable.toString(),
                "sourceChanged" to sourceChanged.toString(),
            ),
        )
        if (!next.playable) kick()
    }

    override fun pause(id: String) {
        update(id) { it.copy(status = DownloadStatus.Paused, updatedAtEpochMs = now()) }
        AppLog.info("offline", "download_paused", "Offline download paused")
    }

    override fun resume(id: String) {
        update(id) {
            it.copy(
                status = DownloadStatus.Queued,
                error = null,
                downloadRevision = if (it.downloadRevision == Long.MAX_VALUE) {
                    0L
                } else {
                    it.downloadRevision + 1L
                },
                updatedAtEpochMs = now(),
            )
        }
        AppLog.info("offline", "download_resumed", "Offline download resumed")
        kick()
    }

    override fun remove(id: String) {
        val removed = synchronized(indexLock) {
            val item = _items.value.firstOrNull { it.id == id }
            commitLocked(_items.value.filterNot { it.id == id })
            item
        }
        removed?.let { item ->
            item.localPath?.let(::File)?.delete()
            partFile(item).delete()
        }
        AppLog.info("offline", "download_removed", "Offline download removed")
    }

    override fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        settings.putBoolean(WIFI_KEY, value)
        if (!value) {
            synchronized(indexLock) {
                commitLocked(
                    _items.value.map {
                    if (it.status == DownloadStatus.WaitingForWifi) {
                        it.copy(status = DownloadStatus.Queued)
                    } else {
                        it
                    }
                    },
                )
            }
            kick()
        }
    }

    internal suspend fun runPendingDownloads() {
        synchronized(runLock) {
            if (running) return
            running = true
        }
        try {
            while (true) {
                val next = _items.value.firstOrNull {
                    it.status == DownloadStatus.Queued ||
                        it.status == DownloadStatus.WaitingForWifi
                } ?: break
                if (_wifiOnly.value && !onWifi()) {
                    AppLog.info(
                        category = "offline",
                        event = "waiting_for_wifi",
                        message = "Offline download is waiting for Wi-Fi",
                    )
                    update(next.id) {
                        it.copy(
                            status = DownloadStatus.WaitingForWifi,
                            error = null,
                            updatedAtEpochMs = now(),
                        )
                    }
                    break
                }
                download(next)
            }
        } finally {
            synchronized(runLock) { running = false }
        }
    }

    private suspend fun download(snapshot: OfflineMedia) = withContext(Dispatchers.IO) {
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
                    updatedAtEpochMs = now(),
                )
            }
        }
        if (!claimed) return@withContext

        var connection: HttpURLConnection? = null
        try {
            AppLog.info(
                category = "offline",
                event = "download_started",
                message = "Offline media download started",
                attributes = mapOf(
                    "itemId" to snapshot.itemId,
                    "resumeBytes" to existing.toString(),
                ),
            )
            val sourceUrl = resolveOfflineSourceUrl(snapshot, registry)
            var append: Boolean
            var responseValidator: String?
            while (true) {
                if (!isCurrentDownload(snapshot)) return@withContext
                connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (existing > 0L) {
                        setRequestProperty("Range", "bytes=$existing-")
                        expectedValidator?.let {
                            setRequestProperty("If-Range", it.resumeValidatorHeaderValue())
                        }
                    }
                }
                val code = connection.responseCode
                responseValidator = connection.offlineResumeValidator()
                val rangeCanAppend = canAppendOfflineRange(
                    existingBytes = existing,
                    statusCode = code,
                    contentRange = connection.getHeaderField("Content-Range"),
                    expectedValidator = expectedValidator,
                    responseValidator = responseValidator,
                )
                val invalidResume = existing > 0L && (
                    code == 416 ||
                        (code == HttpURLConnection.HTTP_PARTIAL && !rangeCanAppend)
                    )
                if (invalidResume) {
                    AppLog.warning(
                        category = "offline",
                        event = "resume_rejected",
                        message = "Offline range response did not match the saved partial file",
                        attributes = mapOf(
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
                if (code !in 200..299) error("HTTP $code")
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
            val total = if (remaining > 0L && existing <= Long.MAX_VALUE - remaining) {
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
                FileOutputStream(part, append).use { output ->
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
                            val nextWindow = if (reportedRemaining > 0L) {
                                minOf(reportedRemaining, SPACE_CHECK_INTERVAL_BYTES)
                            } else {
                                SPACE_CHECK_INTERVAL_BYTES
                            }
                            ensureStorageAvailable(requiredBytes = nextWindow)
                            lastSpaceCheck = downloaded
                        }
                        output.write(buffer, 0, read)
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
                    output.fd.sync()
                }
            }
            if (total > 0L) {
                check(part.length() == total) { "下载内容不完整" }
            }
            val target = finalizeDownload(snapshot, part) ?: return@withContext
            AppLog.info(
                category = "offline",
                event = "download_completed",
                message = "Offline media download completed",
                attributes = mapOf(
                    "itemId" to snapshot.itemId,
                    "bytes" to target.length().toString(),
                ),
            )
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
            update(snapshot.id) {
                if (
                    it.downloadRevision != snapshot.downloadRevision ||
                    it.status == DownloadStatus.Paused
                ) {
                    it
                } else {
                    it.copy(
                        status = DownloadStatus.Failed,
                        downloadedBytes = part.takeIf(File::exists)?.length() ?: 0L,
                        // URLConnection errors may embed the authenticated request URL.
                        // This field is persisted, so apply the same token policy as diagnostics.
                        error = redactDiagnosticText(error.message ?: "下载失败"),
                        updatedAtEpochMs = now(),
                    )
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun kick() {
        context.startForegroundService(Intent(context, OfflineDownloadService::class.java))
    }

    private fun onWifi(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun isCurrentDownload(snapshot: OfflineMedia): Boolean = synchronized(indexLock) {
        _items.value.firstOrNull { it.id == snapshot.id }?.let {
            it.downloadRevision == snapshot.downloadRevision &&
                it.status == DownloadStatus.Downloading
        } == true
    }

    private fun finalizeDownload(snapshot: OfflineMedia, part: File): File? =
        synchronized(indexLock) {
            val current = _items.value.firstOrNull { it.id == snapshot.id }
                ?.takeIf {
                    it.downloadRevision == snapshot.downloadRevision &&
                        it.status == DownloadStatus.Downloading
                }
                ?: return@synchronized null
            val target = completedFile(current)
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "无法保存离线文件" }
            commitLocked(
                _items.value.map {
                    if (it.id == current.id) {
                        it.copy(
                            status = DownloadStatus.Completed,
                            localPath = target.absolutePath,
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
                            resumeValidator = null,
                            error = null,
                            updatedAtEpochMs = now(),
                        )
                    } else {
                        it
                    }
                },
            )
            target
        }

    private fun update(id: String, transform: (OfflineMedia) -> OfflineMedia) {
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

    private fun partFile(item: OfflineMedia) =
        File(directory, safeFileName(item.id) + ".part")

    private fun completedFile(item: OfflineMedia) =
        File(directory, safeFileName(item.id) + ".media")

    private fun ensureStorageAvailable(requiredBytes: Long) {
        val usable = directory.usableSpace
        if (!hasSufficientOfflineStorage(usable, requiredBytes)) {
            val missingBytes = missingOfflineStorageBytes(usable, requiredBytes).coerceAtLeast(1L)
            val bytesPerMb = 1024L * 1024L
            val missingMb = ((missingBytes - 1L) / bytesPerMb) + 1L
            error("存储空间不足，至少还需 $missingMb MB 可用空间")
        }
    }

    private fun safeFileName(value: String) =
        value.encodeToByteArray().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun now() = System.currentTimeMillis()
}

class OfflineDownloadService : Service() {
    private companion object {
        const val CHANNEL_ID = "yfuse_downloads"
        const val NOTIFICATION_ID = 2410
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "离线下载", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification("准备下载", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (work?.isActive == true) return START_NOT_STICKY
        val manager = GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
        work = scope.launch {
            val updates = launch {
                manager.items.collectLatest { items ->
                    val active = items.firstOrNull { it.status == DownloadStatus.Downloading }
                    if (active != null) {
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            notification(
                                active.title,
                                active.downloadedBytes,
                                active.totalBytes,
                            ),
                        )
                    }
                }
            }
            manager.runPendingDownloads()
            updates.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(title: String, downloaded: Long, total: Long): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val progress = if (total > 0L) {
            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (total > 0L) "$progress%" else "正在连接服务器")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, total <= 0L)
            .build()
    }
}
