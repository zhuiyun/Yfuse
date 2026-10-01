package com.yfuse.core.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.yfuse.MainActivity
import com.yfuse.core.logging.AppLog
import com.yfuse.feature.player.PlaybackForegroundTransitionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Keeps this phone serving a television that reads its media through [DlnaMediaRelay].
 *
 * A relayed cast lasts as long as the film, usually with the player closed or the screen off. Without
 * a foreground service the process is frozen once it is cached, and without the wake and Wi-Fi locks
 * the CPU and radio sleep under the screen; either way the television runs dry a few seconds later.
 */
class CastRelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏转发", NotificationManager.IMPORTANCE_LOW).apply {
                description = "电视经本机读取视频时保持连接"
                setSound(null, null)
            },
        )
        // The platform deadline for a foreground start runs from startForegroundService().
        startForeground(NOTIFICATION_ID, notification())
        transitionGate.onForegroundStarted()
        holdLocks()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when {
            intent?.action == ACTION_STOP_CAST ->
                scope.launch {
                    runCatching { GlobalContext.get().get<CastManager>().stop() }
                }
            transitionGate.shouldStop -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> {
                // A later load names another device; restating the foreground also satisfies a
                // startForegroundService() that reached a service already running.
                startForeground(NOTIFICATION_ID, notification())
                holdLocks()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        transitionGate.onDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Each load renews the bound, so a lock left behind by a lost stop still runs out. */
    private fun holdLocks() {
        val wake =
            wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Yfuse:CastRelay")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        wake.acquire(LOCK_TIMEOUT_MS)
        val wifi =
            wifiLock ?: runCatching {
                @Suppress("DEPRECATION")
                applicationContext
                    .getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Yfuse:CastRelay")
                    .apply { setReferenceCounted(false) }
            }.getOrNull()?.also { wifiLock = it }
        if (wifi?.isHeld == false) wifi.acquire()
    }

    private fun notification(): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopCast =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CastRelayService::class.java).setAction(ACTION_STOP_CAST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val device = deviceName.ifBlank { "电视" }
        return Notification
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在投屏到「$device」")
            .setContentText("电视经本机读取视频，投屏期间请保持手机连接同一 Wi-Fi")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                Notification.Action
                    .Builder(null, "停止投屏", stopCast)
                    .build(),
            ).build()
    }

    companion object {
        private const val CHANNEL_ID = "yfuse_cast_relay"
        private const val NOTIFICATION_ID = 2412
        private const val ACTION_STOP_CAST = "com.yfuse.cast.STOP_RELAYED_CAST"

        /** A film rarely runs this long; a lock that outlives its cast still ends. */
        private const val LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
        private val transitionGate = PlaybackForegroundTransitionGate()

        @Volatile private var deviceName: String = ""

        /** Called for each relayed load, from the user's cast request while the app is in front. */
        fun start(
            context: Context,
            device: String,
        ) {
            deviceName = device
            transitionGate.prepareStart()
            runCatching {
                context.startForegroundService(Intent(context, CastRelayService::class.java))
            }.onFailure { error ->
                // Background-start refusal or no such service (the TV build declares none): the relay
                // still serves while the process runs, it just loses the protection described above.
                AppLog.warning("cast", "dlna_relay_service_unavailable", "Cast relay service did not start", error)
            }
        }

        fun stop(context: Context) {
            if (transitionGate.requestStop()) {
                runCatching { context.stopService(Intent(context, CastRelayService::class.java)) }
            }
        }
    }
}
