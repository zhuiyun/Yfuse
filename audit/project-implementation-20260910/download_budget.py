from pathlib import Path
p=Path('composeApp/src/androidMain/kotlin/com/yfuse/core/offline/OfflineMedia.android.kt')
s=p.read_text(encoding='utf-8')
s=s.replace('    private val runLock = Mutex()','''    private val runLock = Mutex()
    private val budgetLock = Any()
    private val activeVideoBytes = mutableMapOf<String, Long>()

    private fun deferReason(item: OfflineMedia): Pair<String, Long>? {
        val currentPolicy = _policy.value
        val clock = java.util.Calendar.getInstance()
        val minute = clock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + clock.get(java.util.Calendar.MINUTE)
        val waitMinutes = currentPolicy.minutesUntilDownloadWindow(minute)
        if (waitMinutes > 0) return "等待允许下载的时段" to (now() + waitMinutes * 60_000L)
        if (item.automaticallyDownloaded && currentPolicy.autoDownloadChargingOnly &&
            !context.getSystemService(android.os.BatteryManager::class.java).isCharging) {
            return "自动追更等待充电" to (now() + 15 * 60_000L)
        }
        return null
    }

    private fun reserveVideoBytes(id: String, writtenBytes: Long, additionalBytes: Long) = synchronized(budgetLock) {
        val used = _items.value.fold(0L) { total, item ->
            val bytes = if (item.id == id) writtenBytes else
                maxOf(item.downloadedBytes, activeVideoBytes[item.id] ?: 0L)
            if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
        }
        if (!offlineBudgetAllows(_policy.value.storageBudgetBytes, used, additionalBytes)) {
            throw OfflineStorageException("已达到离线视频容量上限，请提高上限或删除不需要的下载")
        }
        activeVideoBytes[id] = writtenBytes + additionalBytes
    }
''')
s=s.replace('    override fun setMaxConcurrentDownloads(value: Int) =','''    override fun setDownloadBudget(bytes: Long, chargingOnly: Boolean, startMinute: Int, endMinute: Int) = command {
        persistPolicy(_policy.value.copy(
            storageBudgetBytes = bytes, autoDownloadChargingOnly = chargingOnly,
            windowStartMinute = startMinute, windowEndMinute = endMinute,
        ))
        _items.value.filter { it.error == "等待允许下载的时段" || it.error == "自动追更等待充电" }
            .forEach { item -> update(item.id) { it.copy(nextRetryAt = 0L, error = null) } }
        rebuildAutoDownloadSchedule()
        kick()
    }

    override fun setMaxConcurrentDownloads(value: Int) =''')
s=s.replace('next.map { pending -> async { download(pending) } }.awaitAll()', '''next.map { pending -> async {
                            val deferred = deferReason(pending)
                            if (deferred == null) download(pending) else update(pending.id) {
                                it.copy(status = DownloadStatus.Queued, error = deferred.first, nextRetryAt = deferred.second)
                            }
                        } }.awaitAll()''')
s=s.replace('        if (!activePolicy.autoDownloadEnabled) return','''        if (!activePolicy.autoDownloadEnabled) return
        if (activePolicy.autoDownloadChargingOnly &&
            !context.getSystemService(android.os.BatteryManager::class.java).isCharging) return
        val autoClock = java.util.Calendar.getInstance()
        if (activePolicy.minutesUntilDownloadWindow(
                autoClock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + autoClock.get(java.util.Calendar.MINUTE),
            ) > 0) return''')
s=s.replace('            var connection: HttpURLConnection? = null\n            try {\n                // A process', '''            var connection: HttpURLConnection? = null
            try {
                reserveVideoBytes(snapshot.id, existing, 0L)
                // A process''')
s=s.replace('                        var lastSpaceCheck = downloaded','''                        var lastSpaceCheck = downloaded
                        var lastPolicyCheck = 0L''')
s=s.replace('                            offlineStorageWrite { output.write(buffer, 0, read) }\n                            downloaded += read', '''                            if (now() - lastPolicyCheck >= 1_000L) {
                                lastPolicyCheck = now()
                                deferReason(snapshot)?.let { deferred ->
                                    update(snapshot.id) {
                                        it.copy(status = DownloadStatus.Queued, downloadedBytes = downloaded,
                                            error = deferred.first, nextRetryAt = deferred.second)
                                    }
                                    return@withContext
                                }
                            }
                            reserveVideoBytes(snapshot.id, downloaded, read.toLong())
                            offlineStorageWrite { output.write(buffer, 0, read) }
                            downloaded += read''')
s=s.replace('            } finally {\n                connection?.disconnect()\n            }\n        }\n\n    private fun kick()', '''            } finally {
                connection?.disconnect()
                synchronized(budgetLock) { activeVideoBytes.remove(snapshot.id) }
            }
        }

    private fun kick()''')
s=s.replace('internal fun offlineAutoSyncRequest(wifiOnly: Boolean): PeriodicWorkRequest =','internal fun offlineAutoSyncRequest(wifiOnly: Boolean, chargingOnly: Boolean = false): PeriodicWorkRequest =')
start=s.index('internal fun offlineAutoSyncRequest(')
end=s.index('\ninternal ',start+15)
part=s[start:end].replace('.setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)', '.setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)\n                .setRequiresCharging(chargingOnly)')
s=s[:start]+part+s[end:]
s=s.replace('offlineAutoSyncRequest(_policy.value.wifiOnly)','offlineAutoSyncRequest(_policy.value.wifiOnly, _policy.value.autoDownloadChargingOnly)')
p.write_text(s,encoding='utf-8')
