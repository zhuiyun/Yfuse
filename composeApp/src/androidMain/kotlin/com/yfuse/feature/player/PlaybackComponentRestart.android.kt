package com.yfuse.feature.player

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.yfuse.core.logging.AppLog

/** Long enough for the closing player to hand its final progress report to the outbox. */
private const val PROGRESS_REPORT_GRACE_MS = 1_500L

/**
 * 重启播放组件. A retired player whose decoder never let go holds hardware this process cannot get
 * back, so the only cure is a new process. The player closes first, which sends its final progress
 * report as every exit does; the app then reopens in a fresh process and 继续观看 resumes from there.
 */
internal fun restartPlaybackComponents(activity: Activity) {
    val appContext = activity.applicationContext
    val relaunch =
        appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    AppLog.warning(
        category = "player.engine",
        event = "playback_components_restart",
        message = "The viewer restarted the app to recover a decoder that never released",
    )
    activity.finish()
    Handler(Looper.getMainLooper()).postDelayed(
        {
            relaunch?.let { intent -> runCatching { appContext.startActivity(intent) } }
            Runtime.getRuntime().exit(0)
        },
        PROGRESS_REPORT_GRACE_MS,
    )
}
