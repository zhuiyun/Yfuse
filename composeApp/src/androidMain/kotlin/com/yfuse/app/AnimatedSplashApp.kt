package com.yfuse.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.LogoPalette
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.YfuseArcSweep
import com.yfuse.core.designsystem.drawYfuseFireArc
import com.yfuse.core.designsystem.drawYfusePlayhead
import com.yfuse.core.designsystem.drawYfuseWaterArc
import com.yfuse.core.designsystem.yfuseFireBrush
import com.yfuse.core.designsystem.yfuseWaterBrush
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps the real app composing behind the splash so startup work and the entrance
 * animation happen in parallel. The saved flag prevents replay after configuration
 * changes while still showing the animation for each fresh launch.
 */
@Composable
fun AnimatedSplashApp(
    root: RootComponent,
    overlay: @Composable () -> Unit = {},
) {
    var splashVisible by rememberSaveable { mutableStateOf(true) }
    val themeMode by root.themePreferences.mode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }

    Box(Modifier.fillMaxSize()) {
        App(root)
        overlay()

        if (splashVisible) {
            AnimatedSplashScreen(
                dark = dark,
                onFinished = { splashVisible = false },
            )
        }
    }
}

/**
 * 开屏「双弧汇流」—— 设计文件「Yfuse Logo 重做」的 **9c**。两条弧沿路径慢慢延伸生长
 * （火自左向右、水自右向左）→ 在中线收笔时一道细光 → 播放头弹出 → 字标展开，停在
 * 这一帧直接接首屏。所有时间点、缓动曲线和位移量都取自设计稿的 CSS keyframes。
 *
 * 标志本体深浅色共用一套色（见 [com.yfuse.core.designsystem.YfuseMark]）；跟主题走的
 * 是底色、字标墨色和中线细光——两套 [SplashSkin]。
 */
@Composable
private fun AnimatedSplashScreen(
    dark: Boolean,
    onFinished: () -> Unit,
) {
    FullscreenSplashEffect()
    StatusBarIconStyle(darkIcons = !dark)

    val skin = if (dark) DarkSkin else LightSkin
    val clock = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        clock.animateTo(
            targetValue = TimelineMs.toFloat(),
            animationSpec = tween(durationMillis = PlaybackMs, easing = LinearEasing),
        )
        onFinished()
    }

    val now = clock.value

    Box(
        Modifier
            .fillMaxSize()
            .background(skin.background),
        contentAlignment = Alignment.Center,
    ) {
        Glows(now)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MarkStage(now = now, skin = skin)
            Spacer(Modifier.height(28.dp))
            Wordmark(now = now, skin = skin)
        }
    }
}

/** Uses the entire display for launch, then restores system bars for the app. */
@Composable
private fun FullscreenSplashEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    repeat(12) {
        when (val context = current) {
            is Activity -> return context
            is ContextWrapper -> {
                val base = context.baseContext
                if (base === context) return null
                current = base
            }
            else -> return null
        }
    }
    return null
}

/**
 * 两团暖/冷散光，对应设计稿的两个 `radial-gradient` + `blur(28px)`。位置按 320×660 的
 * 设计画框折算成屏幕比例：暖光略高于中线，冷光压在下面。
 */
@Composable
private fun Glows(now: Float) {
    val warm = span(now, start = 0, duration = 1_300)
    val cool = span(now, start = 120, duration = 1_300)
    Canvas(Modifier.fillMaxSize()) {
        drawGlow(warm, centerY = 0.485f, color = GlowWarm)
        drawGlow(cool, centerY = 0.606f, color = GlowCool)
    }
}

/**
 * `radial-gradient(circle, <color>, transparent 66%)` + `spGlow`。设计稿还叠了
 * `blur(28px)`，那只是把本已很软的衰减再摊开一点，多给一段停靠就够，不必再走一遍
 * render effect。
 */
private fun DrawScope.drawGlow(fraction: Float, centerY: Float, color: Color) {
    if (fraction <= 0f) return
    val eased = GlowEase.transform(fraction)
    val alpha = keyframes(eased, 0f to 0f, 0.55f to 0.6f, 1f to 0.32f)
    val radius = size.width * 0.47f * lerp(0.5f, 1f, eased)
    val center = Offset(size.width / 2f, size.height * centerY)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = color.alpha * alpha),
            0.66f to Color.Transparent,
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * 132dp 的方形舞台：火弧、水弧、中线细光、播放头，四层画在同一块 Canvas 上，
 * 这样弧的圆头端点能跟着笔尖走。
 */
@Composable
private fun MarkStage(now: Float, skin: SplashSkin) {
    val fire = Draw.transform(span(now, start = 0, duration = 1_150))
    val water = Draw.transform(span(now, start = 100, duration = 1_150))
    val seam = span(now, start = 1_050, duration = 600)
    val pop = span(now, start = 1_200, duration = 500)

    Canvas(Modifier.size(MarkSize)) {
        drawYfuseFireArc(yfuseFireBrush(), YfuseArcSweep * fire)
        drawYfuseWaterArc(yfuseWaterBrush(), YfuseArcSweep * water)

        // 收笔的一道细光：从中线中间向两侧撑开再散掉。
        if (seam > 0f && seam < 1f) {
            val alpha = keyframes(seam, 0f to 0f, 0.45f to 0.9f, 1f to 0f)
            val stretch = lerp(0.2f, 1.25f, seam)
            val inset = size.width * (8f / 132f)
            val half = (size.width / 2f - inset) * stretch
            val thickness = size.width * (2f / 132f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to skin.seam.copy(alpha = alpha),
                    1f to Color.Transparent,
                    startX = size.width / 2f - half,
                    endX = size.width / 2f + half,
                ),
                topLeft = Offset(size.width / 2f - half, (size.height - thickness) / 2f),
                size = Size(half * 2f, thickness),
            )
        }

        if (pop > 0f) {
            val eased = Pop.transform(pop)
            val alpha = (pop / 0.6f).coerceAtMost(1f)
            val scale = keyframes(eased, 0f to 0.2f, 0.6f to 1.2f, 0.8f to 0.94f, 1f to 1f)
            withTransform({
                scale(scale, scale, pivot = Offset(size.width / 2f, size.height / 2f))
            }) {
                drawYfusePlayhead(
                    fire = LogoPalette.HeadFire.copy(alpha = alpha),
                    water = LogoPalette.HeadWater.copy(alpha = alpha),
                )
            }
        }
    }
}

/** 字标展开（字距从 16 收到 3）+ 副标上浮。 */
@Composable
private fun Wordmark(now: Float, skin: SplashSkin) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val word = Word.transform(span(now, start = 1_450, duration = 800))
        Text(
            text = "Yfuse",
            color = skin.word,
            fontSize = 29.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = lerp(16f, 3f, word).sp,
            modifier = Modifier
                .blur(lerp(9f, 0f, word).dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { alpha = word },
        )
        Spacer(Modifier.height(12.dp))
        val sub = EaseOut.transform(span(now, start = 1_750, duration = 700))
        Text(
            text = "影音，一处汇流",
            color = skin.word,
            fontSize = 12.sp,
            letterSpacing = 4.sp,
            modifier = Modifier.graphicsLayer {
                alpha = sub * 0.55f
                translationY = lerp(10f, 0f, sub).dp.toPx()
            },
        )
    }
}

// ---------------------------------------------------------------- 时间线与缓动

/**
 * 编排长度：每个阶段的起止都写在这条时间线上（副标在 2450ms 收尾，多留一拍再交给主界面）。
 *
 * 这不是它在屏幕上的实际时长——见 [PlaybackMs]。
 */
private const val TimelineMs = 2_600

/**
 * 实际播放时长：整条时间线按这个速度跑完。
 *
 * 各阶段是相互叠着的（弧线未画完接缝就起，字标未展开副标就跟上），中间没有可以剪掉的空档，
 * 所以缩短靠整体加速而不是截断——后者会把字标和副标切掉一半。等比压缩保住了编排的比例，
 * 只是整体更快交给主界面。
 */
private const val PlaybackMs = 2_000
private val MarkSize = 132.dp

/** 两团散光的色值 —— `rgba(255,106,22,.22)` / `rgba(20,169,240,.2)`。 */
private val GlowWarm = Color(0xFFFF6A16).copy(alpha = 0.22f)
private val GlowCool = Color(0xFF14A9F0).copy(alpha = 0.20f)

private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** `cubic-bezier(.4,0,.2,1)` — 弧线生长。 */
private val Draw = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** `cubic-bezier(.16,1,.3,1)` — 播放头弹出与字标展开。 */
private val Pop = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val Word = Pop

/** `cubic-bezier(.2,.8,.2,1)` — 散光浮现。 */
private val GlowEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private fun span(nowMs: Float, start: Int, duration: Int): Float =
    ((nowMs - start) / duration).coerceIn(0f, 1f)

private fun lerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

/** CSS keyframe 列表的分段线性求值——`stops` 必须按位置升序。 */
private fun keyframes(fraction: Float, vararg stops: Pair<Float, Float>): Float {
    if (fraction <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (at, value) = stops[i]
        if (fraction <= at) {
            val (prevAt, prevValue) = stops[i - 1]
            val span = (at - prevAt).takeIf { it > 0f } ?: return value
            return lerp(prevValue, value, (fraction - prevAt) / span)
        }
    }
    return stops.last().second
}

// ---------------------------------------------------------------- 亮色 / 深色皮肤

@Immutable
private data class SplashSkin(
    val background: Color,
    val word: Color,
    /** 中线收笔的细光——浅色底用墨色，深色底反白。 */
    val seam: Color,
)

/** 设计稿 9c 的浅色画框底 `#F7F8FB`。 */
private val LightSkin = SplashSkin(
    background = Color(0xFFF7F8FB),
    word = Color(0xFF12151E),
    seam = Color(0xFF12151E),
)

/** 深色对位：图标壳同色 `#05070C`，字标与细光反白。 */
private val DarkSkin = SplashSkin(
    background = LogoPalette.Shell,
    word = Color(0xFFFFFFFF),
    seam = Color(0xFFFFFFFF),
)
