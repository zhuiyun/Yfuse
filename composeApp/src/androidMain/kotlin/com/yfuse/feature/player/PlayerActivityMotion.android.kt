package com.yfuse.feature.player

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.PlayerTransitionStyle
import com.yfuse.shared.R
import org.koin.core.context.GlobalContext
import kotlin.math.abs

/** How much the player window itself may move. */
private enum class PlayerWindowMotion {
    /** 画中画: the window is already somewhere else. */
    None,

    /** 减弱动态效果: a fade and nothing more. */
    Fade,

    Full,
}

private fun Activity.playerWindowMotion(): PlayerWindowMotion {
    if (isInPictureInPictureMode) return PlayerWindowMotion.None
    val reduced =
        runCatching {
            GlobalContext
                .get()
                .get<ThemePreferences>()
                .reduceMotion.value
        }.getOrDefault(false)
    val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return if (reduced || scale <= 0f) PlayerWindowMotion.Fade else PlayerWindowMotion.Full
}

/** Whether the window may move at all — and so whether a drawn transition can meet it. */
internal fun Activity.playerWindowMotionEnabled(): Boolean = playerWindowMotion() == PlayerWindowMotion.Full

/**
 * Whether the system plays window transitions and animators at their own length.
 *
 * At 0.5× or 2× it stretches the window's own fade while the drawn halves of a transition run on
 * the real clock, and the two would miss each other at the seam; a launch then takes the plain fade.
 */
internal fun Context.animationScalesAtNormalSpeed(): Boolean {
    val transition = Settings.Global.getFloat(contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
    val animator = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return abs(transition - 1f) < SCALE_TOLERANCE && abs(animator - 1f) < SCALE_TOLERANCE
}

private const val SCALE_TOLERANCE = 0.01f

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
            PlayerTransitionStyle.None -> R.anim.player_enter
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
 * rise-and-fade, and under 减弱动态效果 a short fade with no rise. The close override is set here
 * as well as in [finishPlayerWindowMotion]: Android reads it when a predictive back gesture
 * starts, before `finish()` can replace it.
 */
@Suppress("DEPRECATION")
internal fun Activity.configurePlayerWindowMotion(transition: PlayerTransitionStyle? = null) {
    val motion = playerWindowMotion()
    val enter =
        when (motion) {
            PlayerWindowMotion.None -> 0
            PlayerWindowMotion.Fade -> R.anim.player_enter_reduced
            PlayerWindowMotion.Full -> transition?.enterAnimation ?: R.anim.player_enter
        }
    val hold =
        when (motion) {
            PlayerWindowMotion.None -> 0
            PlayerWindowMotion.Fade -> R.anim.player_hold_reduced
            PlayerWindowMotion.Full -> if (transition != null) R.anim.player_hold_handoff else R.anim.player_hold_enter
        }
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, hold)
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            closeHoldAnimation(motion),
            closeExitAnimation(motion, transition),
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
    val motion = if (wasPictureInPicture) PlayerWindowMotion.None else playerWindowMotion()
    val hold = closeHoldAnimation(motion)
    val exit = closeExitAnimation(motion, transition)
    if (Build.VERSION.SDK_INT < 34) {
        overridePendingTransition(hold, exit)
    } else {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, hold, exit)
    }
}

/** The page's window while the player closes over it: held still for as long as the player leaves. */
private fun closeHoldAnimation(motion: PlayerWindowMotion): Int =
    when (motion) {
        PlayerWindowMotion.None -> 0
        PlayerWindowMotion.Fade -> R.anim.player_hold_reduced
        PlayerWindowMotion.Full -> R.anim.player_hold_exit
    }

private fun closeExitAnimation(
    motion: PlayerWindowMotion,
    transition: PlayerTransitionStyle?,
): Int =
    when (motion) {
        PlayerWindowMotion.None -> 0
        PlayerWindowMotion.Fade -> R.anim.player_exit_reduced
        PlayerWindowMotion.Full -> transition?.exitAnimation ?: R.anim.player_exit
    }
