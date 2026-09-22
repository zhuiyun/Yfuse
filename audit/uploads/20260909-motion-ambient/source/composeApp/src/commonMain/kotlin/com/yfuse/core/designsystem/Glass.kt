package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Semantic shape roles. Small geometry uses [micro]/[track]; content surfaces stay on the
 * 10/16/26dp ladder through [thumb], [card]/[control]/[chip], and [sheet].
 */
object AppShapes {
    val micro = ContinuousRoundedCornerShape(4.dp)
    val track = ContinuousRoundedCornerShape(3.dp)
    val thumb = ContinuousRoundedCornerShape(Dimens.small)
    val card = ContinuousRoundedCornerShape(Dimens.medium)
    val control = ContinuousRoundedCornerShape(Dimens.medium)
    val chip = ContinuousRoundedCornerShape(Dimens.medium)
    val sheet = ContinuousRoundedCornerShape(Dimens.large)
    val pill: Shape = CircleShape

    /**
     * Material's shape table requires [androidx.compose.foundation.shape.CornerBasedShape].
     * Its standard contours therefore mirror the exact semantic radii above, while custom
     * Yfuse surfaces keep using the continuous-corner originals directly.
     */
    val material =
        Shapes(
            extraSmall = RoundedCornerShape(micro.radius),
            small = RoundedCornerShape(thumb.radius),
            medium = RoundedCornerShape(control.radius),
            large = RoundedCornerShape(card.radius),
            extraLarge = RoundedCornerShape(sheet.radius),
        )
}

/**
 * Compatibility aliases for existing liquid-glass call sites. New code should use
 * [AppShapes] so a component names its semantic role rather than its visual implementation.
 */
object GlassShapes {
    val thumb: Shape = AppShapes.thumb
    val poster: Shape = AppShapes.card
    val chip: Shape = AppShapes.chip
    val card: Shape = AppShapes.card
    val menu: Shape = AppShapes.control
    val sheet: Shape = AppShapes.sheet
    val hero: Shape = AppShapes.sheet
    val tabBar: Shape = AppShapes.sheet

    /** The app mark and other square art that has to read as an icon. */
    val appIcon: Shape = ContinuousIconShape()

    val circle: Shape = AppShapes.pill
}

/** Resolves a translucent semantic fill to the opaque colour it has over [background]. */
internal fun opaqueComposite(
    fill: Color,
    background: Color,
): Color = fill.compositeOver(background).copy(alpha = 1f)

/** Opaque semantic counterparts used when the user requests reduced transparency. */
private fun reducedTransparencyFill(
    fill: Color,
    palette: Palette,
    over: Color = palette.background,
): Color =
    when (fill) {
        palette.card -> if (palette.isDark) Color(0xFF1A2437) else Color(0xFFF6F8FC)
        palette.card2 -> if (palette.isDark) Color(0xFF151F31) else Color(0xFFEEF2F7)
        palette.card3 -> if (palette.isDark) Color(0xFF202D43) else Color(0xFFF3F6FA)
        palette.sheet -> if (palette.isDark) Color(0xFF131D2D) else Color(0xFFF4F7FB)
        palette.glass -> if (palette.isDark) Color(0xFF172235) else Color(0xFFEDF2F8)
        palette.glassStrong -> if (palette.isDark) Color(0xFF1B273B) else Color(0xFFE8EEF7)
        else -> {
            // White translucent controls carry white glyphs over artwork. A solid white plate
            // would erase them, so use a dark opaque control surface in both themes.
            val translucentWhite =
                fill.alpha < 0.55f &&
                    fill.red > 0.90f &&
                    fill.green > 0.90f &&
                    fill.blue > 0.90f
            if (translucentWhite) {
                if (palette.isDark) Color(0xFF273246) else Color(0xFF303A4D)
            } else {
                opaqueComposite(fill, opaqueComposite(over, palette.background))
            }
        }
    }

private fun reducedTransparencyBorder(
    border: Color?,
    palette: Palette,
): Color? =
    when {
        border == null -> null
        border == palette.border || border == palette.tabbarBorder ->
            if (palette.isDark) Color.White.copy(alpha = 0.24f) else Color(0xFFD3DBE7)
        else -> border
    }

/**
 * Glass owns a neutral luminous edge, never a semantic/dark outline.
 *
 * Callers may still decide whether an edge is present, but its colour is resolved here so
 * accent, error and artwork colours cannot turn a translucent surface into an outlined card.
 * Focus and selection rings are separate interaction modifiers and are intentionally not
 * routed through this material resolver.
 */
internal fun resolveGlassMaterialBorder(
    requested: Color?,
    palette: Palette,
): Color? =
    when {
        requested == null -> null
        requested == palette.border || requested == palette.tabbarBorder -> requested
        requested.luminance() >= 0.72f -> requested
        else -> null
    }

internal data class FrostedMaterialTones(
    val top: Color,
    val body: Color,
    val bottom: Color,
)

/**
 * Diffused colour field shared by every 毛玻璃 surface.
 *
 * The old material was one flat, raised-alpha colour. It hid the page but never looked like
 * light had travelled through it, so cards read as cloudy plastic. The new material keeps the
 * semantic source colour, mixes in a cool mist, and gives the pane a very small density ramp.
 * Actual backdrop blur is added where the surface owns a [BackdropState]; these tones are the
 * coherent fallback for ordinary content cards and pre-Android-12 devices.
 */
internal fun resolveFrostedMaterialTones(
    fill: Color,
    palette: Palette,
    density: Float = 1f,
): FrostedMaterialTones {
    if (fill.alpha <= 0.001f) {
        return FrostedMaterialTones(
            top = Color.Transparent,
            body = Color.Transparent,
            bottom = Color.Transparent,
        )
    }
    val resolvedDensity = density.coerceIn(0.75f, 1.25f)
    val composited = fill.compositeOver(palette.background)
    val pale = composited.luminance() >= 0.48f
    val source = fill.copy(alpha = 1f)
    val mist = if (pale) Color(0xFFDCE7F4) else Color(0xFF213149)
    val depth = if (pale) Color(0xFFC8D6E6) else Color(0xFF09111F)
    val mistAmount = (if (pale) 0.55f else 0.46f) * resolvedDensity
    val bodyAlpha =
        (fill.alpha * (0.68f + 0.32f * resolvedDensity))
            .coerceIn(0.18f, if (pale) 0.78f else 0.82f)
    val body =
        lerp(source, mist, mistAmount).copy(alpha = bodyAlpha)
    val top =
        lerp(body.copy(alpha = 1f), Color.White, (if (pale) 0.045f else 0.03f) * resolvedDensity).copy(
            alpha = (body.alpha + 0.025f * resolvedDensity).coerceAtMost(0.84f),
        )
    val bottom =
        lerp(body.copy(alpha = 1f), depth, (if (pale) 0.045f else 0.06f) * resolvedDensity).copy(
            alpha = (body.alpha + 0.01f * resolvedDensity).coerceAtMost(0.83f),
        )
    return FrostedMaterialTones(top = top, body = body, bottom = bottom)
}

private fun frostedMaterialBorder(
    border: Color?,
    palette: Palette,
): Color? =
    border?.copy(
        alpha = minOf(border.alpha, if (palette.isDark) 0.16f else 0.38f),
    )

private fun frostedSurfaceBrush(
    fill: Color,
    palette: Palette,
    density: Float,
): Brush {
    val tones = resolveFrostedMaterialTones(fill, palette, density)
    return cssLinearGradient(
        165f,
        0f to tones.top,
        0.46f to tones.body,
        1f to tones.bottom,
    )
}

private enum class GlassSurfaceWeight(
    val liquidSheen: Float,
    val frostDensity: Float,
) {
    Quiet(liquidSheen = 0.62f, frostDensity = 0.88f),
    Standard(liquidSheen = 0.82f, frostDensity = 1f),
    Strong(liquidSheen = 1f, frostDensity = 1.10f),
}

private fun liquidSurfaceBrush(
    fill: Color,
    palette: Palette,
    weight: GlassSurfaceWeight,
): Brush {
    val pale = fill.compositeOver(palette.background).luminance() >= 0.48f
    val depth = if (pale) Color(0xFFDCE5F1) else Color(0xFF070C16)
    val top =
        lerp(fill, Color.White, (if (pale) 0.18f else 0.11f) * weight.liquidSheen).copy(
            alpha = (fill.alpha + 0.055f * weight.liquidSheen).coerceAtMost(0.94f),
        )
    val bottom =
        lerp(fill, depth, (if (pale) 0.07f else 0.10f) * weight.liquidSheen).copy(
            alpha = (fill.alpha * 0.96f).coerceIn(0f, 1f),
        )
    return cssLinearGradient(
        145f,
        0f to top,
        0.34f to fill,
        0.76f to fill.copy(alpha = (fill.alpha * 0.98f).coerceIn(0f, 1f)),
        1f to bottom,
    )
}

@Composable
private fun Modifier.glassMaterial(
    shape: Shape,
    fill: Color,
    border: Color?,
    weight: GlassSurfaceWeight,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    if (LocalMutedGlass.current) return mutedGlassControl(shape, fill, border)
    val materialBorder = resolveGlassMaterialBorder(border, palette)
    val frosted = frostedGlass()
    val resolvedBorder =
        if (accessibility.reduceTransparency) {
            reducedTransparencyBorder(materialBorder, palette)
        } else if (frosted) {
            frostedMaterialBorder(materialBorder, palette)
        } else {
            materialBorder
        }
    val surface =
        when {
            accessibility.reduceTransparency -> {
                val opaque = reducedTransparencyFill(fill, palette)
                Brush.linearGradient(listOf(opaque, opaque))
            }

            frosted -> frostedSurfaceBrush(fill, palette, weight.frostDensity)
            else -> liquidSurfaceBrush(fill, palette, weight)
        }
    return clip(shape)
        .background(surface)
        .let { modifier ->
            if (resolvedBorder != null) {
                modifier.border(Dimens.hairline, resolvedBorder, shape)
            } else {
                modifier
            }
        }
}

/**
 * Primary liquid-glass surface. A translucent diagonal sheen, single-colour edge and
 * ambient tint preserve depth without a platform-specific blur dependency.
 */
@Composable
fun Modifier.glass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = null,
): Modifier = glassMaterial(shape, fill, border, GlassSurfaceWeight.Standard)

/**
 * Quiet glass surface used by dense forms and settings.
 *
 * Liquid keeps a restrained directional sheen; Frosted removes that sheen and keeps a
 * single translucent pane. Settings use this variant heavily, so it must honor GlassStyle.
 */
@Composable
fun Modifier.flatGlass(
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = null,
): Modifier = glassMaterial(shape, fill, border, GlassSurfaceWeight.Quiet)

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
    border: Color? = null,
): Modifier = glassMaterial(shape, fill, border, GlassSurfaceWeight.Strong)

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
    border: Color? = null,
    over: Color = if (LocalPalette.current.isDark) LocalPalette.current.background else Color.White,
    sheen: Float = 1f,
): Modifier {
    val palette = LocalPalette.current
    val accessibility = LocalAccessibilityOptions.current
    if (LocalMutedGlass.current) return mutedGlassControl(shape, fill, border)
    val materialBorder = resolveGlassMaterialBorder(border, palette)
    if (accessibility.reduceTransparency) {
        val flat = reducedTransparencyFill(fill, palette, over)
        val edge = reducedTransparencyBorder(materialBorder, palette)
        return this
            .clip(shape)
            .background(flat)
            .let { modifier ->
                if (edge != null) modifier.border(Dimens.hairline, edge, shape) else modifier
            }
    }
    // 毛玻璃 uses the same diffused mist and density ramp as every other surface. It never
    // inherits liquid glass's specular sweep or raised button body.
    if (frostedGlass()) {
        if (fill.alpha <= 0.001f && materialBorder == null) return this.clip(shape)
        val frost = frostedSurfaceBrush(fill, palette, GlassSurfaceWeight.Strong.frostDensity)
        val frostBorder = frostedMaterialBorder(materialBorder, palette)
        return this
            .clip(shape)
            .background(frost)
            .let { modifier ->
                if (frostBorder != null) {
                    modifier.border(Dimens.hairline, frostBorder, shape)
                } else {
                    modifier
                }
            }
    }
    // The theme is the wrong signal here — the play key is pale glass under both, and 返回
    // is dense glass over artwork on the light one. What the fill composites to is the right
    // one, so that is what the ramps are keyed off.
    val pale = fill.compositeOver(over).luminance() > 0.42f
    val depth = if (pale) Color(0xFF8CA1C1) else Color(0xFF04070E)
    val body =
        Brush.verticalGradient(
            0f to
                lerp(fill, Color.White, if (pale) 0.38f else 0.16f)
                    .copy(alpha = (fill.alpha * 1.18f).coerceAtMost(1f)),
            0.50f to fill,
            1f to
                lerp(fill, depth, if (pale) 0.16f else 0.30f)
                    .copy(alpha = (fill.alpha * 0.96f).coerceAtMost(1f)),
        )
    val gloss =
        cssLinearGradient(
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
                if (materialBorder != null) {
                    drawOutline(outline, color = materialBorder, style = stroke)
                }
            }
        }
}

/**
 * Which glass the app draws. Defaults to the product direction; the user's choice is provided
 * by [YfuseTheme].
 */
val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.Liquid }

/**
 * True when the user has asked for 毛玻璃.
 *
 * Every glass variant consults this, not just [Modifier.liquidGlass]. The preference first
 * shipped reaching only that one, which is 18 of the app's 187 glass surfaces — the other 169
 * go through [Modifier.glass], [Modifier.solidGlass], [Modifier.flatGlass] or
 * [Modifier.overlayGlass], so switching materials changed almost nothing on screen and read
 * as a setting that did not work.
 *
 * What it removes is the specular: the diagonal white sheen and, on liquid glass, the body
 * ramp. What stays is the translucency, the fill and the luminous edge — so 毛玻璃 is a
 * softer material rather than an opaque one, which is what 减弱透明度 is for.
 */
@Composable
@ReadOnlyComposable
fun frostedGlass(): Boolean = LocalGlassStyle.current == GlassStyle.Frosted

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
    border: Color? = null,
): Modifier =
    glass(
        shape = shape,
        fill = fill,
        border = border,
    )

/** Same, for surfaces whose fill is a gradient (hero cards, artwork tiles). */
@Composable
fun Modifier.glass(
    shape: Shape,
    fill: Brush,
    border: Color? = null,
): Modifier {
    if (LocalMutedGlass.current) return mutedGlassControl(shape, LocalPalette.current.card2, border)
    val materialBorder = resolveGlassMaterialBorder(border, LocalPalette.current)
    return this
        .clip(shape)
        .background(fill)
        .let {
            if (materialBorder != null) {
                it.border(Dimens.hairline, materialBorder, shape)
            } else {
                it
            }
        }
}

/**
 * Content-layer liquid glass card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShapes.card,
    fill: Color = LocalPalette.current.card,
    border: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, fill, border), content = content)
}

/**
 * The layers of the ambient field, bottom first.
 *
 * Exposed separately from [AppBackdrop] for surfaces that need the same ambient field.
 */
@Composable
@ReadOnlyComposable
fun appBackdropBrushes(): List<Brush> {
    val palette = LocalPalette.current
    val base =
        if (palette.isDark) {
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
    val upperGlow =
        cssRadialGradient(
            centerX = 0.15f,
            centerY = 0.08f,
            endStop = 0.72f,
            inner =
                if (palette.isDark) {
                    Brand.PrimaryGradBottom.copy(alpha = 0.24f)
                } else {
                    Color.White.copy(alpha = 0.76f)
                },
        )
    val lowerGlow =
        cssRadialGradient(
            centerX = 0.92f,
            centerY = 0.76f,
            endStop = 0.68f,
            inner =
                if (palette.isDark) {
                    Color(0xFF704FBE).copy(alpha = 0.18f)
                } else {
                    Color(0xFF9B7DE0).copy(alpha = 0.18f)
                },
        )
    return listOf(base, upperGlow, lowerGlow)
}

/** Ambient colour field visible through every liquid-glass surface. */
@Composable
fun AppBackdrop(
    modifier: Modifier = Modifier,
    imageUri: String? = null,
    dim: Float = DEFAULT_BACKGROUND_DIM,
    content: @Composable BoxScope.() -> Unit,
) {
    val layers = appBackdropBrushes()
    Box(modifier.fillMaxSize()) {
        if (imageUri != null) {
            // Cropped to fill: a wallpaper chosen on a phone is portrait and the window is
            // portrait, so the alternative is letterboxing the user's own picture.
            AsyncImage(
                model = imageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The theme's own ground goes over the picture rather than under it. Every surface
        // in the app is translucent and every text colour was chosen against this ramp, so
        // the picture has to sit behind it at a strength the user controls — a photograph
        // reaching the copy directly would decide the contrast of the whole app.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (imageUri == null) 1f else dim }
                .drawBehind { layers.forEach { drawRect(it) } },
        )
        content()
    }
}

/**
 * Enough of the page's ground over a wallpaper to keep body copy on a surface it was
 * designed for, while the picture still reads as a picture.
 */
const val DEFAULT_BACKGROUND_DIM: Float = 0.72f

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

    /** Emphasis-button lift tinted from the active semantic accent. */
    fun primaryButton(accent: Color): CssShadow = CssShadow(0.dp, 10.dp, 24.dp, 0.dp, accent.copy(alpha = 0.30f))

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

/** The active theme accent carried into an emphasis-button lift. */
@Composable
@ReadOnlyComposable
fun semanticPrimaryButtonShadow(): CssShadow = Shadows.primaryButton(LocalAccentColors.current.accent)

/** A single `box-shadow` declaration. */
data class CssShadow(
    val offsetX: androidx.compose.ui.unit.Dp,
    val offsetY: androidx.compose.ui.unit.Dp,
    val blur: androidx.compose.ui.unit.Dp,
    val spread: androidx.compose.ui.unit.Dp,
    val color: Color,
)

fun Modifier.shadow(
    shadow: CssShadow,
    shape: Shape,
): Modifier = cssShadow(shadow.offsetX, shadow.offsetY, shadow.blur, shadow.spread, shadow.color, shape)
