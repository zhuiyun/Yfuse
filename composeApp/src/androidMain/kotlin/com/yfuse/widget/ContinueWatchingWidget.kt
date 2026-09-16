package com.yfuse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yfuse.MainActivity
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.shared.R
import org.koin.core.context.GlobalContext

class ContinueWatchingWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = scheduleWidgetUpdate(context)
}

fun scheduleWidgetUpdate(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.getAppWidgetIds(ComponentName(context, ContinueWatchingWidget::class.java)).isEmpty()) return
    WorkManager.getInstance(context).enqueueUniqueWork(
        "continue-watching-widget",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<ContinueWatchingWidgetWorker>().build(),
    )
}

/** Uses local progress and cached titles only; refreshing the widget never pulls server progress. */
class ContinueWatchingWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val koin = GlobalContext.get()
        val servers =
            koin
                .get<ServerRegistry>()
                .data.value.servers
        val progress = koin.get<PlaybackSyncStore>()
        val cache = koin.get<LibraryCache>()
        val recent =
            servers
                .flatMap { server ->
                    progress.statesForServer(server.id).filter {
                        !it.played &&
                            it.positionMs > 0 &&
                            it.serverItemId != null
                    }
                }.maxByOrNull { it.lastPlayedAtEpochMs }
        val content = recent?.serverId?.let { cache.read(it) }
        val title =
            content
                ?.let { it.resume + it.featured + it.rows.flatMap { row -> row.items } }
                ?.firstOrNull { it.id == recent?.serverItemId }
                ?.title
        val follow =
            koin.get<CalendarFollowStore>().followed.value.firstOrNull { followed ->
                servers.any { it.id == followed.serverId } && followed.seriesItemId != null
            }
        val view = RemoteViews(context.packageName, R.layout.widget_continue_watching)
        view.setTextViewText(R.id.widget_resume, title ?: if (recent != null) "继续上次观看" else "打开媒体库")
        view.setTextViewText(R.id.widget_progress, recent?.let { "已观看 ${it.positionMs / 60_000} 分钟" } ?: "播放后会在这里显示进度")
        view.setTextViewText(R.id.widget_follow, follow?.let { "追剧 · ${it.title}" } ?: "打开 Yfuse")

        fun destination(
            kind: String,
            serverId: String?,
            itemId: String?,
            positionMs: Long = 0L,
        ): PendingIntent {
            val intent =
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(
                        Uri
                            .Builder()
                            .scheme(
                                "yfuse-widget",
                            ).authority(kind)
                            .appendPath(serverId.orEmpty())
                            .appendPath(itemId.orEmpty())
                            .build(),
                    ).putExtra("widget_action", kind)
                    .putExtra("widget_server", serverId)
                    .putExtra("widget_item", itemId)
                    .putExtra("widget_position", positionMs)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        view.setOnClickPendingIntent(
            R.id.widget_resume,
            destination(
                "resume",
                recent?.serverId,
                recent?.serverItemId,
                recent?.positionMs ?: 0L,
            ),
        )
        view.setOnClickPendingIntent(R.id.widget_follow, destination("follow", follow?.serverId, follow?.seriesItemId))
        AppWidgetManager
            .getInstance(
                context,
            ).updateAppWidget(ComponentName(context, ContinueWatchingWidget::class.java), view)
        return Result.success()
    }
}
