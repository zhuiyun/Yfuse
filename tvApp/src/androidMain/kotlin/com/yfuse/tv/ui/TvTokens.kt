package com.yfuse.tv.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Semantic
import com.yfuse.core.designsystem.resolveAccentColors

// ---------------------------------------------------------------- colour
//
// The television has one theme — dark — and these are that theme's tokens, not a second
// palette. They used to be six literals chosen by eye, a shade off the phone's in every
// case, with the error and warning inks written out again wherever a screen needed one.
//
// Surfaces are the *opaque* stand-ins ([Palette.reducedFill]): a ten-foot UI on a set-top
// GPU does not blur what is behind a card, so it takes the plates the phone uses when it
// is asked not to either.

internal val TvBackground: Color = DarkPalette.background
internal val TvSurface: Color = DarkPalette.reducedFill.card2
internal val TvSurfaceFocused: Color = DarkPalette.reducedFill.card3

/** Behind artwork that has not loaded, or never will. */
internal val TvPlaceholder: Color = DarkPalette.reducedFill.glass
internal val TvOnSurface: Color = DarkPalette.text
internal val TvOnSurfaceMuted: Color = DarkPalette.body

/** A resting surface's edge; focus and selection replace it — see [TvFocusMotion]. */
internal val TvHairline: Color = DarkPalette.border.copy(alpha = 0.08f)

/** The brand emphasis as the dark theme resolves it: 4.5:1 on a dark surface, dark ink on top. */
internal val TvAccent: Color = resolveAccentColors(Brand.Primary, dark = true).accent

/**
 * A selected card, row or chip at rest: a fifth of the accent over the plate, under a 2dp accent
 * edge. The edge alone, 1dp wide, could not be picked out from three metres.
 */
internal val TvSelectedPlate: Color = TvAccent.copy(alpha = 0.2f).compositeOver(TvSurface)

/** Failed outcomes and destructive actions. */
internal val TvDanger: Color = DarkPalette.error

/** Recoverable trouble — a scan that found nothing, a code that expired. */
internal val TvWarning: Color = Semantic.Warning

// ---------------------------------------------------------------- type

/**
 * The television's four type levels — the phone's 四级体系 at ten feet.
 *
 * There were twenty sizes here, 13sp to 42sp, chosen per call site. The roles are the ones
 * `AppTypography` names; the sizes keep roughly its ratios from a floor of 16sp, because
 * nothing smaller is legible from a sofa. Weight and ink still separate a row's title from
 * its subtitle, as they do on the phone.
 */
internal object TvType {
    /** 页面主标题、hero 片名. */
    val display = 36.sp

    /** 货架标题、弹窗标题、区块标题. */
    val section = 24.sp

    /** 行标题、按钮、需要被读到的一句话. */
    val body = 18.sp

    /** 元数据、副标题、徽标、状态 — the floor. */
    val caption = 16.sp

    /** Line height for running copy (简介) set at [caption]. */
    val readingLineHeight = 24.sp
}

// ---------------------------------------------------------------- focus

/**
 * How a surface answers focus: one clock drives the scale, the edge's width and colour, and
 * the plate underneath, so the four never arrive separately.
 *
 * Under 减少动态效果 the change is a cut and the scale is dropped altogether — a card jumping
 * a size larger in one frame is still motion. The white edge and the lifted plate carry focus
 * on their own.
 */
internal object TvFocusMotion {
    val restBorder = 1.dp
    val selectedBorder = 2.dp
    val focusBorder = 3.dp

    /** How far a focused poster or card grows; the more of the screen a surface takes, the less. */
    const val CARD_SCALE = 1.055f

    /** Action buttons. */
    const val BUTTON_SCALE = 1.035f

    /** Entries of the navigation rail. */
    const val NAVIGATION_SCALE = 1.025f

    /** Full-width settings rows. */
    const val ROW_SCALE = 1.015f

    /** Critically damped: a D-pad held down interrupts this constantly, and it must not ring. */
    fun <T> spec(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        }

    /** The scale a focused surface reaches, or none at all under reduced motion. */
    fun scale(
        requested: Float,
        reduceMotion: Boolean,
    ): Float = if (reduceMotion) 1f else requested

    // 焦点视差 — cards only, and neither under 静息 nor under 减少动态效果, which keep the lift
    // and the white edge above and nothing else.

    /** How far a card focus arrives on starts turned, the side focus came from set back. */
    const val PARALLAX_DEGREES = 8f

    /**
     * Where the turned card is seen from, times density — the phone's press tilt uses the same
     * distance: Compose's default is close enough that a turned poster smears instead of turning.
     */
    const val PARALLAX_CAMERA_DISTANCE = 20f

    /**
     * The card turning back to face the room. Under-damped: it passes rest once, by about an
     * eighth of the turn, and stops. A spring and not a duration because the next press can
     * land before it has settled, and it carries on from wherever it was.
     */
    fun <T> parallax(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = 260f)

    /** The light that crosses a card once as focus arrives on it, left to right. */
    const val SWEEP_MILLIS = 420

    /** The band's width, as a share of the card's. */
    const val SWEEP_BAND = 0.45f

    /** White at the band's centre line, fading to nothing at its edges. */
    const val SWEEP_ALPHA = 0.34f

    /** The band leans this far from upright, its top ahead of its foot. */
    const val SWEEP_LEAN_DEGREES = 12f

    /** Quick off the mark and long in the tail, so the light is seen to leave rather than stop. */
    val SweepEasing = CubicBezierEasing(0.3f, 0.6f, 0.4f, 1f)

    /** How soon after a D-pad press a card gaining focus still counts as that press arriving. */
    const val ARRIVAL_WINDOW_MILLIS = 300L
}

// ---------------------------------------------------------------- dialogs

/**
 * The dialog entrances a television offers: 柔和浮起 and 底部升起 move the panel as one piece.
 * The other styles fold, scan, slice or sample the page behind it, every frame of every dialog, on
 * a GPU that was chosen to decode video.
 */
internal val TvDialogAnimations: List<DialogAnimation> = listOf(DialogAnimation.Lift, DialogAnimation.Slide)

/** What this style plays as on the television: a style picked before the list above, as 柔和浮起. */
internal fun DialogAnimation.onTv(): DialogAnimation = if (this in TvDialogAnimations) this else DialogAnimation.Lift

// ---------------------------------------------------------------- waiting

/**
 * The loading dot's breath: its alpha falls to [DIM] and back once every [BREATH_MILLIS]. A still
 * dot could not say whether anything was still happening; this is the calm register's wait —
 * no travel, no spin — and it holds still under 减少动态效果.
 */
internal object TvLoadingMotion {
    const val BREATH_MILLIS = 1_200
    const val DIM = 0.35f
}

// ---------------------------------------------------------------- pages

/**
 * How one page gives way to the next — a pushed route, a tab, a settings sub-page. They all cut
 * before. The television takes the calm register: the arriving page fades in over
 * [Motion.STANDARD] from at most [travel] away, the leaving one fades out over [Motion.QUICK]
 * where it stands, and nothing scales or samples the page behind. Under 减少动态效果 it is a cut.
 */
internal object TvPageMotion {
    val travel = 8.dp

    /**
     * [travelPx] is signed: positive arrives from the end side (going deeper), negative from the
     * start side (coming back), and 0 moves nothing — pages of one level, like the tabs.
     */
    fun transform(
        reduceMotion: Boolean,
        travelPx: Int,
    ): ContentTransform {
        if (reduceMotion) return fadeIn(snap()) togetherWith fadeOut(snap())
        val fade = fadeIn(Motion.tween(Motion.STANDARD))
        val arrive =
            if (travelPx == 0) {
                fade
            } else {
                fade + slideInHorizontally(Motion.tween(Motion.STANDARD)) { travelPx }
            }
        return arrive togetherWith fadeOut(Motion.tween(Motion.QUICK))
    }
}
