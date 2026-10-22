package com.yfuse.feature.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** A safety net only: every state change re-arms it, and a stopped player releases it at once. */
private const val BACKGROUND_AUDIO_WAKE_LOCK_MS = 4 * 60 * 60 * 1_000L
private const val BACKGROUND_AUDIO_LOCK_TAG = "Yfuse:background-audio"

/**
 * The partial wake lock and Wi-Fi lock 熄屏继续播放声音 needs while sound actually plays with the
 * screen off: without them the CPU naps between audio buffers and Wi-Fi drops into power save,
 * starving the stream. Held only while playing; a paused or visible player holds neither.
 */
internal class PlaybackBackgroundLocks(
    context: Context,
) {
    private val wakeLock =
        context.applicationContext
            .getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BACKGROUND_AUDIO_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    // WIFI_MODE_FULL_HIGH_PERF is what Media3's own WifiLockManager takes for streaming.
    @Suppress("DEPRECATION")
    private val wifiLock =
        context.applicationContext
            .getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, BACKGROUND_AUDIO_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    fun update(held: Boolean) {
        runCatching {
            if (held) {
                wakeLock?.acquire(BACKGROUND_AUDIO_WAKE_LOCK_MS)
            } else if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
        runCatching {
            if (held) {
                wifiLock?.acquire()
            } else if (wifiLock?.isHeld == true) {
                wifiLock.release()
            }
        }
    }

    fun release() = update(held = false)
}
