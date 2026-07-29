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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.designsystem.YfuseMark
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
 * 开屏「光圈收拢」— 设计文件「Yfuse Logo 与开屏动画」第 4 轮。三片叶片各自旋进
 * 归位，一道光束自下而上穿过，随后闪光扩散、字标收紧、副标上浮。所有时间点、
 * 缓动曲线和位移量都取自设计稿的 CSS keyframes。
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
            animationSpec = tween(durationMillis = TimelineMs, easing = LinearEasing),
        )
        onFinished()
    }

    val now = clock.value

    Box(
        Modifier
            .fillMaxSize()
            .background(skin.backgroundOuter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // CSS 侧是椭圆 radial-gradient，Compose 的 radialGradient 只有正圆，
            // 因此按竖直方向压扁坐标系再铺满。
            val squeeze = (size.height * skin.backgroundRadiusY) /
                (size.width * skin.backgroundRadiusX)
            val center = Offset(size.width / 2f, size.height * skin.backgroundCenterY)
            withTransform({ scale(1f, squeeze, pivot = center) }) {
                drawRect(
                    brush = Brush.radialGradient(
                        0f to skin.backgroundInner,
                        skin.backgroundStop to skin.backgroundOuter,
                        1f to skin.backgroundOuter,
                        center = center,
                        radius = size.width * skin.backgroundRadiusX,
                    ),
                    topLeft = Offset(0f, center.y - size.height / (2f * squeeze)),
                    size = Size(size.width, size.height / squeeze),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MarkStage(now = now, skin = skin)
            Spacer(Modifier.height(32.dp))
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

/** 光束 + 叶片旋进 + 闪光，三层叠在 142dp 的方形舞台里。 */
@Composable
private fun MarkStage(now: Float, skin: SplashSkin) {
    Box(
        modifier = Modifier.size(MarkSize),
        contentAlignment = Alignment.Center,
    ) {
        val beam = span(now, start = 600, duration = 1_100)
        val beamMove = EaseOut.transform(beam)
        Box(
            Modifier
                .width(60.dp)
                .height(190.dp)
                .graphicsLayer {
                    alpha = pulse(beam, peak = 0.35f, top = 0.9f, easing = EaseOut)
                    translationY = lerp(60f, -70f, beamMove).dp.toPx()
                    scaleY = lerp(0.2f, 1.4f, beamMove)
                }
                .blur(11.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to skin.beam,
                        1f to Color.Transparent,
                    ),
                ),
        )

        val spin = Spin.transform(span(now, start = 0, duration = 950))
        YfuseMark(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = lerp(-12f, 0f, spin)
                    val scale = lerp(0.82f, 1f, spin)
                    scaleX = scale
                    scaleY = scale
                    alpha = spin
                },
        )

        val flash = span(now, start = 820, duration = 950)
        Box(
            Modifier
                .size(170.dp)
                .graphicsLayer {
                    alpha = pulse(flash, peak = 0.4f, top = 0.85f, easing = EaseOut)
                    val scale = if (flash <= 0.4f) {
                        lerp(0.6f, 1.25f, EaseOut.transform(flash / 0.4f))
                    } else {
                        lerp(1.25f, 1.9f, EaseOut.transform((flash - 0.4f) / 0.6f))
                    }
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    Brush.radialGradient(
                        0f to skin.flash,
                        0.62f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** 字标收紧（字距从 14 收到 -0.8）+ 副标上浮。 */
@Composable
private fun Wordmark(now: Float, skin: SplashSkin) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val word = Track.transform(span(now, start = 880, duration = 800))
        Text(
            text = "Yfuse",
            color = skin.word,
            fontSize = 33.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = lerp(14f, -0.8f, word).sp,
            modifier = Modifier
                .blur(lerp(8f, 0f, word).dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { alpha = word },
        )
        Spacer(Modifier.height(12.dp))
        val sub = EaseOut.transform(span(now, start = 1_100, duration = 650))
        Text(
            text = "收拢即开始",
            color = skin.subtitle,
            fontSize = 10.5.sp,
            letterSpacing = 3.sp,
            modifier = Modifier
                .blur(lerp(7f, 0f, sub).dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer {
                    alpha = sub
                    translationY = lerp(16f, 0f, sub).dp.toPx()
                },
        )
    }
}

// ---------------------------------------------------------------- 时间线与缓动

/** 最后一段（副标）在 1750ms 收尾，多留一拍再交给主界面。 */
private const val TimelineMs = 1_900
private val MarkSize = 142.dp

private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val Spin = CubicBezierEasing(0.28f, 1.2f, 0.4f, 1f)
private val Track = CubicBezierEasing(0.2f, 0.85f, 0.15f, 1f)

private fun span(nowMs: Float, start: Int, duration: Int): Float =
    ((nowMs - start) / duration).coerceIn(0f, 1f)

private fun lerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

/** 三帧关键帧的透明度：0 → [top]（落在 [peak]）→ 0，每段各自缓动。 */
private fun pulse(fraction: Float, peak: Float, top: Float, easing: Easing): Float =
    if (fraction <= peak) {
        top * easing.transform(fraction / peak)
    } else {
        top * (1f - easing.transform((fraction - peak) / (1f - peak)))
    }

// ---------------------------------------------------------------- 亮色 / 深色皮肤

@Immutable
private data class SplashSkin(
    val backgroundInner: Color,
    val backgroundOuter: Color,
    val backgroundStop: Float,
    val backgroundRadiusX: Float,
    val backgroundRadiusY: Float,
    val backgroundCenterY: Float,
    val beam: Color,
    val flash: Color,
    val word: Color,
    val subtitle: Color,
)

/** radial-gradient(120% 78% at 50% 56%, #FFFFFF, #E4E9F2 78%) */
private val LightSkin = SplashSkin(
    backgroundInner = Color(0xFFFFFFFF),
    backgroundOuter = Color(0xFFE4E9F2),
    backgroundStop = 0.78f,
    backgroundRadiusX = 1.20f,
    backgroundRadiusY = 0.78f,
    backgroundCenterY = 0.56f,
    beam = Color(0x576C63FF),
    flash = Color(0x476C63FF),
    word = Color(0xFF151A28),
    subtitle = Color(0x73181E2C),
)

/** radial-gradient(115% 76% at 50% 58%, #1B1749, #08090F 74%) */
private val DarkSkin = SplashSkin(
    backgroundInner = Color(0xFF1B1749),
    backgroundOuter = Color(0xFF08090F),
    backgroundStop = 0.74f,
    backgroundRadiusX = 1.15f,
    backgroundRadiusY = 0.76f,
    backgroundCenterY = 0.58f,
    beam = Color(0x8CA096FF),
    flash = Color(0x6B7CF0F4),
    word = Color(0xFFFFFFFF),
    subtitle = Color(0x6BFFFFFF),
)
