package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Design tokens transcribed 1:1 from the "Emby 液态玻璃 UI · 设计规范" spec sheet.
 * CSS px map directly to dp; every literal here has a counterpart in the spec.
 */

// ---------------------------------------------------------------- brand colours

/** 品牌与功能色 — spec section "品牌与功能色". */
object Brand {
    val Primary = Color(0xFF3D64C9)
    val PrimaryGradTop = Color(0xFF8FB2E8)
    val PrimaryGradBottom = Color(0xFF5B7FD1)
    val Online = Color(0xFF4FB56A)
    val Offline = Color(0xFFC2C9D3)
    val Danger = Color(0xFFC9584A)
    val Imdb = Color(0xFFF5C518)
    val Douban = Color(0xFF2F9E5E)
}

/** 主色渐变 135deg — used for avatars, server badges, category cards. */
val PrimaryGradient: Brush = cssLinearGradient(
    135f,
    0f to Brand.PrimaryGradTop,
    1f to Brand.PrimaryGradBottom,
)

// ---------------------------------------------------------------- theme palette

/**
 * 设计说明文档 §8.2 色彩. Light / dark are one variable set switched under two themes;
 * there is exactly one accent ([Brand.Primary], `#3d64c9`) and no second saturated hue.
 *
 * The product direction uses liquid glass as the primary material. Page backgrounds
 * carry the colour and depth; cards, sheets and controls remain translucent so the
 * surrounding artwork and ambient colour continue through the whole app.
 */
@Immutable
data class Palette(
    /** 页面底色 — the base under the ambient gradient. */
    val background: Color,
    /** 主文字 */ val text: Color,
    /** 次文字 */ val sub: Color,
    /** `--pg-sub2` */ val sub2: Color,
    /** `--pg-body` */ val body: Color,
    /** `--pg-hint` */ val hint: Color,
    /** Primary content glass. */ val card: Color,
    /** `--pg-card2` */ val card2: Color,
    /** `--pg-card3` */ val card3: Color,
    /** `--pg-sheet` — media-library content sheet. */ val sheet: Color,
    /** 浮层玻璃底 — §8.1, 浅色 0.74–0.82 半透明白 / 深色半透明深底. */
    val glass: Color,
    /**
     * The same material for overlays that sit directly over dense content — tab bar,
     * 迷你播放器, sheets.
     *
     * §8.1 pairs its 0.74–0.82 fill with `blur(20-22px) saturate(180%)`; that blur is
     * what keeps the bar legible over artwork. Compose Multiplatform has no common
     * backdrop-blur, so at the annotated alpha posters read straight through and the
     * tab labels blur out. Raising the alpha buys back the separation the blur provided.
     */
    val glassStrong: Color,
    /** `--pg-border` */ val border: Color,
    /** `--pg-tabbar-border` */ val tabbarBorder: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    background = Color(0xFFF3F5F8),
    text = Color(0xFF151A22),
    sub = Color(0xFF68717F),
    sub2 = Color(0xFF8A93A3),
    body = Color(0xFF5A6472),
    hint = Color(0xFFB0B8C4),
    card = Color.White.copy(alpha = 0.62f),
    card2 = Color.White.copy(alpha = 0.46f),
    card3 = Color.White.copy(alpha = 0.72f),
    sheet = Color.White.copy(alpha = 0.58f),
    glass = Color.White.copy(alpha = 0.54f),
    glassStrong = Color.White.copy(alpha = 0.72f),
    border = Color.White.copy(alpha = 0.70f),
    tabbarBorder = Color.White.copy(alpha = 0.82f),
    isDark = false,
)

val DarkPalette = Palette(
    background = Color(0xFF080D17),
    text = Color(0xFFEEF0F3),
    sub = Color(0xFF9AA4B4),
    sub2 = Color(0xFF9199A8),
    body = Color(0xFFB7BFCB),
    hint = Color(0xFF6B7280),
    card = Color(0xFF182235).copy(alpha = 0.62f),
    card2 = Color(0xFF111A2A).copy(alpha = 0.48f),
    card3 = Color(0xFF202D43).copy(alpha = 0.70f),
    sheet = Color(0xFF111A2A).copy(alpha = 0.62f),
    glass = Color(0xFF111A29).copy(alpha = 0.58f),
    glassStrong = Color(0xFF111A29).copy(alpha = 0.76f),
    border = Color.White.copy(alpha = 0.18f),
    tabbarBorder = Color.White.copy(alpha = 0.24f),
    isDark = true,
)

// ---------------------------------------------------------------- player colours

/** 播放器背景 `linear-gradient(155deg,#2C3B57,#0C1018 60%)`. */
val PlayerBackground: Brush = cssLinearGradient(
    155f,
    0f to Color(0xFF2C3B57),
    0.6f to Color(0xFF0C1018),
)

/** `radial-gradient(circle at 30% 20%,rgba(120,150,220,.25),transparent 60%)`. */
val PlayerGlowPortrait: Brush = cssRadialGradient(
    centerX = 0.30f,
    centerY = 0.20f,
    endStop = 0.60f,
    inner = Color(0xFF7896DC).copy(alpha = 0.25f),
)

/** Landscape variant: `circle at 25% 30%,rgba(120,150,220,.22)`. */
val PlayerGlowLandscape: Brush = cssRadialGradient(
    centerX = 0.25f,
    centerY = 0.30f,
    endStop = 0.60f,
    inner = Color(0xFF7896DC).copy(alpha = 0.22f),
)

/** Player chrome literals shared by the portrait and landscape surfaces. */
object PlayerTokens {
    val panelFill = Color(0xFF121622).copy(alpha = 0.45f)     // rgba(18,22,34,.45)
    val topBarFill = Color(0xFF141A28).copy(alpha = 0.40f)    // rgba(20,26,40,.4)
    val drawerFill = Color(0xFF121622).copy(alpha = 0.75f)    // rgba(18,22,34,.75)
    val drawerFillLandscape = Color(0xFF121622).copy(alpha = 0.70f)
    val nextUpFill = Color(0xFF141826).copy(alpha = 0.72f)    // rgba(20,24,38,.72)
    val hairline = Color.White.copy(alpha = 0.18f)
    val controlFill = Color.White.copy(alpha = 0.16f)
    val controlBorder = Color.White.copy(alpha = 0.28f)
    val chipFill = Color.White.copy(alpha = 0.14f)
    val chipBorder = Color.White.copy(alpha = 0.22f)
    val playFill = Color.White.copy(alpha = 0.68f)
    val onPlay = Color(0xFF141A26)
    val trackFill = Color.White.copy(alpha = 0.22f)
    val trackFillLandscape = Color.White.copy(alpha = 0.24f)
    val timeText = Color.White.copy(alpha = 0.70f)
    val timeTextLandscape = Color.White.copy(alpha = 0.75f)
    val footerText = Color.White.copy(alpha = 0.65f)
    val sheetFill = Color.White.copy(alpha = 0.76f)
    val sheetFillLandscape = Color.White.copy(alpha = 0.80f)
    val episodeIdleFill = Color.White.copy(alpha = 0.08f)
    val episodeActiveFill = Brand.Primary.copy(alpha = 0.25f)
    val episodeActiveBorder = Color(0xFF7FA2E8).copy(alpha = 0.40f)
    val episodeActiveSub = Color(0xFFA7C0F2)
    val nextUpRing = Color(0xFF7FA2E8)
    val nextUpRingTrack = Color.White.copy(alpha = 0.15f)
    val nextUpCore = Color(0xFF151A26)

    /** Progress fill `linear-gradient(90deg,#7FA2E8,#A7C0F2)`. */
    val progress: Brush = cssLinearGradient(
        90f,
        0f to Color(0xFF7FA2E8),
        1f to Color(0xFFA7C0F2),
    )
}

/** Mini player — `.miniplayer`. */
object MiniPlayerTokens {
    val fill = Color(0xFF121622).copy(alpha = 0.75f)
    val border = Color.White.copy(alpha = 0.16f)
    val artwork: Brush = cssLinearGradient(
        135f,
        0f to Color(0xFF3A4A6B),
        1f to Color(0xFF1B2436),
    )
}

// ---------------------------------------------------------------- metrics

/**
 * 设计说明文档 §8.4 圆角与间距.
 *
 * Values are the spec's prototype-canvas px carried over as dp, which is the convention
 * the whole codebase uses. The spec's ×1.31 canvas→pt factor is *not* applied: it targets
 * a 393 pt iPhone baseline, and scaling by it on a typical 360 dp Android phone visibly
 * inflates the layout.
 *
 * **圆角三档，不允许中间值** — anything that needs a radius picks [small], [medium] or
 * [large]. The old 14 / 20 / 24 / 31 px steps are gone.
 */
object Dimens {
    /** 页面水平内边距统一 18px */ val pageHorizontal = 18.dp

    /**
     * Gap between the status bar and the first row. The prototype's screens use
     * `padding-top:52px` over a `40px` status bar, so the real inset is 12px —
     * applied on top of `statusBarsPadding()`, whose height varies by device.
     */
    val contentTop = 20.dp

    /** 滚动容器底部预留供浮层组避让（迷你播放器 + tab bar）. */
    val contentBottom = 124.dp

    /** 卡片间距 8–14px */ val cardGap = 14.dp

    /** 大区块间距 18–22px */ val sectionGap = 22.dp

    // ------------------------------------------------------------ 圆角三档
    /** 小 10px — 缩略图、内嵌小块. */ val small = 10.dp

    /** 中 16px — 海报、按钮、胶囊、菜单. */ val medium = 16.dp

    /** 大 26px — sheet、迷你播放器、tab bar. */ val large = 26.dp

    /** 悬浮 Tab Bar — 与迷你播放器共用材质、圆角与左右边距（§3）. */
    val tabBarHeight = 54.dp
    val tabBarInset = 14.dp

    /** 卡片描边 */ val hairline = 1.dp
}

/**
 * Space the scrollable content must leave for the floating overlay stack —
 * 滚动容器底部预留 134px, §8.4.
 */
val TabBarInset = Dimens.contentBottom

// ---------------------------------------------------------------- motion

/**
 * 设计说明文档 §3.1 转场体系 — five transitions, all on one iOS curve. Durations are ms.
 *
 * 开启「减弱动态效果」后全部降为瞬时切换（see [AccessibilityPreferences] consumers).
 */
object Motion {
    /** `cubic-bezier(.32,.72,0,1)` — the single easing used by every transition. */
    val Curve = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 推进（详情 / 类型 / 下载）— 右侧 30px 滑入 + 淡入. */
    const val PUSH = 360
    val pushOffset = 30.dp

    /** 返回 — 左侧 22px 滑入 + 淡入. */
    const val POP = 300
    val popOffset = 22.dp

    /** 平级切 tab — 0.986 缩放淡入. */
    const val TAB = 260
    const val TAB_SCALE_FROM = 0.986f

    /** 覆盖（播放器 / 菜单）— 下方 46px 上滑. */
    const val MODAL = 400
    val modalOffset = 46.dp

    /** 迷你播放器展开 — 从底部 0.8 缩放放大；详情页顶图 1.14 → 1. */
    const val EXPAND = 460
    const val MINI_SCALE_FROM = 0.8f
    const val DETAIL_HERO_SCALE_FROM = 1.14f

    /** 顶栏材质切换 — 滚动超过 280px 后转为玻璃底（§4.2）. */
    const val TOP_BAR = 280

    /** 图片渐进加载：占位主色渐变 → 12px 模糊放大 1.05 → 清晰归位. */
    const val IMAGE_IN = 550
    val imageBlur = 12.dp
    const val IMAGE_SCALE_FROM = 1.05f
}

// ---------------------------------------------------------------- typography

/**
 * The spec pairs Noto Sans SC (Chinese) with Manrope (Latin/numerals). Neither is
 * bundled, so both resolve through the platform default — on Android that is Noto
 * Sans CJK for Chinese glyphs and Roboto for Latin. Sizes, weights and line heights
 * below are the annotated values.
 */
private val SansSc = FontFamily.Default
private val Manrope = FontFamily.Default

/** `font: <weight> <size>px 'Noto Sans SC'` */
fun sc(size: Float, weight: Int, lineHeight: Float? = null) = TextStyle(
    fontFamily = SansSc,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = (lineHeight ?: (size * 1.35f)).sp,
)

/** `font: <weight> <size>px Manrope` */
fun mr(size: Float, weight: Int, lineHeight: Float? = null) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = (lineHeight ?: (size * 1.35f)).sp,
)

/**
 * 设计说明文档 §8.3 字体四级体系 — 四级层次，不新增字号. Sizes are the spec's canvas px
 * carried over as sp; see [Dimens] on why the ×1.31 factor is not applied.
 *
 * Reach for these rather than a fresh [sc] / [mr] call: the spec caps the scale at four
 * steps, and every ad-hoc size widens it.
 */
object Type {
    /** Display · 800 · 22–26px — 页面主标题、hero 片名. */
    fun display(size: Float = 26f) = sc(size, 800)

    /** Section · 700 · 15–18px — 货架标题、顶栏标题. */
    fun section(size: Float = 18f) = sc(size, 700)

    /** Body · 400–600 · 11.5–13px — 卡片标题、简介、列表行. */
    fun body(size: Float = 13f, weight: Int = 400) = sc(size, weight)

    /** Caption · 400–700 · 9–11px — 年份、条目数、徽章. */
    fun caption(size: Float = 11f, weight: Int = 400) = mr(size, weight)
}

// ---------------------------------------------------------------- gradient helpers

/**
 * CSS `linear-gradient(<deg>, …)`: 0deg points up, angles advance clockwise, and the
 * gradient line is sized so the first and last stops land on the box corners.
 */
fun cssLinearGradient(degrees: Float, vararg stops: Pair<Float, Color>): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val rad = degrees * PI.toFloat() / 180f
            val dx = sin(rad)
            val dy = -cos(rad)
            val length = abs(size.width * dx) + abs(size.height * dy)
            val cx = size.width / 2f
            val cy = size.height / 2f
            return LinearGradientShader(
                from = Offset(cx - dx * length / 2f, cy - dy * length / 2f),
                to = Offset(cx + dx * length / 2f, cy + dy * length / 2f),
                colors = stops.map { it.second },
                colorStops = stops.map { it.first },
                tileMode = TileMode.Clamp,
            )
        }
    }

/** CSS `radial-gradient(circle at x% y%, <inner>, transparent <endStop>)`. */
fun cssRadialGradient(
    centerX: Float,
    centerY: Float,
    endStop: Float,
    inner: Color,
): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        // `circle` with no explicit extent defaults to farthest-corner.
        val cx = size.width * centerX
        val cy = size.height * centerY
        val radius = maxOf(
            hypot(cx, cy),
            hypot(size.width - cx, cy),
            hypot(cx, size.height - cy),
            hypot(size.width - cx, size.height - cy),
        ) * endStop
        return RadialGradientShader(
            center = Offset(cx, cy),
            radius = radius.coerceAtLeast(1f),
            colors = listOf(inner, inner.copy(alpha = 0f)),
            colorStops = listOf(0f, 1f),
            tileMode = TileMode.Clamp,
        )
    }
}

private fun hypot(a: Float, b: Float): Float = kotlin.math.sqrt(a * a + b * b)

/** CSS `linear-gradient(0deg, …)` scrim over artwork, expressed as stop pairs. */
fun scrim(vararg stops: Pair<Float, Color>): Brush = cssLinearGradient(0f, *stops)
