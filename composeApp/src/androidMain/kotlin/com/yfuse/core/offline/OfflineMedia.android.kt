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
import com.yfuse.core.logging.AppLog
import android.os.IBinder
import com.russhwolf.settings.Settings
import com.yfuse.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

actual fun createOfflineMediaManager(settings: Settings): OfflineMediaManager =
    AndroidOfflineMediaManager(offlineApplicationContext, settings)

internal class AndroidOfflineMediaManager(
    private val context: Context,
    private val settings: Settings,
) : OfflineMediaManager {

    private companion object {
        const val INDEX_KEY = "offline.media.index.v1"
        const val WIFI_KEY = "offline.media.wifiOnly"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(OfflineMedia.serializer())
    private val directory = File(context.filesDir, "offline-media").apply { mkdirs() }
    private val _items = MutableStateFlow(loadIndex())
    override val items: StateFlow<List<OfflineMedia>> = _items.asStateFlow()
    private val _wifiOnly = MutableStateFlow(settings.getBoolean(WIFI_KEY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val runLock = Any()
    private var running = false

    init {
        val recovered = _items.value.map { item ->
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
        val old = _items.value.firstOrNull { it.id == id }
        val next = OfflineMedia(
            id = id,
            serverId = request.serverId,
            itemId = request.itemId,
            title = request.title,
            sourceUrl = request.sourceUrl,
            posterUrl = request.posterUrl,
            localPath = old?.localPath,
            downloadedBytes = old?.downloadedBytes ?: 0L,
            totalBytes = old?.totalBytes ?: 0L,
            status = if (old?.playable == true) DownloadStatus.Completed else DownloadStatus.Queued,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        commit(_items.value.filterNot { it.id == id } + next)
        AppLog.info(
            category = "offline",
            event = "download_enqueued",
            message = "Offline download enqueued",
            attributes = mapOf(
                "itemId" to request.itemId,
                "alreadyPlayable" to next.playable.toString(),
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
            it.copy(status = DownloadStatus.Queued, error = null, updatedAtEpochMs = now())
        }
        AppLog.info("offline", "download_resumed", "Offline download resumed")
        kick()
    }

    override fun remove(id: String) {
        _items.value.firstOrNull { it.id == id }?.let { item ->
            item.localPath?.let(::File)?.delete()
            partFile(item).delete()
        }
        commit(_items.value.filterNot { it.id == id })
        AppLog.info("offline", "download_removed", "Offline download removed")
    }

    override fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        settings.putBoolean(WIFI_KEY, value)
        if (!value) {
            commit(
                _items.value.map {
                    if (it.status == DownloadStatus.WaitingForWifi) {
                        it.copy(status = DownloadStatus.Queued)
                    } else {
                        it
                    }
                },
            )
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
        update(snapshot.id) {
            it.copy(
                status = DownloadStatus.Downloading,
                downloadedBytes = existing,
                error = null,
                updatedAtEpochMs = now(),
            )
        }

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
            connection = (URL(snapshot.sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!append) {
                existing = 0L
                if (part.exists()) part.delete()
            }
            val remaining = connection.contentLengthLong.coerceAtLeast(0L)
            val total = if (remaining > 0L) existing + remaining else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    var lastPersisted = downloaded
                    while (true) {
                        val current = _items.value.firstOrNull { it.id == snapshot.id }
                        if (current?.status == DownloadStatus.Paused || current == null) return@withContext
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastPersisted >= 512 * 1024) {
                            lastPersisted = downloaded
                            update(snapshot.id) {
                                it.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    status = DownloadStatus.Downloading,
                                    updatedAtEpochMs = now(),
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            val target = completedFile(snapshot)
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { "无法保存离线文件" }
            update(snapshot.id) {
                it.copy(
                    status = DownloadStatus.Completed,
                    localPath = target.absolutePath,
                    downloadedBytes = target.length(),
                    totalBytes = target.length(),
                    error = null,
                    updatedAtEpochMs = now(),
                )
            }
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
            val currentStatus = _items.value.firstOrNull { it.id == snapshot.id }?.status
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
                if (it.status == DownloadStatus.Paused) {
                    it
                } else {
                    it.copy(
                        status = DownloadStatus.Failed,
                        downloadedBytes = part.takeIf(File::exists)?.length() ?: 0L,
                        error = error.message ?: "下载失败",
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

    private fun update(id: String, transform: (OfflineMedia) -> OfflineMedia) {
        commit(_items.value.map { if (it.id == id) transform(it) else it })
    }

    private fun commit(value: List<OfflineMedia>) {
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
