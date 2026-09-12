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

/**
 * Window animations include SurfaceView layers; no bitmap capture or delayed player release.
 *
 * [sharedArtwork] only silences the arrival: the poster morph is the arrival. The departure
 * override stays the ordinary fade, because Android reads it when a predictive back gesture
 * starts, before [finishPlayerWindowMotion] can replace it. Left at zero for a shared-artwork
 * launch, a system back cut the window away with no motion at all. When the reverse morph is
 * the one closing the window, `finish()` clears the fade itself.
 */
@Suppress("DEPRECATION")
internal fun Activity.configurePlayerWindowMotion(sharedArtwork: Boolean = false) {
    val moving = playerWindowMotionEnabled()
    val opening = moving && !sharedArtwork
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            if (opening) R.anim.player_enter else 0,
            if (opening) R.anim.player_hold_enter else 0,
        )
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0,
            if (moving) R.anim.player_exit else 0,
        )
    } else {
        overridePendingTransition(if (opening) R.anim.player_enter else 0, if (opening) R.anim.player_hold_enter else 0)
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
