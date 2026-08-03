package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 设计说明文档 §8.4 — 圆角三档，不允许中间值.
 *
 * The names say where a radius is used, but all of them resolve to one of the three
 * steps; adding a fourth value is a spec violation, not a tweak.
 */
object GlassShapes {
    /** 小 10px — 缩略图、内嵌小块. */
    val thumb = RoundedCornerShape(Dimens.small)

    /** 中 16px — 海报、按钮、胶囊、菜单. */
    val poster = RoundedCornerShape(Dimens.medium)
    val chip = RoundedCornerShape(Dimens.medium)
    val card = RoundedCornerShape(Dimens.medium)
    val menu = RoundedCornerShape(Dimens.medium)

    /** 大 26px — sheet、迷你播放器、tab bar. */
    val sheet = RoundedCornerShape(Dimens.large)
    val hero = RoundedCornerShape(Dimens.large)
    val tabBar = RoundedCornerShape(Dimens.large)

    val circle = CircleShape
}

/**
 * Primary liquid-glass surface. A translucent diagonal sheen, single-colour edge and
 * ambient tint preserve depth without a platform-specific blur dependency.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = LocalPalette.current.border,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    val resolvedFill = if (accessibility.reduceTransparency) {
        if (palette.isDark) Color(0xFF182235) else Color.White
    } else {
        fill
    }
    val sheen = if (palette.isDark) {
        Color.White.copy(alpha = 0.13f)
    } else {
        Color.White.copy(alpha = 0.58f)
    }
    val surface = if (accessibility.reduceTransparency) {
        Brush.linearGradient(listOf(resolvedFill, resolvedFill))
    } else {
        cssLinearGradient(
            145f,
            0f to sheen,
            0.26f to resolvedFill.copy(alpha = (resolvedFill.alpha * 0.92f).coerceIn(0f, 1f)),
            0.72f to resolvedFill,
            1f to resolvedFill.copy(alpha = (resolvedFill.alpha * 0.78f).coerceIn(0f, 1f)),
        )
    }
    return this
        .clip(shape)
        .background(surface)
        .let { modifier ->
            if (border != null) modifier.border(Dimens.hairline, border, shape) else modifier
        }
}

/**
 * Liquid-glass surface without a directional colour ramp.
 *
 * Profile and form controls use this variant when hierarchy should come from
 * translucency, a single fill and the luminous edge.
 */
@Composable
fun Modifier.flatGlass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = LocalPalette.current.border,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    val resolvedFill = if (accessibility.reduceTransparency) {
        if (palette.isDark) Color(0xFF182235) else Color.White
    } else {
        fill
    }
    return this
        .clip(shape)
        .background(resolvedFill)
        .let { modifier ->
            if (border != null) modifier.border(Dimens.hairline, border, shape) else modifier
        }
}

/**
 * Liquid-glass surface with a single-colour edge.
 *
 * Use this for interactive controls and dense detail-page cards: the surface keeps
 * the directional sheen that communicates glass, while the outline remains a calm
 * solid colour instead of becoming a second gradient.
 */
@Composable
fun Modifier.solidGlass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = LocalPalette.current.border,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    val resolvedFill = if (accessibility.reduceTransparency) {
        if (palette.isDark) Color(0xFF182235) else Color.White
    } else {
        fill
    }
    val surface = if (accessibility.reduceTransparency) {
        Brush.linearGradient(listOf(resolvedFill, resolvedFill))
    } else {
        cssLinearGradient(
            145f,
            0f to Color.White.copy(alpha = if (palette.isDark) 0.16f else 0.72f),
            0.30f to resolvedFill.copy(alpha = (resolvedFill.alpha * 0.92f).coerceIn(0f, 1f)),
            0.72f to resolvedFill,
            1f to resolvedFill.copy(alpha = (resolvedFill.alpha * 0.80f).coerceIn(0f, 1f)),
        )
    }
    return this
        .clip(shape)
        .background(surface)
        .let { modifier ->
            if (border != null) modifier.border(Dimens.hairline, border, shape) else modifier
        }
}

/**
 * 液态玻璃 — the material for interactive controls.
 *
 * [glass] and [solidGlass] are *plates*: one translucent fill plus a diagonal sheen, which
 * is all a card needs. A button has to read as a body with thickness, and on the detail
 * page's content surface — flat white under the light theme — a white plate has no edge at
 * all, leaving the drop shadow to do the entire job. Three things are added here, drawn in
 * one pass so they stay in order:
 *
 * - a vertical body ramp that lightens towards the top and shades towards a depth tint at
 *   the bottom, which is the one depth cue that survives white glass on a white page;
 * - a 145° specular sweep, with a faint bounce on the far corner;
 * - a calm, single-colour [border] around the control.
 *
 * The edge is a solid stroke on the outline, drawn inside the clip so its outer half is cut
 * away and the remaining inner half lands exactly on [Dimens.hairline]. Borders stay flat
 * across the app even when the body and its reflected sheen use gradients.
 *
 * [sheen] scales the specular only, for controls small enough that a full-strength highlight
 * reads as a blown-out patch rather than a reflection.
 *
 * [over] is what lies behind the control, and defaults to the page. A translucent fill is
 * only half the colour that ends up on screen, so this is what decides whether the glass is
 * shaded as a pale body or a dense one; controls that float over artwork rather than over
 * the page have to say so.
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = GlassShapes.chip,
    fill: Color = LocalPalette.current.glassStrong,
    border: Color = LocalPalette.current.border,
    over: Color = if (LocalPalette.current.isDark) LocalPalette.current.background else Color.White,
    sheen: Float = 1f,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    if (accessibility.reduceTransparency) {
        val flat = if (palette.isDark) Color(0xFF182235) else Color.White
        return this.clip(shape).background(flat).border(Dimens.hairline, border, shape)
    }
    // The theme is the wrong signal here — the play key is pale glass under both, and 返回
    // is dense glass over artwork on the light one. What the fill composites to is the right
    // one, so that is what the ramps are keyed off.
    val pale = fill.compositeOver(over).luminance() > 0.42f
    val depth = if (pale) Color(0xFF8CA1C1) else Color(0xFF04070E)
    val body = Brush.verticalGradient(
        0f to lerp(fill, Color.White, if (pale) 0.38f else 0.16f)
            .copy(alpha = (fill.alpha * 1.18f).coerceAtMost(1f)),
        0.50f to fill,
        1f to lerp(fill, depth, if (pale) 0.16f else 0.30f)
            .copy(alpha = (fill.alpha * 0.96f).coerceAtMost(1f)),
    )
    val gloss = cssLinearGradient(
        145f,
        0f to Color.White.copy(alpha = (if (pale) 0.58f else 0.28f) * sheen),
        0.20f to Color.White.copy(alpha = (if (pale) 0.14f else 0.06f) * sheen),
        0.52f to Color.Transparent,
        1f to Color.White.copy(alpha = (if (pale) 0.20f else 0.10f) * sheen),
    )
    return this
        .clip(shape)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(Dimens.hairline.toPx() * 2f)
            onDrawBehind {
                drawOutline(outline, brush = body)
                drawOutline(outline, brush = gloss)
                drawOutline(outline, color = border, style = stroke)
            }
        }
}

/** 液态玻璃 lift — the shadow that separates a glass control from the page beneath it. */
object GlassLift {
    /** 主按钮 — wide keys that sit on the page itself. */
    val key = CssShadow(0.dp, 10.dp, 26.dp, 0.dp, Color(0xFF1C243A).copy(alpha = 0.20f))

    /** 圆形/胶囊小按钮 — enough to detach from a white surface, not enough to read as a card. */
    val control = CssShadow(0.dp, 4.dp, 12.dp, 0.dp, Color(0xFF1C243A).copy(alpha = 0.10f))
}

/**
 * Stronger liquid glass used above artwork and dense content.
 */
@Composable
fun Modifier.overlayGlass(
    shape: Shape = GlassShapes.sheet,
    fill: Color = LocalPalette.current.glass,
    border: Color? = LocalPalette.current.border,
): Modifier {
    return glass(
        shape = shape,
        fill = fill,
        border = border,
    )
}

/** Same, for surfaces whose fill is a gradient (hero cards, artwork tiles). */
fun Modifier.glass(
    shape: Shape,
    fill: Brush,
    border: Color? = null,
): Modifier = this
    .clip(shape)
    .background(fill)
    .let { if (border != null) it.border(Dimens.hairline, border, shape) else it }

/**
 * Content-layer liquid glass card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = LocalPalette.current.border,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, fill, border), content = content)
}

/** Ambient colour field visible through every liquid-glass surface. */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalPalette.current
    val base = if (palette.isDark) {
        cssLinearGradient(
            155f,
            0f to Color(0xFF101D35),
            0.46f to palette.background,
            1f to Color(0xFF170F2A),
        )
    } else {
        cssLinearGradient(
            155f,
            0f to Color(0xFFE7EFFB),
            0.42f to palette.background,
            0.72f to Color(0xFFF3EFF9),
            1f to Color(0xFFEDF7F5),
        )
    }
    val upperGlow = cssRadialGradient(
        centerX = 0.15f,
        centerY = 0.08f,
        endStop = 0.72f,
        inner = if (palette.isDark) {
            Brand.PrimaryGradBottom.copy(alpha = 0.24f)
        } else {
            Color.White.copy(alpha = 0.76f)
        },
    )
    val lowerGlow = cssRadialGradient(
        centerX = 0.92f,
        centerY = 0.76f,
        endStop = 0.68f,
        inner = if (palette.isDark) {
            Color(0xFF704FBE).copy(alpha = 0.18f)
        } else {
            Color(0xFF9B7DE0).copy(alpha = 0.18f)
        },
    )
    Box(modifier.fillMaxSize().background(base)) {
        Box(Modifier.fillMaxSize().background(upperGlow))
        Box(Modifier.fillMaxSize().background(lowerGlow))
        content()
    }
}

/** `rgba(0,0,0,.06)` divider used inside stacked form cards. */
@Composable
@ReadOnlyComposable
fun formDivider(): Color =
    if (LocalPalette.current.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

/** Elevation presets transcribed from the prototype's `box-shadow` declarations. */
object Shadows {
    /** 首页搜索入口 `0 6px 18px rgba(90,120,180,.12)` */
    val searchBar = CssShadow(0.dp, 6.dp, 18.dp, 0.dp, Color(0xFF5A78B4).copy(alpha = 0.12f))

    /** 搜索页输入框 `0 6px 18px rgba(90,120,180,.15)` */
    val searchBarFocused = CssShadow(0.dp, 6.dp, 18.dp, 0.dp, Color(0xFF5A78B4).copy(alpha = 0.15f))

    /** Hero `0 10px 30px rgba(30,40,70,.18)` */
    val hero = CssShadow(0.dp, 10.dp, 30.dp, 0.dp, Color(0xFF1E2846).copy(alpha = 0.18f))

    /** Tab bar `0 12px 30px rgba(60,90,150,.18)` */
    val tabBar = CssShadow(0.dp, 12.dp, 30.dp, 0.dp, Color(0xFF3C5A96).copy(alpha = 0.18f))

    /** 用户卡 `0 8px 24px rgba(90,120,180,.12)` */
    val profileCard = CssShadow(0.dp, 8.dp, 24.dp, 0.dp, Color(0xFF5A78B4).copy(alpha = 0.12f))

    /** 连接按钮 `0 10px 24px rgba(61,100,201,.3)` */
    val primaryButton = CssShadow(0.dp, 10.dp, 24.dp, 0.dp, Brand.Primary.copy(alpha = 0.30f))

    /** 详情海报 `0 10px 24px rgba(0,0,0,.25)` */
    val detailPoster = CssShadow(0.dp, 10.dp, 24.dp, 0.dp, Color.Black.copy(alpha = 0.25f))

    /** 弹层 `0 20px 44px -10px rgba(30,40,70,.3)` */
    val sheet = CssShadow(0.dp, 20.dp, 44.dp, (-10).dp, Color(0xFF1E2846).copy(alpha = 0.30f))

    /** 下拉菜单 `0 16px 36px -8px rgba(30,40,70,.3)` */
    val menu = CssShadow(0.dp, 16.dp, 36.dp, (-8).dp, Color(0xFF1E2846).copy(alpha = 0.30f))

    /** 播放器底部面板 `0 20px 40px rgba(0,0,0,.35)` */
    val playerPanel = CssShadow(0.dp, 20.dp, 40.dp, 0.dp, Color.Black.copy(alpha = 0.35f))

    /** 播放器设置面板 `0 20px 50px -12px rgba(30,40,70,.3)` */
    val playerSheet = CssShadow(0.dp, 20.dp, 50.dp, (-12).dp, Color(0xFF1E2846).copy(alpha = 0.30f))

    /** 下一集提示 `0 16px 36px rgba(0,0,0,.4)` */
    val nextUp = CssShadow(0.dp, 16.dp, 36.dp, 0.dp, Color.Black.copy(alpha = 0.40f))

    /** 迷你播放器 `0 14px 30px rgba(0,0,0,.3)` */
    val miniPlayer = CssShadow(0.dp, 14.dp, 30.dp, 0.dp, Color.Black.copy(alpha = 0.30f))
}

/** A single `box-shadow` declaration. */
data class CssShadow(
    val offsetX: androidx.compose.ui.unit.Dp,
    val offsetY: androidx.compose.ui.unit.Dp,
    val blur: androidx.compose.ui.unit.Dp,
    val spread: androidx.compose.ui.unit.Dp,
    val color: Color,
)

fun Modifier.shadow(shadow: CssShadow, shape: Shape): Modifier =
    cssShadow(shadow.offsetX, shadow.offsetY, shadow.blur, shadow.spread, shadow.color, shape)
