package com.yfuse.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yfuse.MainActivity
import com.yfuse.core.data.LibraryCache
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.offline.DownloadNotificationActions
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.shared.R
import org.koin.core.context.GlobalContext

/** How many 继续观看 titles the launcher lists above 搜索 and 下载. */
private const val RESUME_SHORTCUTS = 2

/** Launchers cut a short label off at about this many characters; a title is trimmed to fit. */
private const val SHORT_LABEL_MAX = 12

/**
 * 桌面快捷方式: holding the app icon offers the two titles last left part-way, then 搜索 and 下载.
 * Rebuilt in the background each time the app leaves the screen, from local progress and cached
 * titles only — the same sources, and the same intents, as the 继续观看 widget.
 */
fun scheduleShortcutUpdate(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "app-shortcuts",
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<AppShortcutsWorker>().build(),
    )
}

class AppShortcutsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return Result.success()
        val koin = GlobalContext.getOrNull() ?: return Result.success()
        val servers =
            koin
                .get<ServerRegistry>()
                .data.value.servers
        val progress = koin.get<PlaybackSyncStore>()
        val cache = koin.get<LibraryCache>()
        val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val resume =
            servers
                .flatMap { server ->
                    progress.statesForServer(server.id).filter { state ->
                        !state.played &&
                            state.positionMs > 0 &&
                            state.serverItemId != null
                    }
                }.sortedByDescending { it.lastPlayedAtEpochMs }
                .distinctBy { it.serverId to it.serverItemId }
                .mapNotNull { state ->
                    val serverId = state.serverId ?: return@mapNotNull null
                    val itemId = state.serverItemId ?: return@mapNotNull null
                    // Untitled progress (a title since removed from the cache) is left out rather than
                    // shown as a bare "继续播放".
                    val title =
                        cache
                            .read(serverId)
                            ?.let { it.resume + it.featured + it.rows.flatMap { row -> row.items } }
                            ?.firstOrNull { it.id == itemId }
                            ?.title
                            ?: return@mapNotNull null
                    Triple(state, serverId to itemId, title)
                }.take(RESUME_SHORTCUTS)
                .mapIndexed { rank, (state, ids, title) ->
                    ShortcutInfo
                        .Builder(context, "resume-$rank")
                        .setShortLabel(title.take(SHORT_LABEL_MAX))
                        .setLongLabel("继续播放 · $title")
                        .setIcon(icon)
                        .setIntent(
                            launch(context)
                                .putExtra("widget_action", "resume")
                                .putExtra("widget_server", ids.first)
                                .putExtra("widget_item", ids.second)
                                .putExtra("widget_position", state.positionMs),
                        ).setRank(rank)
                        .build()
                }
        val fixed =
            listOf(
                ShortcutInfo
                    .Builder(context, "search")
                    .setShortLabel("搜索")
                    .setLongLabel("搜索媒体库")
                    .setIcon(icon)
                    .setIntent(launch(context).putExtra("widget_action", "search"))
                    .setRank(RESUME_SHORTCUTS)
                    .build(),
                ShortcutInfo
                    .Builder(context, "downloads")
                    .setShortLabel("下载")
                    .setLongLabel("下载与离线")
                    .setIcon(icon)
                    .setIntent(launch(context).putExtra(DownloadNotificationActions.EXTRA_OPEN_DOWNLOADS, true))
                    .setRank(RESUME_SHORTCUTS + 1)
                    .build(),
            )
        runCatching {
            manager.setDynamicShortcuts((resume + fixed).take(manager.maxShortcutCountPerActivity))
        }
        return Result.success()
    }

    /** A launcher shortcut needs an action; the extras say where to go, as the widget's do. */
    private fun launch(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
