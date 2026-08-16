package com.yfuse.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the playback process in the foreground while media is actively
 * playing. PlayerActivity owns the MediaSession and replaces this minimal
 * notification with the live transport notification.
 */
class PlaybackKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                PlayerActivity.NOTIFICATION_CHANNEL,
                "后台播放",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // startForegroundService() starts a strict system deadline before onStartCommand. Enter
        // the foreground as soon as the service exists so main-thread work cannot make us miss it.
        // PlayerActivity normally posted the full MediaStyle notification first; reuse it instead
        // of replacing its transport controls with this service's minimal fallback notification.
        val existingNotification =
            runCatching {
                notificationManager.activeNotifications
                    .firstOrNull { it.id == PlayerActivity.NOTIFICATION_ID }
                    ?.notification
            }.getOrNull()
        startForeground(
            PlayerActivity.NOTIFICATION_ID,
            existingNotification ?: notification(),
        )
        transitionGate.onForegroundStarted()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (transitionGate.shouldStop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        transitionGate.onDestroyed()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val openPlayer =
            PendingIntent.getActivity(
                this,
                0,
                PlayerActivity.openIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder = Notification.Builder(this, PlayerActivity.NOTIFICATION_CHANNEL)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Yfuse 正在播放")
            .setContentText("点按返回播放器")
            .setContentIntent(openPlayer)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val transitionGate = PlaybackForegroundTransitionGate()

        /** Clears a stop requested while Android was still creating the service. */
        fun prepareStart() {
            transitionGate.prepareStart()
        }

        /**
         * Stops an established foreground service immediately. If Android has not delivered
         * [onCreate] yet, the service is allowed to enter the foreground first and then stops from
         * [onStartCommand], satisfying the platform deadline instead of crashing the process.
         */
        fun requestStop(context: android.content.Context) {
            if (transitionGate.requestStop()) {
                context.stopService(Intent(context, PlaybackKeepAliveService::class.java))
            }
        }
    }
}

/** Coordinates start/stop calls that can cross the service's asynchronous creation boundary. */
internal class PlaybackForegroundTransitionGate {
    private val foregroundStarted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    val shouldStop: Boolean
        get() = stopRequested.get()

    fun prepareStart() {
        stopRequested.set(false)
    }

    /** Returns true only when calling Context.stopService is already safe. */
    fun requestStop(): Boolean {
        stopRequested.set(true)
        return foregroundStarted.get()
    }

    fun onForegroundStarted() {
        foregroundStarted.set(true)
    }

    fun onDestroyed() {
        foregroundStarted.set(false)
    }
}
