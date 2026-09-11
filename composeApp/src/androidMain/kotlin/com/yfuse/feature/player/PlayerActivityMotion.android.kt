package com.yfuse.feature.player

import android.app.Activity
import android.os.Build
import android.provider.Settings
import com.yfuse.R
import com.yfuse.core.data.ThemePreferences
import org.koin.core.context.GlobalContext

private fun Activity.playerWindowMotionEnabled(): Boolean {
    val reduced =
        runCatching {
            GlobalContext
                .get()
                .get<ThemePreferences>()
                .reduceMotion.value
        }.getOrDefault(false)
    val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return !reduced && scale > 0f && !isInPictureInPictureMode
}

/** Window animations include SurfaceView layers; no bitmap capture or delayed player release. */
@Suppress("DEPRECATION")
internal fun Activity.configurePlayerWindowMotion(sharedArtwork: Boolean = false) {
    val moving = !sharedArtwork && playerWindowMotionEnabled()
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            if (moving) R.anim.player_enter else 0,
            if (moving) R.anim.player_hold_enter else 0,
        )
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0,
            if (moving) R.anim.player_exit else 0,
        )
    } else {
        overridePendingTransition(if (moving) R.anim.player_enter else 0, if (moving) R.anim.player_hold_enter else 0)
    }
}

@Suppress("DEPRECATION")
internal fun Activity.finishPlayerWindowMotion(
    wasPictureInPicture: Boolean,
    sharedArtwork: Boolean = false,
) {
    val moving = !sharedArtwork && !wasPictureInPicture && playerWindowMotionEnabled()
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(if (moving) R.anim.player_hold_exit else 0, if (moving) R.anim.player_exit else 0)
    } else {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0,
            if (moving) R.anim.player_exit else 0,
        )
    }
}
