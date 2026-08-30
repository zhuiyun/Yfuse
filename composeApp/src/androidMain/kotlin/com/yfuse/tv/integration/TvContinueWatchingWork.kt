package com.yfuse.tv.integration

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import com.yfuse.feature.player.SystemPlaybackProgressEvent
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

const val TV_CONTINUE_WATCHING_WORK_NAME = "yfuse.tv.continue_watching.publish.v1"
const val TV_CONTINUE_WATCHING_PERIODIC_WORK_NAME = "yfuse.tv.continue_watching.periodic.v1"
val TV_CONTINUE_WATCHING_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

fun tvContinueWatchingWorkRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest
        .Builder(TvContinueWatchingSyncWorker::class.java)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        ).addTag(TV_CONTINUE_WATCHING_WORK_NAME)
        .build()

private fun tvContinueWatchingPeriodicWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequest
        .Builder(TvContinueWatchingSyncWorker::class.java, 15, TimeUnit.MINUTES)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        ).addTag(TV_CONTINUE_WATCHING_PERIODIC_WORK_NAME)
        .build()

/** Public entry used by the TV Application at startup and by foreground profile/logout actions. */
fun scheduleTvContinueWatchingSync(context: Context) {
    val application = context.applicationContext
    if (!application.isAndroidTvDevice()) return
    val workManager = WorkManager.getInstance(application)
    workManager.enqueueUniqueWork(
        TV_CONTINUE_WATCHING_WORK_NAME,
        TV_CONTINUE_WATCHING_WORK_POLICY,
        tvContinueWatchingWorkRequest(),
    )
    // Repairs the very small KEEP race where a final progress event arrives as a worker exits.
    workManager.enqueueUniquePeriodicWork(
        TV_CONTINUE_WATCHING_PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        tvContinueWatchingPeriodicWorkRequest(),
    )
}

/**
 * TV lifecycle facade. Engage stays disabled until an enrolled build installs a real adapter;
 * Watch Next remains the honest on-device fallback.
 */
object TvContinueWatchingRuntime {
    @Volatile
    private var engageAdapter: EngageContinuationAdapter? = null

    fun installEngageAdapter(adapter: EngageContinuationAdapter?) {
        engageAdapter = adapter
    }

    fun refresh(context: Context) {
        scheduleTvContinueWatchingSync(context)
    }

    fun clearServerProfile(
        context: Context,
        server: SavedServer,
    ) {
        if (!context.isAndroidTvDevice()) return
        val scope = ContinueWatchingScope(server.kind.toTvProvider(), server.id, server.userId)
        if (SharedPreferencesContinueWatchingStore(context).clearScope(scope)) {
            scheduleTvContinueWatchingSync(context)
        }
    }

    fun clearAll(context: Context) {
        if (!context.isAndroidTvDevice()) return
        if (SharedPreferencesContinueWatchingStore(context).clearAll()) {
            scheduleTvContinueWatchingSync(context)
        }
    }

    /** Foreground-only user action; never call this from Application or WorkManager. */
    fun requestPreviewChannel(context: Context): Boolean =
        if (!context.isAndroidTvDevice()) {
            false
        } else {
            PreviewChannelPublisher(context).requestBrowsableFromForeground() == TvProviderWriteResult.Success
        }

    internal fun recordSystemProgress(
        context: Context,
        event: SystemPlaybackProgressEvent,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (!context.isAndroidTvDevice()) return
        val serverId = event.serverId?.takeIf(String::isNotBlank) ?: return
        val registry = runCatching { GlobalContext.get().get<ServerRegistry>() }.getOrNull() ?: return
        val server = registry.serverById(serverId) ?: return
        val episode =
            event.seriesName?.isNotBlank() == true ||
                event.seasonNumber != null ||
                event.episodeNumber != null
        val coordinate =
            if (episode) {
                listOfNotNull(
                    event.seasonNumber?.let { "S$it" },
                    event.episodeNumber?.let { "E$it" },
                ).joinToString(" ").takeIf(String::isNotBlank)
            } else {
                null
            }
        val episodeName =
            event.title.takeIf { episode && it.isNotBlank() && it != event.seriesName }
        val subtitle = listOfNotNull(coordinate, episodeName).joinToString(" · ").takeIf(String::isNotBlank)
        val entry =
            ContinueWatchingEntry(
                identity =
                    ContinueWatchingIdentity(
                        scope =
                            ContinueWatchingScope(
                                provider = server.kind.toTvProvider(),
                                serverId = server.id,
                                profileId = server.userId,
                            ),
                        itemId = event.itemId,
                    ),
                mediaType =
                    if (episode) {
                        ContinueWatchingMediaType.Episode
                    } else {
                        ContinueWatchingMediaType.Movie
                    },
                title = event.seriesName?.takeIf(String::isNotBlank) ?: event.title,
                subtitle = subtitle,
                seasonNumber = event.seasonNumber,
                episodeNumber = event.episodeNumber,
                positionMs = event.positionMs,
                durationMs = event.durationMs,
                lastEngagementEpochMs = nowEpochMs,
                posterArtUri = sanitizeTvArtworkUri(event.posterUrl),
            )
        val decision =
            ContinueWatchingPolicy().decide(
                ContinueWatchingObservation(
                    entry = entry,
                    explicitlyCompleted = event.trigger == PlaybackSyncTrigger.Completed,
                    startedNewGeneration = event.trigger == PlaybackSyncTrigger.Started,
                ),
            )
        if (SharedPreferencesContinueWatchingStore(context).apply(decision)) {
            scheduleTvContinueWatchingSync(context)
        }
    }

    internal fun publisher(context: Context): ContinueWatchingPublisher =
        EngageThenWatchNextPublisher(
            engage = EngageContinueWatchingPublisher(adapter = engageAdapter),
            watchNext = AndroidTvProviderContinueWatchingPublisher(context),
        )
}

/** One bounded, idempotent snapshot replacement. No access token reaches WorkManager input data. */
class TvContinueWatchingSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        if (!applicationContext.isAndroidTvDevice()) return Result.success()
        val registry =
            runCatching { GlobalContext.get().get<ServerRegistry>() }.getOrElse { error ->
                AppLog.warning(
                    category = "tv.continue_watching",
                    event = "worker_dependency_unavailable",
                    message = "TV continuation sync is waiting for application dependencies",
                    throwable = error,
                )
                return Result.retry()
            }
        val store = SharedPreferencesContinueWatchingStore(applicationContext)
        store.retainScopes(registry.data.value.servers.mapTo(linkedSetOf()) { it.continueWatchingScope() })
        val pending = store.pendingPublication() ?: return Result.success()
        return when (val result = TvContinueWatchingRuntime.publisher(applicationContext).replace(pending.entries)) {
            is ContinueWatchingPublishResult.Published -> {
                store.markPublished(pending.revision)
                Result.success()
            }
            is ContinueWatchingPublishResult.Failed -> {
                if (result.retryable) Result.retry() else Result.success()
            }
            is ContinueWatchingPublishResult.Unavailable -> {
                if (result.terminal) Result.success() else Result.retry()
            }
        }
    }
}

private fun SavedServer.continueWatchingScope(): ContinueWatchingScope =
    ContinueWatchingScope(kind.toTvProvider(), id, userId)

internal fun Context.isAndroidTvDevice(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
