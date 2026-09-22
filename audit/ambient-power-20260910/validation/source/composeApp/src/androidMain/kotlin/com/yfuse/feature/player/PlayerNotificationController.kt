package com.yfuse.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession

/** Builds and publishes Android playback notifications without leaking that concern into the activity. */
internal class PlayerNotificationController(
    private val activity: PlayerActivity,
    private val mediaSession: () -> MediaSession,
) {
    private val manager = activity.getSystemService(NotificationManager::class.java)

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
        manager.cancel(PlayerActivity.NOTIFICATION_ID)
    }

    fun update(
        state: PlaybackState,
        titles: List<String>,
    ) {
        val title = titles.getOrNull(state.currentIndex).orEmpty().ifBlank { "Yfuse" }
        val contentIntent =
            PendingIntent.getActivity(
                activity,
                0,
                PlayerActivity.openIntent(activity),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
