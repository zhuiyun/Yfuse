package com.yfuse.tv.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DarkPalette
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
    val focusBorder = 3.dp

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
}

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
