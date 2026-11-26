package com.yfuse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yfuse.appEntryIntent
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.shared.R
import org.koin.core.context.GlobalContext

class TodayAiringWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = scheduleTodayAiringWidgetUpdate(context)
}

fun scheduleTodayAiringWidgetUpdate(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    if (manager.getAppWidgetIds(ComponentName(context, TodayAiringWidget::class.java)).isEmpty()) return
    WorkManager.getInstance(context).enqueueUniqueWork(
        "today-airing-widget",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<TodayAiringWidgetWorker>().build(),
    )
}

class TodayAiringWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val koin = GlobalContext.getOrNull() ?: return Result.success()
        val servers = koin.get<ServerRegistry>().data.value.servers
        val followed =
            koin
                .get<CalendarFollowStore>()
                .followed
                .value
                .filter { entry -> entry.seriesItemId != null && servers.any { it.id == entry.serverId } }
                .take(3)

        val view = RemoteViews(context.packageName, R.layout.widget_today_airing)
        view.setTextViewText(R.id.widget_today_header, "今日追剧")
        view.setTextViewText(
            R.id.widget_today_count,
            if (followed.isEmpty()) "点击打开日历" else "已追 ${followed.size} 部",
        )

        fun openIntent(
            kind: String,
            serverId: String?,
            itemId: String?,
            requestCode: Int,
        ): PendingIntent {
            val intent =
                appEntryIntent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(
                        Uri
                            .Builder()
                            .scheme("yfuse-widget")
                            .authority(kind)
                            .appendPath(serverId.orEmpty())
                            .appendPath(itemId.orEmpty())
                            .build(),
                    ).putExtra("widget_action", kind)
                    .putExtra("widget_server", serverId)
                    .putExtra("widget_item", itemId)
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val rowIds = intArrayOf(R.id.widget_today_row_1, R.id.widget_today_row_2, R.id.widget_today_row_3)
        if (followed.isEmpty()) {
            view.setViewVisibility(R.id.widget_today_row_1, View.VISIBLE)
            view.setTextViewText(R.id.widget_today_row_1, "暂无追剧，点此打开追剧日历")
            view.setOnClickPendingIntent(R.id.widget_today_row_1, openIntent("open", null, null, 301))
            view.setViewVisibility(R.id.widget_today_row_2, View.GONE)
            view.setViewVisibility(R.id.widget_today_row_3, View.GONE)
        } else {
            rowIds.forEachIndexed { index, viewId ->
                val item = followed.getOrNull(index)
                if (item != null) {
                    view.setViewVisibility(viewId, View.VISIBLE)
                    view.setTextViewText(viewId, "· ${item.title}")
                    view.setOnClickPendingIntent(
                        viewId,
                        openIntent("detail", item.serverId, item.seriesItemId, 310 + index),
                    )
                } else {
                    view.setViewVisibility(viewId, View.GONE)
                }
            }
        }
        view.setOnClickPendingIntent(R.id.widget_today_root, openIntent("open", null, null, 300))

        val manager = AppWidgetManager.getInstance(context)
        manager.updateAppWidget(ComponentName(context, TodayAiringWidget::class.java), view)
        return Result.success()
    }
}
