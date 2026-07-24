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
 * The prototype's CSS custom properties on `.phone` / `.phone[data-theme="dark"]`.
 * Names match the CSS variables so annotations stay greppable.
 */
@Immutable
data class Palette(
    /** `--pg-bg`, a 160deg wash. */
    val backgroundStops: List<Pair<Float, Color>>,
    /** `--pg-text` */ val text: Color,
    /** `--pg-sub` */ val sub: Color,
    /** `--pg-sub2` */ val sub2: Color,
    /** `--pg-body` */ val body: Color,
    /** `--pg-hint` */ val hint: Color,
    /** `--pg-card` */ val card: Color,
    /** `--pg-card2` */ val card2: Color,
    /** `--pg-card3` */ val card3: Color,
    /** `--pg-border` */ val border: Color,
    /** `--pg-tabbar-border` */ val tabbarBorder: Color,
    val isDark: Boolean,
) {
    val background: Brush get() = cssLinearGradient(160f, *backgroundStops.toTypedArray())
}

val LightPalette = Palette(
    backgroundStops = listOf(
        0f to Color(0xFFF6F7F9),
        0.45f to Color(0xFFEEF1F5),
        1f to Color(0xFFE6EBF1),
    ),
    text = Color(0xFF151A22),
    sub = Color(0xFF7A8494),
    sub2 = Color(0xFF8A93A3),
    body = Color(0xFF5A6472),
    hint = Color(0xFFB0B8C4),
    card = Color.White.copy(alpha = 0.55f),
    card2 = Color.White.copy(alpha = 0.50f),
    card3 = Color.White.copy(alpha = 0.60f),
    border = Color.White.copy(alpha = 0.80f),
    tabbarBorder = Color.White.copy(alpha = 0.85f),
    isDark = false,
)

val DarkPalette = Palette(
    backgroundStops = listOf(
        0f to Color(0xFF1B1F27),
        0.55f to Color(0xFF14171D),
        1f to Color(0xFF0F1216),
    ),
    text = Color(0xFFEEF0F3),
    sub = Color(0xFF9AA4B4),
    sub2 = Color(0xFF9199A8),
    body = Color(0xFFB7BFCB),
    hint = Color(0xFF6B7280),
    card = Color.White.copy(alpha = 0.08f),
    card2 = Color.White.copy(alpha = 0.06f),
    card3 = Color.White.copy(alpha = 0.10f),
    border = Color.White.copy(alpha = 0.12f),
    tabbarBorder = Color.White.copy(alpha = 0.14f),
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
    val playFill = Color.White.copy(alpha = 0.92f)
    val onPlay = Color(0xFF141A26)
    val trackFill = Color.White.copy(alpha = 0.22f)
    val trackFillLandscape = Color.White.copy(alpha = 0.24f)
    val timeText = Color.White.copy(alpha = 0.70f)
    val timeTextLandscape = Color.White.copy(alpha = 0.75f)
    val footerText = Color.White.copy(alpha = 0.65f)
    val sheetFill = Color.White.copy(alpha = 0.90f)
    val sheetFillLandscape = Color.White.copy(alpha = 0.92f)
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

/** 间距 / 圆角 token table. */
object Dimens {
    /** 页面水平内边距 */ val pageHorizontal = 18.dp

    /**
     * Gap between the status bar and the first row. The prototype's screens use
     * `padding-top:52px` over a `40px` status bar, so the real inset is 12px —
     * applied on top of `statusBarsPadding()`, whose height varies by device.
     */
    val contentTop = 12.dp

    /** `padding-bottom:100px` — clears the floating tab bar. */
    val contentBottom = 100.dp

    /** 大区块间距 */ val sectionGap = 22.dp

    // 圆角
    /** 小圆角（芯片/按钮） */ val chip = 14.dp
    val chipSmall = 10.dp

    /** 卡片圆角 */ val card = 16.dp
    val cardLarge = 20.dp
    val hero = 24.dp

    /** 海报圆角 — `.poster` */ val poster = 14.dp

    /** 悬浮 Tab Bar */
    val tabBarHeight = 62.dp
    val tabBarRadius = 31.dp
    val tabBarInset = 16.dp

    /** 卡片描边 */ val hairline = 1.dp
}

/**
 * Space the scrollable content must leave for the floating tab bar.
 * `bottom:16px` + `height:62px` + breathing room, matching the prototype's 100px.
 */
val TabBarInset = Dimens.contentBottom

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
