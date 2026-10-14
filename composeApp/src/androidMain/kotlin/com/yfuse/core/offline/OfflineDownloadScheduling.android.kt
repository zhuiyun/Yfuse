package com.yfuse.core.offline

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

internal const val OFFLINE_WAKE_WORK_NAME = "yfuse.offline.download.wake.v1"
internal const val OFFLINE_AUTO_SYNC_WORK_NAME = "yfuse.offline.auto-sync.v1"

private const val AUTO_SYNC_INTERVAL_HOURS = 6L

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

internal fun offlineAutoSyncRequest(
    wifiOnly: Boolean,
    chargingOnly: Boolean = false,
): PeriodicWorkRequest =
    PeriodicWorkRequest
        .Builder(OfflineDownloadWorker::class.java, AUTO_SYNC_INTERVAL_HOURS, TimeUnit.HOURS)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(chargingOnly)
                .build(),
        ).addTag(OFFLINE_AUTO_SYNC_WORK_NAME)
        .build()
