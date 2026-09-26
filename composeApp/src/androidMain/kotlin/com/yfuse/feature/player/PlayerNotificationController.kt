package com.yfuse.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.castLiveClock
import com.yfuse.core.cast.castLiveProgress
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.notification.LiveUpdateColors
import com.yfuse.core.notification.requestPromotedOngoing
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

private const val ACTION_STOP_CAST = "com.yfuse.player.STOP_CAST"

/**
 * How far a cast has to move before its live update is redrawn. The time left runs on the
 * notification's own countdown in between, so the bar only needs to keep roughly up.
 */
private const val CAST_LIVE_REFRESH_MS = 30_000L

/** Builds and publishes Android playback notifications without leaking that concern into the activity. */
internal class PlayerNotificationController(
    private val activity: PlayerActivity,
    private val mediaSession: () -> MediaSession,
) {
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val castManager: CastManager? by lazy { GlobalContext.get().getOrNull<CastManager>() }
    private var lastState = PlaybackState()
    private var lastTitles: List<String> = emptyList()
    private var lastSegments: List<PlaybackSegment> = emptyList()
    private var castRefresh: Job? = null
    private var stopReceiverRegistered = false
    private val stopCastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == ACTION_STOP_CAST) stopCasting()
            }
        }

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                PlayerActivity.NOTIFICATION_CHANNEL,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "正在播放的影视与控制按钮"
                setSound(null, null)
            },
        )
    }

    fun cancel() {
        castRefresh?.cancel()
        castRefresh = null
        if (stopReceiverRegistered) {
            runCatching { activity.unregisterReceiver(stopCastReceiver) }
            stopReceiverRegistered = false
        }
        manager.cancel(PlayerActivity.NOTIFICATION_ID)
    }

    /**
     * @param segments the current title's 片头 / 片尾 markers, drawn as chapter points while casting.
     */
    fun update(
        state: PlaybackState,
        titles: List<String>,
        segments: List<PlaybackSegment> = emptyList(),
    ) {
        lastState = state
        lastTitles = titles
        lastSegments = segments
        if (Build.VERSION.SDK_INT >= 36) {
            val cast = castManager?.state?.value
            if (cast != null && cast.hasActiveSession) {
                registerStopReceiver()
                val live = castLiveUpdate(state, titles, segments, cast)
                runCatching { manager.notify(PlayerActivity.NOTIFICATION_ID, live) }
                followCast()
                return
            }
            castRefresh?.cancel()
            castRefresh = null
        }
        val title = titles.getOrNull(state.currentIndex).orEmpty().ifBlank { "Yfuse" }
        val contentIntent = openPlayerIntent()
        val previousIntent = mediaPendingIntent(PlayerActivity.ACTION_PREVIOUS, 1)
        val playPauseIntent = mediaPendingIntent(PlayerActivity.ACTION_PLAY_PAUSE, 2)
        val nextIntent = mediaPendingIntent(PlayerActivity.ACTION_NEXT, 3)
        val playPauseIcon =
            if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (state.playing) "暂停" else "播放"

        val notification =
            Notification
                .Builder(activity, PlayerActivity.NOTIFICATION_CHANNEL)
                .setSmallIcon(playPauseIcon)
                .setContentTitle(title)
                .setContentText(
                    when {
                        state.error != null -> "播放失败，可返回播放器重试"
                        state.ended -> "播放完成"
                        state.buffering -> "正在缓冲"
                        state.playing -> "正在播放"
                        else -> "已暂停"
                    },
                ).setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(state.playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(
                    Notification.Action
                        .Builder(
                            Icon.createWithResource(activity, android.R.drawable.ic_media_previous),
                            "上一集",
                            previousIntent,
                        ).build(),
                ).addAction(
                    Notification.Action
                        .Builder(
                            Icon.createWithResource(activity, playPauseIcon),
                            playPauseLabel,
                            playPauseIntent,
                        ).build(),
                ).addAction(
                    Notification.Action
                        .Builder(
                            Icon.createWithResource(activity, android.R.drawable.ic_media_next),
                            "下一集",
                            nextIntent,
                        ).build(),
                ).setStyle(
                    Notification
                        .MediaStyle()
                        .setMediaSession(mediaSession().sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                ).build()

        runCatching { manager.notify(PlayerActivity.NOTIFICATION_ID, notification) }
    }

    /**
     * 实况通知 while casting: the title's bar with its chapter points, the time left counting down in
     * the status bar chip, and 暂停 / 停止投屏. It takes the transport notification's place rather
     * than sitting beside it: the phone plays nothing while the television does.
     */
    @RequiresApi(36)
    private fun castLiveUpdate(
        state: PlaybackState,
        titles: List<String>,
        segments: List<PlaybackSegment>,
        cast: CastState,
    ): Notification {
        val title = titles.getOrNull(state.currentIndex).orEmpty().ifBlank { "Yfuse" }
        val device = cast.activeDevice?.name
        val playing = cast.status == CastPlaybackStatus.Playing
        val waiting = cast.status == CastPlaybackStatus.Buffering || cast.status == CastPlaybackStatus.Connecting
        val positionMs = if (cast.positionConfirmed) cast.positionMs else state.positionMs
        val durationMs = cast.durationMs.takeIf { it > 0L } ?: state.durationMs
        val progress = castLiveProgress(positionMs, durationMs, segments)
        val style = Notification.ProgressStyle()
        if (progress == null) {
            style.setProgressIndeterminate(true)
        } else {
            style
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(progress.max)))
                .setProgressPoints(
                    progress.points.map { Notification.ProgressStyle.Point(it).setColor(LiveUpdateColors.CHAPTER) },
                ).setProgress(progress.progress)
        }
        val remaining = progress?.remainingMs
        val text =
            listOfNotNull(
                progress?.section,
                when {
                    waiting -> "正在缓冲"
                    !playing -> "已暂停"
                    else -> null
                },
                remaining?.let {
                    // Paused, the exact figure holds; playing, the countdown beside the title is exact
                    // and this line only has to be right to the minute until the next redraw.
                    if (playing) "剩余约 ${(it + 59_999L) / 60_000L} 分钟" else "剩余 ${castLiveClock(it)}"
                },
            ).joinToString(" · ")
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val builder =
            Notification
                .Builder(activity, PlayerActivity.NOTIFICATION_CHANNEL)
                .setSmallIcon(playPauseIcon)
                .setContentTitle(listOfNotNull(title, device).joinToString(" · "))
                .setContentText(text)
                .setContentIntent(openPlayerIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setStyle(style)
                .requestPromotedOngoing()
                .addAction(
                    Notification.Action
                        .Builder(
                            Icon.createWithResource(activity, playPauseIcon),
                            if (playing) "暂停" else "继续",
                            mediaPendingIntent(PlayerActivity.ACTION_PLAY_PAUSE, 2),
                        ).build(),
                ).addAction(
                    Notification.Action
                        .Builder(
                            Icon.createWithResource(activity, android.R.drawable.ic_menu_close_clear_cancel),
                            "停止投屏",
                            mediaPendingIntent(ACTION_STOP_CAST, 4),
                        ).build(),
                )
        if (playing && remaining != null) {
            // The chip and the header count down to the end by themselves between redraws.
            builder
                .setWhen(System.currentTimeMillis() + remaining)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder
                .setShowWhen(false)
                .setShortCriticalText(if (waiting) "缓冲中" else "已暂停")
        }
        return builder.build()
    }

    /** Keeps the live update moving between the player's own presentation changes. */
    private fun followCast() {
        if (castRefresh?.isActive == true) return
        val cast = castManager ?: return
        castRefresh =
            activity.lifecycleScope.launch {
                cast.state
                    .map { state ->
                        listOf(
                            state.hasActiveSession,
                            state.status,
                            state.positionMs / CAST_LIVE_REFRESH_MS,
                            state.durationMs,
                            state.activeDevice?.name,
                        )
                    }.distinctUntilChanged()
                    .drop(1)
                    .collect { update(lastState, lastTitles, lastSegments) }
            }
    }

    private fun registerStopReceiver() {
        if (stopReceiverRegistered) return
        ContextCompat.registerReceiver(
            activity,
            stopCastReceiver,
            IntentFilter(ACTION_STOP_CAST),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        stopReceiverRegistered = true
    }

    /**
     * 停止投屏 from the notification: the television stops and the phone is left paused where it
     * was, as the player's own 停止投屏 does — without playing on, which from a notification would
     * start sound nobody asked for.
     */
    private fun stopCasting() {
        val cast = castManager ?: return
        activity.lifecycleScope.launch {
            val state = cast.state.value
            val handback = state.positionMs.takeIf { state.positionConfirmed }
            if (cast.stop() && handback != null) {
                runCatching { mediaSession().controller.transportControls.seekTo(handback) }
            }
        }
    }

    private fun openPlayerIntent(): PendingIntent =
        PendingIntent.getActivity(
            activity,
            0,
            PlayerActivity.openIntent(activity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun mediaPendingIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
