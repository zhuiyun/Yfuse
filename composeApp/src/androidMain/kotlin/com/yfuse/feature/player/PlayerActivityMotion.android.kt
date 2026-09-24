package com.yfuse.feature.player

import android.app.Activity
import android.os.Build
import android.provider.Settings
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.shared.R
import org.koin.core.context.GlobalContext

internal fun Activity.playerWindowMotionEnabled(): Boolean {
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
 * The window's own enter: a pure fade, delayed so that on time it ends exactly when the page has
 * reached the frame this window takes over from (see `HandoffTiming.windowDelay`). No movement —
 * any offset would pull the two halves apart at the seam.
 */
private val PlayerTransitionStyle.enterAnimation: Int
    get() =
        when (this) {
            PlayerTransitionStyle.Turn -> R.anim.player_enter_turn
            PlayerTransitionStyle.Curtain -> R.anim.player_enter_curtain
            PlayerTransitionStyle.Glass -> R.anim.player_enter_glass
            PlayerTransitionStyle.PushIn -> R.anim.player_enter_push
            PlayerTransitionStyle.Tide -> R.anim.player_enter_tide
            PlayerTransitionStyle.Defocus -> R.anim.player_enter_defocus
        }

private val PlayerTransitionStyle.exitAnimation: Int
    get() =
        when (this) {
            PlayerTransitionStyle.Turn -> R.anim.player_exit_fade_long
            PlayerTransitionStyle.Glass -> R.anim.player_exit_fade_medium
            else -> R.anim.player_exit_fade_short
        }

/**
 * Window animations include SurfaceView layers; no bitmap capture or delayed player release.
 *
 * With a [transition], the player's own window only fades — the picture's journey is drawn by
 * the two halves of the transition, on either side of that fade. Without one it keeps the plain
 * rise-and-fade. The close override is set here as well as in [finishPlayerWindowMotion]: Android
 * reads it when a predictive back gesture starts, before `finish()` can replace it.
 */
@Suppress("DEPRECATION")
internal fun Activity.configurePlayerWindowMotion(transition: PlayerTransitionStyle? = null) {
    val moving = playerWindowMotionEnabled()
    val enter =
        when {
            !moving -> 0
            transition != null -> transition.enterAnimation
            else -> R.anim.player_enter
        }
    val hold =
        when {
            !moving -> 0
            transition != null -> R.anim.player_hold_handoff
            else -> R.anim.player_hold_enter
        }
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, hold)
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            if (moving) R.anim.player_hold_exit else 0,
            if (moving) transition?.exitAnimation ?: R.anim.player_exit else 0,
        )
    } else {
        overridePendingTransition(enter, hold)
    }
}

/** [transition] is the set that drew the way out, if one did; its window then only fades. */
@Suppress("DEPRECATION")
internal fun Activity.finishPlayerWindowMotion(
    wasPictureInPicture: Boolean,
    transition: PlayerTransitionStyle? = null,
) {
    val moving = !wasPictureInPicture && playerWindowMotionEnabled()
    val hold = if (moving) R.anim.player_hold_exit else 0
    val exit =
        when {
            !moving -> 0
            transition != null -> transition.exitAnimation
            else -> R.anim.player_exit
        }
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(hold, exit)
    } else {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, hold, exit)
    }
}
