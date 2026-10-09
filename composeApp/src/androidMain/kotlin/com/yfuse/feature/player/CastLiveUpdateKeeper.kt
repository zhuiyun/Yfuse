package com.yfuse.feature.player

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.yfuse.appEntryIntent
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastState
import com.yfuse.core.model.PlaybackSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val ACTION_PAUSE = "com.yfuse.cast.LIVE_PAUSE"
private const val ACTION_RESUME = "com.yfuse.cast.LIVE_RESUME"
private const val ACTION_STOP = "com.yfuse.cast.LIVE_STOP"

/**
 * The cast's 实况通知, from the player while it is open and from [CastLiveUpdateKeeper] once it has
 * closed. Not [PlayerActivity.NOTIFICATION_ID] (2407): that id is the media notification and the
 * playback service's foreground notification, which the system takes down along with the service,
 * and a MediaStyle notification cannot be promoted to a live update — replacing it took the lock
 * screen's and quick settings' media card, and 上一集 / 下一集, away for as long as the cast ran.
 */
internal const val CAST_LIVE_NOTIFICATION_ID = 2409

/** What the player knew about the titles on the television when it closed. */
internal class CastLiveHandover(
    val titles: List<String>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val segments: List<PlaybackSegment>,
    val chapterStartsMs: List<Long>,
)

/**
 * The live update for [cast] once the player has gone: the title the receiver's queue is on now,
 * with the handed-over position and chapters only while that is still the title handed over.
 */
internal fun CastLiveHandover.contentFor(cast: CastState): CastLiveContent {
    val current = cast.currentQueueIndex.takeIf { cast.queueSize > 1 && it in titles.indices } ?: index
    val same = current == index
    return CastLiveContent(
        title = titles.getOrNull(current).orEmpty(),
        positionMs = if (same) positionMs else 0L,
        durationMs = if (same) durationMs else 0L,
        segments = if (same) segments else emptyList(),
        chapterStartsMs = if (same) chapterStartsMs else emptyList(),
    )
}

/**
 * A cast's 实况通知 once the player has closed and the television plays on.
 *
 * The player owns the live update while it is open and hands it over here as it goes. From then
 * on 暂停 / 继续 and 停止投屏 go straight to the app-wide [CastManager], a tap opens the app — whose
 * capsule shows the cast — and the update goes when the session ends, or when a player opens and
 * takes it back. Main thread only.
 */
internal object CastLiveUpdateKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var kept: Kept? = null

    private class Kept(
        val context: Context,
        val receiver: BroadcastReceiver,
        val following: Job,
    )

    @RequiresApi(36)
    fun adopt(
        context: Context,
        cast: CastManager,
        handover: CastLiveHandover,
    ) {
        release()
        val app = context.applicationContext
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        ACTION_PAUSE -> forward { cast.pause() }
                        ACTION_RESUME -> forward { cast.resume() }
                        ACTION_STOP -> forward { cast.stop() }
                    }
                }
            }
        // Registered with the app rather than declared: it is only wanted while this process holds a session.
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_PAUSE)
                addAction(ACTION_RESUME)
                addAction(ACTION_STOP)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val manager = app.getSystemService(NotificationManager::class.java)
        val following =
            scope.launch {
                cast.state
                    .map { it.liveUpdateKey() }
                    .distinctUntilChanged()
                    .collect {
                        val state = cast.state.value
                        if (!state.hasActiveSession) {
                            release()
                            return@collect
                        }
                        val intents =
                            CastLiveIntents(
                                open = openApp(app),
                                pause = broadcast(app, ACTION_PAUSE, 2418),
                                resume = broadcast(app, ACTION_RESUME, 2419),
                                stop = broadcast(app, ACTION_STOP, 2420),
                            )
                        runCatching {
                            manager?.notify(
                                CAST_LIVE_NOTIFICATION_ID,
                                castLiveUpdate(app, handover.contentFor(state), state, intents),
                            )
                        }
                    }
            }
        kept = Kept(app, receiver, following)
    }

    /** Takes the kept live update down, if there is one. */
    fun release() {
        val current = kept ?: return
        kept = null
        current.following.cancel()
        runCatching { current.context.unregisterReceiver(current.receiver) }
        current.context.getSystemService(NotificationManager::class.java)?.cancel(CAST_LIVE_NOTIFICATION_ID)
    }

    private fun forward(command: suspend () -> Boolean) {
        // Refused or failed, a command shows in the cast's state, which the update is drawn from.
        scope.launch { runCatching { command() } }
    }
}

/**
 * The app, through the entry that stays enabled whichever launcher icon is chosen: the player the
 * update came from has gone, and a launcher entry resolved at one redraw can be switched off before
 * the next.
 */
private fun openApp(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        2417,
        appEntryIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun broadcast(
    context: Context,
    action: String,
    requestCode: Int,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
