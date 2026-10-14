package com.yfuse.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.runCatchingCancellable
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.DEFAULT_EMBY_USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

internal lateinit var offlineApplicationContext: Context

private const val MAX_KNOWN_AUTO_EPISODES = 2_000

internal class AndroidOfflineMediaManager(
    private val context: Context,
    private val settings: Settings,
    private val registry: ServerRegistry,
    private val repository: EmbyRepository,
    /** What the app calls itself to servers; downloads send it as playback does. */
    private val userAgent: () -> String = { DEFAULT_EMBY_USER_AGENT },
) : OfflineMediaManager {
    private companion object {
        const val INDEX_KEY = "offline.media.index.v1"
        const val WIFI_KEY = "offline.media.wifiOnly"
        const val AUTO_RULES_KEY = "offline.media.auto.rules.v1"
        const val SPACE_CHECK_INTERVAL_BYTES = 8L * 1024L * 1024L
        const val PROGRESS_UI_INTERVAL_BYTES = 512L * 1024L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val serializer = ListSerializer(OfflineMedia.serializer())
    private val autoRuleSerializer = ListSerializer(OfflineAutoDownloadRule.serializer())
    private val directory by lazy { File(context.filesDir, "offline-media").apply { mkdirs() } }
    private val artifacts by lazy { OfflineArtifactStore(context, directory) }
    private val indexStore = OfflineMediaIndexStore(context, json)
    private val indexLock = Any()
    private val _wifiOnly = MutableStateFlow(settings.getBoolean(WIFI_KEY, true))
    override val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    private val _policy = MutableStateFlow(loadOfflineDownloadPolicy(settings))
    override val policy: StateFlow<OfflineDownloadPolicy> = _policy.asStateFlow()
    private val autoRulesState = MutableStateFlow<List<OfflineAutoDownloadRule>>(emptyList())
    private val _autoDownloadRuleCount = MutableStateFlow(autoRulesState.value.size)
    override val autoDownloadRuleCount: StateFlow<Int> = _autoDownloadRuleCount.asStateFlow()
    private val _items = MutableStateFlow<List<OfflineMedia>>(emptyList())
    override val items: StateFlow<List<OfflineMedia>> = _items.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    override val operationError: StateFlow<String?> = _operationError.asStateFlow()
    private val _indexStatus = MutableStateFlow(OfflineIndexStatus.Loading)
    override val indexStatus: StateFlow<OfflineIndexStatus> = _indexStatus.asStateFlow()
    private val runLock = Mutex()
    private val budgetLock = Any()
    private val activeVideoBytes = mutableMapOf<String, Long>()

    private fun deferReason(item: OfflineMedia): Pair<String, Long>? {
        val currentPolicy = _policy.value
        val clock = java.util.Calendar.getInstance()
        val minute = clock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + clock.get(java.util.Calendar.MINUTE)
        val waitMinutes = currentPolicy.minutesUntilDownloadWindow(minute)
        if (waitMinutes > 0) return "等待允许下载的时段" to (now() + waitMinutes * 60_000L)
        if (item.automaticallyDownloaded &&
            currentPolicy.autoDownloadChargingOnly &&
            !context.getSystemService(android.os.BatteryManager::class.java).isCharging
        ) {
            return "自动追更等待充电" to (now() + 15 * 60_000L)
        }
        return null
    }

    private fun reserveVideoBytes(
        id: String,
        writtenBytes: Long,
        additionalBytes: Long,
    ) = synchronized(budgetLock) {
        val used =
            _items.value.fold(0L) { total, item ->
                val bytes =
                    if (item.id == id) {
                        writtenBytes
                    } else {
                        maxOf(item.downloadedBytes, activeVideoBytes[item.id] ?: 0L)
                    }
                if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
            }
        if (!offlineBudgetAllows(_policy.value.storageBudgetBytes, used, additionalBytes)) {
            throw OfflineStorageException("已达到离线视频容量上限，请提高上限或删除不需要的下载")
        }
        activeVideoBytes[id] = writtenBytes + additionalBytes
    }

    private val commands =
        OfflineCommandQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { error ->
            if (_indexStatus.value != OfflineIndexStatus.Ready) _indexStatus.value = OfflineIndexStatus.Failed
            _operationError.value =
                if (_indexStatus.value == OfflineIndexStatus.Failed) {
                    "下载记录未能读取，请检查存储后重新打开应用"
                } else if (error is OfflineCommandRejectedException) {
                    "下载操作过于频繁，请稍后重试"
                } else {
                    "下载操作未完成，请检查存储空间和目录权限后重试"
                }
            AppLog.error("offline", "command_failed", "Offline command could not be completed", error)
        }

    init {
        commands.submit(::initialize)
    }

    override fun clearOperationError() {
        _operationError.value = null
    }

    override fun retryIndex() {
        if (_indexStatus.value != OfflineIndexStatus.Failed) return
        _indexStatus.value = OfflineIndexStatus.Loading
        _operationError.value = null
        commands.submit(::initialize)
    }

    private fun command(action: () -> Unit) {
        commands.submit {
            check(_indexStatus.value == OfflineIndexStatus.Ready) { "下载索引未能读取，请检查存储后重新打开应用" }
            action()
        }
    }

    private fun commandEach(
        ids: List<String>,
        action: (String) -> Unit,
    ) {
        commands.submitBatch(
            ids,
            beforeBatch = {
                check(_indexStatus.value == OfflineIndexStatus.Ready) { "下载索引未能读取，请检查存储后重新打开应用" }
            },
            action = action,
        )
    }

    internal suspend fun applyNotificationAction(action: String?) {
        commands.execute {
            check(_indexStatus.value == OfflineIndexStatus.Ready)
            when (action) {
                DownloadNotificationActions.ACTION_PAUSE,
                DownloadNotificationActions.ACTION_STOP,
                -> pauseAllNow()
                DownloadNotificationActions.ACTION_RESUME -> resumeAllNow()
                else -> error("Unknown download notification action")
            }
        }
    }

    private suspend fun awaitCommands() {
        commands.awaitPending()
        check(_indexStatus.value == OfflineIndexStatus.Ready) { "下载索引未能读取，请检查存储后重新打开应用" }
    }

    private fun initialize() {
        autoRulesState.value = loadAutoRules()
        _autoDownloadRuleCount.value = autoRulesState.value.size
        val recovered =
            recoverOfflineIndex(
                load = ::loadIndex,
                cleanup = ::cleanupOrphanedArtifacts,
                persist = indexStore::sync,
                recover = { storedItems ->
                    ensureOfflineAddedOrder(storedItems).map { stored ->
                        // v1 persisted authenticated source/poster URLs. Extract the non-secret source
                        // selection once, then erase both URLs before the index is written again.
                        val item = sanitizeLegacyOfflineItem(stored)
                        when (item.status) {
                            DownloadStatus.Downloading -> item.copy(status = DownloadStatus.Queued)
                            DownloadStatus.Completed ->
                                if (item.localPath?.let(::offlinePathExists) == true) {
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
                },
            )
        _items.value = recovered.sortedWith(offlineAddedComparator)
        _indexStatus.value = OfflineIndexStatus.Ready
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
        rebuildAutoDownloadSchedule()
    }

    override fun enqueue(request: OfflineDownloadRequest) = enqueueAll(listOf(request))

    override fun enqueueAll(requests: List<OfflineDownloadRequest>) {
        if (requests.isEmpty()) return
        val snapshot = requests.toList()
        command { enqueueBatch(snapshot) }
        refreshShownAttention()
    }

    private fun enqueueBatch(requests: List<OfflineDownloadRequest>) {
        val unique = requests.associateBy { "${it.serverId}#${it.itemId}" }.values
        var pending = false
        synchronized(indexLock) {
            val batch = planOfflineEnqueueBatch(_items.value, unique.toList(), _policy.value.storageTreeUri, now())
            pending = batch.changed.any { !it.item.playable }
            // Publish every replacement revision durably before cleaning any old artifact.
            commitLocked(batch.items)
            batch.changed.forEach { change ->
                val old = change.previous ?: return@forEach
                if (change.sourceChanged) {
                    // Commit the new revision first so the active loop sees that it was superseded.
                    // Keep cleanup under the same lock so a replacement cannot claim its files before
                    // the old generation's deterministic artifacts have been removed.
                    old.localPath?.let(::deleteOfflinePath)
                    old.subtitlePath?.let(::deleteOfflinePath)
                    offlineVideoTarget(context, directory, old).let { target ->
                        target.deletePartial()
                        target.published()?.path?.let(::deleteOfflinePath)
                    }
                    artifacts.completedFile(old).delete()
                    artifacts.legacyCompletedFile(old.id).delete()
                    artifacts.subtitleFile(old.id).delete()
                    artifacts.deleteSubtitlePartFiles(old.id)
                } else if (!change.item.playable) {
                    // Re-queuing the same variant intentionally preserves its verified range and
                    // partial video. Only subtitle staging is revision-bound and cannot be resumed.
                    artifacts.deleteSubtitlePartFiles(old.id)
                }
            }
        }
        registerAutoDownloadRules(unique.filter { it.autoDownloadNewEpisodes })
        AppLog.info(
            category = "offline",
            event = "download_enqueued",
            message = "Offline download batch enqueued",
            attributes = mapOf("itemCount" to unique.size.toString()),
        )
        if (pending) kick()
    }

    override fun pause(id: String) = pauseMany(listOf(id))

    override fun pauseMany(ids: List<String>) {
        commandEach(ids, ::pauseNow)
        refreshShownAttention()
    }

    private fun pauseNow(id: String) {
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
        command(::pauseAllNow)
        refreshShownAttention()
    }

    private fun pauseAllNow() {
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

    override fun resume(id: String) = resumeMany(listOf(id))

    override fun resumeMany(ids: List<String>) {
        commandEach(ids, ::resumeNow)
        refreshShownAttention()
    }

    private fun resumeNow(id: String) {
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
        command(::resumeAllNow)
        refreshShownAttention()
    }

    private fun resumeAllNow() {
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

    override fun remove(id: String) = removeMany(listOf(id))

    override fun removeMany(ids: List<String>) {
        commandEach(ids, ::removeNow)
        refreshShownAttention()
    }

    private fun removeNow(id: String) {
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
                if (artifacts.delete(deleting)) {
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
            artifacts.subtitleFile(id).delete()
            artifacts.deleteSubtitlePartFiles(id)
        }
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
        AppLog.info("offline", "download_removed", "Offline download removed")
    }

    override fun setWifiOnly(value: Boolean) = command { setWifiOnlyNow(value) }

    private fun setWifiOnlyNow(value: Boolean) {
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
        rebuildAutoDownloadSchedule()
    }

    override fun setDownloadBudget(
        bytes: Long,
        chargingOnly: Boolean,
        startMinute: Int,
        endMinute: Int,
    ) = command {
        persistPolicy(
            _policy.value.copy(
                storageBudgetBytes = bytes,
                autoDownloadChargingOnly = chargingOnly,
                windowStartMinute = startMinute,
                windowEndMinute = endMinute,
            ),
        )
        _items.value
            .filter { it.error == "等待允许下载的时段" || it.error == "自动追更等待充电" }
            .forEach { item -> update(item.id) { it.copy(nextRetryAt = 0L, error = null) } }
        rebuildAutoDownloadSchedule()
        kick()
    }

    override fun setMaxConcurrentDownloads(value: Int) =
        command {
            persistPolicy(_policy.value.copy(maxConcurrentDownloads = value).normalized())
            kick()
        }

    override fun setAutoDeleteWatched(value: Boolean) =
        command {
            persistPolicy(_policy.value.copy(autoDeleteWatched = value))
        }

    override fun setAutoDownloadEnabled(value: Boolean) =
        command {
            persistPolicy(_policy.value.copy(autoDownloadEnabled = value))
            rebuildAutoDownloadSchedule()
        }

    override fun setAutoDownloadItemLimit(value: Int) =
        command {
            persistPolicy(_policy.value.copy(autoDownloadItemLimit = value).normalized())
            rebuildAutoDownloadSchedule()
        }

    override fun setStorageDirectory(
        treeUri: String?,
        label: String?,
    ) = command {
        persistPolicy(
            _policy.value.copy(
                storageTreeUri = treeUri,
                storageLabel = label,
            ),
        )
    }

    override fun clearAutoDownloadRules() =
        command {
            persistAutoRules(emptyList())
            rebuildAutoDownloadSchedule()
        }

    override fun onPlaybackCompleted(
        serverId: String,
        itemId: String,
    ) = command {
        if (!_policy.value.autoDeleteWatched) return@command
        _items.value
            .firstOrNull { it.serverId == serverId && it.itemId == itemId }
            ?.let {
                removeNow(it.id)
                refreshShownAttentionNow()
            }
    }

    internal suspend fun runPendingDownloads() =
        withContext(Dispatchers.IO) {
            runLock.withLock {
                while (true) {
                    awaitCommands()
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
                        next
                            .map { pending ->
                                async {
                                    val deferred = deferReason(pending)
                                    if (deferred == null) {
                                        download(pending)
                                    } else {
                                        update(pending.id) {
                                            it.copy(
                                                status = DownloadStatus.Queued,
                                                error = deferred.first,
                                                nextRetryAt = deferred.second,
                                            )
                                        }
                                    }
                                }
                            }.awaitAll()
                    }
                }
            }
        }

    internal suspend fun refreshAutoDownloads() {
        awaitCommands()
        val activePolicy = _policy.value
        if (!activePolicy.autoDownloadEnabled) return
        if (activePolicy.autoDownloadChargingOnly &&
            !context.getSystemService(android.os.BatteryManager::class.java).isCharging
        ) {
            return
        }
        val autoClock = java.util.Calendar.getInstance()
        if (activePolicy.minutesUntilDownloadWindow(
                autoClock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + autoClock.get(java.util.Calendar.MINUTE),
            ) > 0
        ) {
            return
        }
        autoRulesState.value.forEach { rule ->
            val server = registry.serverById(rule.serverId) ?: return@forEach
            val episodes =
                repository
                    .episodes(
                        server = server,
                        seriesId = rule.seriesId,
                        seasonId = rule.seasonId,
                        includeMediaSources = true,
                    ).getOrElse { error ->
                        AppLog.warning(
                            category = "offline",
                            event = "auto_download_refresh_failed",
                            message = "Automatic episode refresh failed",
                            throwable = error,
                            attributes = mapOf("seriesId" to rule.seriesId),
                        )
                        return@forEach
                    }
            commands.execute {
                if (_policy.value != activePolicy || autoRulesState.value.none { it == rule }) return@execute
                val ruleItems =
                    _items.value
                        .asSequence()
                        .filter {
                            it.serverId == rule.serverId &&
                                it.seriesId == rule.seriesId &&
                                it.seasonId == rule.seasonId &&
                                it.automaticallyDownloaded
                        }.toList()
                val plan =
                    planAutoDownload(
                        episodes = episodes,
                        knownEpisodeIds = rule.knownEpisodeIds,
                        ruleItems = ruleItems,
                        itemLimit = activePolicy.autoDownloadItemLimit,
                    )
                plan.remove.forEach { removeNow(it) }
                val requests =
                    plan.download.map { episode ->
                        val version = episode.versions.firstOrNull()
                        val subtitle =
                            matchOfflineSubtitleTrack(
                                tracks = version?.subtitleTracks.orEmpty(),
                                language = rule.subtitleLanguage,
                                codec = rule.subtitleCodec,
                                default = false,
                                forced = false,
                            )
                        OfflineDownloadRequest(
                            serverId = rule.serverId,
                            itemId = episode.id,
                            title =
                                listOfNotNull(
                                    episode.seasonNumber?.let { "S$it" },
                                    episode.indexNumber?.let { "E$it" },
                                    episode.name,
                                ).joinToString(" "),
                            mediaSourceId = version?.id,
                            quality = rule.quality,
                            subtitleStreamIndex = subtitle?.index,
                            subtitleCodec = subtitle?.codec,
                            subtitleLanguage = subtitle?.language,
                            estimatedBytes =
                                estimateOfflineBytes(
                                    sourceSizeBytes = version?.sizeBytes,
                                    sourceBitrateBps = version?.bitrateBps,
                                    runtimeMinutes = episode.runtimeMinutes,
                                    quality = rule.quality,
                                    includeSubtitle = subtitle != null,
                                ),
                            seriesId = rule.seriesId,
                            seasonId = episode.seasonId ?: rule.seasonId,
                            automaticallyDownloaded = true,
                        )
                    }
                commitOfflineAutoDiscovery(
                    enqueue = { if (requests.isNotEmpty()) enqueueBatch(requests) },
                    rememberEpisodes = {
                        // Only what this refresh fetched or found watched: a new episode left out for
                        // want of room stays new, and is fetched once a watched download makes room.
                        updateAutoRule(rule.id) { current ->
                            current.copy(
                                knownEpisodeIds =
                                    (current.knownEpisodeIds + plan.seen)
                                        .takeLastBounded(MAX_KNOWN_AUTO_EPISODES),
                                updatedAtEpochMs = now(),
                            )
                        }
                    },
                )
            }
        }
    }

    private suspend fun download(snapshot: OfflineMedia) =
        withContext(Dispatchers.IO) {
            val target = offlineVideoTarget(context, directory, snapshot)
            var finalizedVideo =
                snapshot.localPath?.let { offlineStoredVideo(context, it) } ?: target.published()
            var existing = if (finalizedVideo == null) target.partialSize() else 0L
            var expectedValidator = snapshot.resumeValidator?.takeIf { existing > 0L }
            if (existing > 0L && expectedValidator == null) {
                // Legacy partial files have no proof that the remote object is unchanged.
                target.resetPartial()
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
                        downloadedBytes = offlineTransferredVideoBytes(finalizedVideo) { existing },
                        totalBytes = finalizedVideo?.size ?: it.totalBytes,
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
                reserveVideoBytes(snapshot.id, offlineTransferredVideoBytes(finalizedVideo) { existing }, 0L)
                // A process may stop after the video was fsync'ed and renamed but before its
                // subtitle and Completed index entry were published. That video is verified
                // enough to reuse: continue with the sidecar phase instead of downloading it
                // from byte zero again.
                val recoveredVideo = finalizedVideo
                if (recoveredVideo != null) {
                    val subtitlePart = downloadSubtitlePart(snapshot)
                    if (!publishCompletedDownload(snapshot, recoveredVideo, subtitlePart)) {
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
                val sourceUrl =
                    if (server.kind == MediaServerKind.Plex) {
                        resolvePlexOfflineSourceUrl(snapshot, server, repository)
                    } else {
                        resolveOfflineSourceUrl(snapshot, registry)
                    }
                val source =
                    requireAllowedOfflineTransferUrl(
                        sourceUrl,
                        server.localCleartextConfirmed,
                    )
                var append: Boolean
                var responseValidator: String?
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentDownload(snapshot)) return@withContext
                    val resumeFrom = existing
                    val validatorToMatch = expectedValidator
                    connection =
                        openOfflineTransfer(source, userAgent()) {
                            if (resumeFrom > 0L) {
                                setRequestProperty("Range", "bytes=$resumeFrom-")
                                validatorToMatch?.let {
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
                            contentLength = connection.contentLengthLong,
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
                        target.resetPartial()
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
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw OfflineHttpException(code)
                    }
                    if (code == HttpURLConnection.HTTP_PARTIAL && existing == 0L) {
                        error("服务器返回了无请求的分段响应")
                    }
                    append = rangeCanAppend
                    if (!append && existing > 0L) {
                        // If-Range correctly returns 200 when the object changed.
                        existing = 0L
                        target.resetPartial()
                    }
                    break
                }
                val remaining = connection.contentLengthLong.coerceAtLeast(0L)
                val total =
                    offlineTransferTotalBytes(
                        append = append,
                        contentRange = connection.getHeaderField("Content-Range"),
                        contentLength = connection.contentLengthLong,
                    )
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
                    target = target,
                    requiredBytes = if (remaining > 0L) remaining else SPACE_CHECK_INTERVAL_BYTES,
                )
                connection.inputStream.use { input ->
                    val output = offlineStorageWrite { target.openPartial(append) }
                    try {
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = existing
                        var lastUiUpdate = downloaded
                        var lastCheckpoint = downloaded
                        var lastSpaceCheck = downloaded
                        var lastPolicyCheck = 0L
                        while (true) {
                            if (!isCurrentDownload(snapshot)) return@withContext
                            val read = readOfflineChunk(input, buffer)
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
                                ensureStorageAvailable(target = target, requiredBytes = nextWindow)
                                lastSpaceCheck = downloaded
                            }
                            if (now() - lastPolicyCheck >= 1_000L) {
                                lastPolicyCheck = now()
                                deferReason(snapshot)?.let { deferred ->
                                    update(snapshot.id) {
                                        it.copy(
                                            status = DownloadStatus.Queued,
                                            downloadedBytes = downloaded,
                                            error = deferred.first,
                                            nextRetryAt = deferred.second,
                                        )
                                    }
                                    return@withContext
                                }
                            }
                            reserveVideoBytes(snapshot.id, downloaded, read.toLong())
                            offlineStorageWrite { output.write(buffer, 0, read) }
                            downloaded += read
                            if (downloaded - lastUiUpdate >= PROGRESS_UI_INTERVAL_BYTES) {
                                lastUiUpdate = downloaded
                                val persistCheckpoint =
                                    shouldPersistOfflineProgress(lastCheckpoint, downloaded)
                                if (persistCheckpoint) lastCheckpoint = downloaded
                                update(snapshot.id, persist = persistCheckpoint) {
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
                requireCompleteOfflineTransfer(target.partialSize(), total)
                currentCoroutineContext().ensureActive()
                val storedVideo =
                    finalizeVideo(snapshot, target) { finalizedVideo = it } ?: return@withContext
                val subtitlePart = downloadSubtitlePart(snapshot)
                if (!publishCompletedDownload(snapshot, storedVideo, subtitlePart)) return@withContext
                AppLog.info(
                    category = "offline",
                    event = "download_completed",
                    message = "Offline media download completed",
                    attributes =
                        mapOf(
                            "itemId" to snapshot.itemId,
                            "bytes" to storedVideo.size.toString(),
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
                            downloadedBytes = offlineTransferredVideoBytes(finalizedVideo, target::partialSize),
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
                if (error is CancellationException) throw error
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
                            downloadedBytes = offlineTransferredVideoBytes(finalizedVideo, target::partialSize),
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
                synchronized(budgetLock) { activeVideoBytes.remove(snapshot.id) }
            }
        }

    private fun kick() {
        rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
    }

    /**
     * Queued behind a change the user made in the app. A paused or failed notification on screen
     * otherwise waits for the next download run to catch up — and deleting the download it was
     * about left it offering 继续 / 重试 for nothing. Once per selection, as Android drops a burst
     * of notification updates.
     */
    private fun refreshShownAttention() {
        commands.submit(::refreshShownAttentionNow)
    }

    private fun refreshShownAttentionNow() {
        runCatching { refreshShownOfflineAttentionNotification(context, _items.value) }
            .onFailure { error ->
                AppLog.warning(
                    category = "offline",
                    event = "attention_refresh_failed",
                    message = "Paused or failed download notification could not be refreshed",
                    throwable = error,
                )
            }
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
        target: OfflineVideoTarget,
        onPublished: (OfflineStoredVideo) -> Unit,
    ): OfflineStoredVideo? =
        synchronized(indexLock) {
            val current =
                _items.value
                    .firstOrNull { it.id == snapshot.id }
                    ?.takeIf {
                        it.downloadRevision == snapshot.downloadRevision &&
                            it.status == DownloadStatus.Downloading
                    }
                    ?: return@synchronized null
            finalizeOfflineVideo(target::publishPartial, onPublished) { stored ->
                // Durably remember the finalized video before the subtitle phase. On an interrupted
                // run this lets the next worker resume from the local video, not byte zero.
                commitLocked(
                    _items.value.map {
                        if (it.id == current.id) {
                            it.copy(
                                localPath = stored.path,
                                downloadedBytes = stored.size,
                                totalBytes = stored.size,
                                resumeValidator = null,
                                updatedAtEpochMs = now(),
                            )
                        } else {
                            it
                        }
                    },
                )
            }
        }

    private suspend fun downloadSubtitlePart(snapshot: OfflineMedia): File? =
        withContext(Dispatchers.IO) {
            val server =
                registry.serverById(snapshot.serverId)
                    ?: return@withContext null
            val sourceUrl =
                runCatchingCancellable {
                    if (server.kind == MediaServerKind.Plex) {
                        resolvePlexOfflineSubtitleUrl(snapshot, server, repository)
                    } else {
                        resolveOfflineSubtitleUrl(snapshot, registry)
                    }
                }.onFailure { error -> logSubtitleFailure(snapshot, error) }
                    .getOrNull()
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
            val part = artifacts.subtitlePartFile(snapshot)
            part.delete()
            var connection: HttpURLConnection? = null
            try {
                if (!isCurrentDownload(snapshot)) return@withContext null
                connection = openOfflineTransfer(source, userAgent())
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
        videoTarget: OfflineStoredVideo,
        subtitlePart: File?,
    ): Boolean {
        val completed =
            synchronized(indexLock) {
                val current = _items.value.firstOrNull { it.id == snapshot.id }
                publishOfflineCompletionLocked(
                    current = current,
                    snapshot = snapshot,
                    videoPath = videoTarget.path,
                    videoBytes = videoTarget.size,
                    subtitlePart = subtitlePart,
                    subtitleTarget = artifacts.subtitleFile(snapshot.id),
                    nowMs = now(),
                )
            } ?: return false
        val exported =
            snapshot.storageTreeUri?.let { treeUri ->
                val subtitleUri =
                    completed.subtitlePath
                        ?.let(::File)
                        ?.takeIf(File::isFile)
                        ?.let { subtitle ->
                            runCatching {
                                publishOfflineFileToTree(
                                    context = context,
                                    treeUri = treeUri,
                                    source = subtitle,
                                    displayName = artifacts.subtitleFile(snapshot.id).name,
                                    mimeType = "application/x-subrip",
                                )
                            }.onFailure { error -> logSubtitleFailure(snapshot, error) }
                                .getOrNull()
                        }
                completed.copy(
                    subtitlePath = subtitleUri ?: completed.subtitlePath,
                )
            } ?: completed
        val committed =
            synchronized(indexLock) {
                val current = _items.value.firstOrNull { it.id == snapshot.id }
                if (
                    current == null ||
                    current.downloadRevision != snapshot.downloadRevision ||
                    current.status != DownloadStatus.Downloading
                ) {
                    false
                } else {
                    commitLocked(_items.value.map { if (it.id == snapshot.id) exported else it })
                    true
                }
            }
        if (!committed) {
            deleteOfflinePath(videoTarget.path)
            exported.subtitlePath?.let(::deleteOfflinePath)
            completed.subtitlePath?.let(::File)?.delete()
            return false
        }
        if (exported.subtitlePath != completed.subtitlePath) {
            completed.subtitlePath
                ?.let(::File)
                ?.delete()
        }
        return committed
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
        persist: Boolean = true,
        transform: (OfflineMedia) -> OfflineMedia,
    ) {
        synchronized(indexLock) {
            commitLocked(_items.value.map { if (it.id == id) transform(it) else it }, persist)
        }
    }

    private fun commitLocked(
        value: List<OfflineMedia>,
        persist: Boolean = true,
    ) {
        val previous = _items.value
        val normalized = ensureOfflineAddedOrder(value)
        if (persist) indexStore.sync(previous, normalized)
        _items.value = normalized
    }

    private fun loadIndex(): List<OfflineMedia> =
        loadOfflineIndexOnce(
            load = indexStore::load,
            migrationComplete = indexStore::migrationComplete,
            readLegacy = {
                settings.getStringOrNull(INDEX_KEY)?.let { json.decodeFromString(serializer, it) }
            },
            migrateAtomically = indexStore::migrateLegacy,
            discardLegacy = { settings.remove(INDEX_KEY) },
        )

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

    private fun registerAutoDownloadRules(requests: List<OfflineDownloadRequest>) {
        if (requests.isEmpty()) return
        var rules = autoRulesState.value
        requests.forEach { request ->
            val seriesId = request.seriesId?.takeIf(String::isNotBlank) ?: return@forEach
            val id = "${request.serverId}#$seriesId#${request.seasonId.orEmpty()}"
            val existing = rules.firstOrNull { it.id == id }
            val rule =
                OfflineAutoDownloadRule(
                    id = id,
                    serverId = request.serverId,
                    seriesId = seriesId,
                    seasonId = request.seasonId,
                    quality = request.quality,
                    subtitleCodec = request.subtitleCodec,
                    subtitleLanguage = request.subtitleLanguage,
                    knownEpisodeIds =
                        (
                            existing?.knownEpisodeIds.orEmpty() +
                                request.knownEpisodeIds +
                                request.itemId
                        ).takeLastBounded(MAX_KNOWN_AUTO_EPISODES),
                    updatedAtEpochMs = now(),
                )
            rules = rules.filterNot { it.id == id } + rule
        }
        persistAutoRules(rules)
        rebuildAutoDownloadSchedule()
    }

    private fun loadAutoRules(): List<OfflineAutoDownloadRule> =
        settings
            .getStringOrNull(AUTO_RULES_KEY)
            ?.let { stored ->
                runCatching { json.decodeFromString(autoRuleSerializer, stored) }
                    .onFailure { error ->
                        AppLog.warning(
                            category = "offline",
                            event = "auto_download_rules_invalid",
                            message = "Stored automatic download rules could not be decoded",
                            throwable = error,
                        )
                    }.getOrNull()
            }.orEmpty()
            .distinctBy(OfflineAutoDownloadRule::id)

    private fun persistAutoRules(value: List<OfflineAutoDownloadRule>) {
        val normalized = value.distinctBy(OfflineAutoDownloadRule::id).takeLast(100)
        autoRulesState.value = normalized
        _autoDownloadRuleCount.value = normalized.size
        settings.putString(AUTO_RULES_KEY, json.encodeToString(autoRuleSerializer, normalized))
    }

    private fun updateAutoRule(
        id: String,
        transform: (OfflineAutoDownloadRule) -> OfflineAutoDownloadRule,
    ) {
        persistAutoRules(autoRulesState.value.map { if (it.id == id) transform(it) else it })
    }

    private fun rebuildAutoDownloadSchedule() {
        val workManager = WorkManager.getInstance(context)
        if (autoRulesState.value.isEmpty() || !_policy.value.autoDownloadEnabled) {
            workManager.cancelUniqueWork(OFFLINE_AUTO_SYNC_WORK_NAME)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            OFFLINE_AUTO_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            offlineAutoSyncRequest(_policy.value.wifiOnly, _policy.value.autoDownloadChargingOnly),
        )
    }

    private fun persistPolicy(value: OfflineDownloadPolicy) {
        val normalized = persistOfflineDownloadPolicy(settings, value)
        _policy.value = normalized
        _wifiOnly.value = normalized.wifiOnly
    }

    private fun ensureStorageAvailable(
        target: OfflineVideoTarget,
        requiredBytes: Long,
    ) {
        // File.usableSpace excludes bytes Android can reclaim from this app's cache and can
        // therefore reject a download even when StorageManager can allocate it safely.
        // A document provider may not expose its backing volume. In that case the provider's
        // write error is authoritative; importantly, internal storage is never used as a proxy.
        val usable = target.usableSpace() ?: return
        if (!hasSufficientOfflineStorage(usable, requiredBytes)) {
            val missingBytes = missingOfflineStorageBytes(usable, requiredBytes).coerceAtLeast(1L)
            val bytesPerMb = 1024L * 1024L
            val missingMb = ((missingBytes - 1L) / bytesPerMb) + 1L
            throw OfflineStorageException("存储空间不足，至少还需 $missingMb MB 可用空间")
        }
    }

    private fun now() = System.currentTimeMillis()
}

private fun Iterable<String>.takeLastBounded(limit: Int): Set<String> =
    toList().takeLast(limit.coerceAtLeast(1)).toCollection(linkedSetOf())
