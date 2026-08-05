package com.yfuse.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.ThemeMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Composes the real app behind the launch animation so startup work can continue in parallel.
 * The saved flag prevents the animation replaying after a configuration change.
 */
@Composable
fun AnimatedSplashApp(
    root: RootComponent,
    overlay: @Composable () -> Unit = {},
) {
    val themeMode by root.themePreferences.mode.collectAsState()
    val dark = themeMode == ThemeMode.Dark
    var splashVisible by rememberSaveable {
        mutableStateOf(root.themePreferences.splashAnimation.value)
    }

    Box(Modifier.fillMaxSize()) {
        App(root)

        // Dialog-based overlays use their own window and can otherwise appear above the
        // Compose splash. Do not compose them until the splash has fully finished.
        if (!splashVisible) {
            overlay()
        }

        if (splashVisible) {
            AnimatedSplashScreen(
                dark = dark,
                onFinished = { splashVisible = false },
            )
        } else {
            VisibleSystemBarsEffect()
        }
    }
}

/** Two-second, code-drawn cloud animation. No animated bitmap or API-level fallback is needed. */
@Composable
private fun AnimatedSplashScreen(
    dark: Boolean,
    onFinished: () -> Unit,
) {
    FullscreenSplashEffect()
    StatusBarIconStyle(darkIcons = !dark)

    val clock = remember { Animatable(0f) }
    val tagline = rememberSaveable { SplashTaglines.random() }
    LaunchedEffect(Unit) {
        clock.animateTo(
            targetValue = SplashDurationMs.toFloat(),
            animationSpec = tween(durationMillis = SplashDurationMs, easing = LinearEasing),
        )
        onFinished()
    }

    val now = clock.value
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) Color.Black else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth(0.88f)
                    .sizeIn(maxWidth = 430.dp, maxHeight = 430.dp)
                    .aspectRatio(1f),
            ) {
                drawCloudPlayerMark(nowMs = now)
            }
            Spacer(Modifier.height(16.dp))
            SplashWordmark(nowMs = now, dark = dark, tagline = tagline)
        }
    }
}

/** Old splash cadence: colorful name resolves first, then the tagline floats into place. */
@Composable
private fun SplashWordmark(
    nowMs: Float,
    dark: Boolean,
    tagline: String,
) {
    val name = easeOutCubic(span(nowMs, start = 1_050, duration = 420))
    val taglineProgress = easeOutCubic(span(nowMs, start = 1_340, duration = 390))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Yfuse",
            style = TextStyle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF68D7FF),
                        Color(0xFF2F91F4),
                        Color(0xFF675FF2),
                    ),
                ),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = lerp(12f, 3f, name).sp,
            ),
            modifier = Modifier
                .blur(lerp(7f, 0f, name).dp, BlurredEdgeTreatment.Unbounded)
                .graphicsLayer {
                    alpha = name
                    translationY = lerp(8f, 0f, name).dp.toPx()
                },
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = tagline,
            color = if (dark) Color.White.copy(alpha = 0.72f) else Color(0xFF526A84),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.2.sp,
            modifier = Modifier.graphicsLayer {
                alpha = taglineProgress
                translationY = lerp(10f, 0f, taglineProgress).dp.toPx()
            },
        )
    }
}

/** Draws the borderless layered cloud, falling water drop, fragments, and play head. */
private fun DrawScope.drawCloudPlayerMark(nowMs: Float) {
    val d = size.minDimension
    val intro = easeOutCubic(span(nowMs, start = 0, duration = 260))
    val breathing = sin((nowMs / SplashDurationMs) * PI).toFloat() * 0.006f
    val markScale = 0.97f + intro * 0.03f + breathing
    val cloudPivot = Offset(d * 0.50f, d * 0.76f)

    // The falling drop deforms the cloud twice: first at the crown, then more strongly when it
    // reaches the center disc. A decaying sideways wobble keeps the recovery organic.
    val crownContact = bell(span(nowMs, start = 690, duration = 330))
    val centerImpact = bell(span(nowMs, start = 1_030, duration = 360))
    val squash = crownContact * 0.040f + centerImpact * 0.072f
    val rebound = span(nowMs, start = 1_180, duration = 520)
    val wobble = sin(rebound * PI * 4.0).toFloat() * (1f - rebound) * d * 0.007f
    val cloudScaleY = 1f - squash
    val animatedDiscCenter = Offset(
        x = d * 0.50f + wobble,
        y = cloudPivot.y + (d * 0.585f - cloudPivot.y) * cloudScaleY,
    )

    withTransform({
        scale(markScale, markScale, pivot = cloudPivot)
        translate(left = wobble)
        scale(1f + squash, cloudScaleY, pivot = cloudPivot)
    }) {
        drawCloudBody(d)
        drawCenterDisc(d)
    }
    drawWaterTimeline(d = d, nowMs = nowMs, center = animatedDiscCenter)
}

private fun DrawScope.drawCloudBody(d: Float) {
    // Cloud shadow.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(CloudBlue.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(d * 0.50f, d * 0.735f),
            radius = d * 0.31f,
        ),
        topLeft = Offset(d * 0.19f, d * 0.69f),
        size = Size(d * 0.62f, d * 0.105f),
    )

    // Rear blue lobes.
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFB8E8FF), Color(0xFF72C2FA), Color(0xFF318AEF)),
            start = Offset(d * 0.42f, d * 0.20f),
            end = Offset(d * 0.57f, d * 0.66f),
        ),
        radius = d * 0.235f,
        center = Offset(d * 0.50f, d * 0.435f),
    )
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF95D8FF), Color(0xFF4AA5F7), Color(0xFF1476E5)),
            start = Offset(d * 0.65f, d * 0.36f),
            end = Offset(d * 0.76f, d * 0.70f),
        ),
        radius = d * 0.165f,
        center = Offset(d * 0.705f, d * 0.535f),
    )

    // Front base joins the two pale lobes into one cloud silhouette.
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFBFDFF), Color(0xFFE7F3FF), Color(0xFFC9E3FF)),
            startY = d * 0.53f,
            endY = d * 0.79f,
        ),
        topLeft = Offset(d * 0.205f, d * 0.585f),
        size = Size(d * 0.61f, d * 0.195f),
        cornerRadius = CornerRadius(d * 0.095f),
    )
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFEAF5FF), Color(0xFFC4E0FF)),
            start = Offset(d * 0.20f, d * 0.43f),
            end = Offset(d * 0.39f, d * 0.76f),
        ),
        radius = d * 0.175f,
        center = Offset(d * 0.315f, d * 0.60f),
    )
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFEEF7FF), Color(0xFFC9E4FF)),
            start = Offset(d * 0.68f, d * 0.49f),
            end = Offset(d * 0.78f, d * 0.77f),
        ),
        radius = d * 0.165f,
        center = Offset(d * 0.715f, d * 0.615f),
    )

    // Fine highlights give the pale front lobes some volume.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.80f), Color.Transparent),
            center = Offset(d * 0.265f, d * 0.515f),
            radius = d * 0.12f,
        ),
        radius = d * 0.12f,
        center = Offset(d * 0.265f, d * 0.515f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.72f), Color.Transparent),
            center = Offset(d * 0.675f, d * 0.545f),
            radius = d * 0.105f,
        ),
        radius = d * 0.105f,
        center = Offset(d * 0.675f, d * 0.545f),
    )
}

private fun DrawScope.drawCenterDisc(d: Float) {
    val center = Offset(d * 0.50f, d * 0.585f)
    val discRadius = d * 0.142f

    // Blue ambient occlusion around the central white disc.
    repeat(6) { index ->
        val radius = discRadius + d * (0.005f + index * 0.004f)
        drawCircle(
            color = Color(0xFF2D79D8).copy(alpha = 0.070f * (1f - index / 6f)),
            radius = radius,
            center = center.copy(y = center.y + d * 0.004f),
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White,
                0.68f to Color(0xFFF8FBFF),
                1f to Color(0xFFDCEBFB),
            ),
            center = Offset(center.x - d * 0.035f, center.y - d * 0.045f),
            radius = discRadius * 1.45f,
        ),
        radius = discRadius,
        center = center,
    )
    drawCircle(
        color = Color(0xFFB7D6F7).copy(alpha = 0.62f),
        radius = discRadius,
        center = center,
        style = Stroke(width = d * 0.0025f),
    )
}

private fun DrawScope.drawWaterTimeline(
    d: Float,
    nowMs: Float,
    center: Offset,
) {
    // The old play head first collapses into the center, leaving a clear landing target.
    val initialPlay = 1f - smooth(span(nowMs, start = 0, duration = 260))
    if (initialPlay > 0f) {
        drawPlayHead(
            d = d,
            center = center,
            scale = initialPlay,
            alpha = initialPlay,
        )
    }

    // A separate water drop appears well above the cloud and accelerates under gravity. It
    // stretches as it gains speed, then vanishes exactly as the shatter begins.
    val dropProgress = span(nowMs, start = 420, duration = 680)
    val dropAlpha = smooth(span(nowMs, start = 420, duration = 90)) *
        (1f - smooth(span(nowMs, start = 1_035, duration = 90)))
    if (dropAlpha > 0f) {
        val gravity = dropProgress * dropProgress
        val startY = d * 0.022f
        val endY = center.y + d * 0.006f
        val y = lerp(startY, endY, gravity)
        val drift = sin(dropProgress * PI).toFloat() * d * 0.006f
        drawWaterDrop(
            d = d,
            center = Offset(center.x + drift, y),
            scaleX = lerp(1f, 0.78f, smooth(dropProgress)),
            scaleY = lerp(0.82f, 1.42f, smooth(dropProgress)),
            alpha = dropAlpha,
        )
    }

    // Impact ripple on the center disc.
    val ripple = span(nowMs, start = 1_055, duration = 310)
    if (ripple in 0.001f..0.999f) {
        drawCircle(
            color = Color(0xFF4FA7F4).copy(alpha = (1f - smooth(ripple)) * 0.55f),
            radius = d * lerp(0.018f, 0.118f, smooth(ripple)),
            center = center,
            style = Stroke(width = d * 0.004f * (1f - ripple)),
        )
    }

    // The drop bursts radially, pauses at maximum spread, then every fragment reverses course
    // toward a point in a right-facing triangle. The solid mark fades in only as those points
    // meet, so the triangle visibly comes from the water rather than appearing underneath it.
    val explode = smooth(span(nowMs, start = 1_070, duration = 220))
    val gather = smooth(span(nowMs, start = 1_285, duration = 390))
    val fragmentIn = smooth(span(nowMs, start = 1_070, duration = 85))
    val fragmentOut = 1f - smooth(span(nowMs, start = 1_560, duration = 170))
    val fragmentAlpha = fragmentIn * fragmentOut
    if (fragmentAlpha > 0f) {
        TriangleDropletTargets.forEachIndexed { index, target ->
            val angle = index * (PI * 2.0 / TriangleDropletTargets.size) - PI / 2.0
            val burstRadius = d * (0.048f + (index % 4) * 0.010f) * explode
            val burst = Offset(
                x = center.x + cos(angle).toFloat() * burstRadius,
                y = center.y + sin(angle).toFloat() * burstRadius,
            )
            val destination = Offset(
                x = center.x + d * target.first,
                y = center.y + d * target.second,
            )
            val position = Offset(
                x = lerp(burst.x, destination.x, gather),
                y = lerp(burst.y, destination.y, gather),
            )
            val radius = d * (0.0105f + (index % 3) * 0.0020f) *
                lerp(1f, 0.78f, gather)
            drawDropletBubble(position, radius, fragmentAlpha)
        }
    }

    val finalPlay = easeOutBack(span(nowMs, start = 1_535, duration = 315))
    if (finalPlay > 0f) {
        drawPlayHead(
            d = d,
            center = center,
            scale = finalPlay,
            alpha = smooth(span(nowMs, start = 1_535, duration = 210)),
        )
    }

    val glint = bell(span(nowMs, start = 1_720, duration = 240))
    if (glint > 0f) {
        drawCircle(
            color = Color.White.copy(alpha = glint * 0.70f),
            radius = d * 0.009f * glint,
            center = Offset(center.x - d * 0.035f, center.y - d * 0.050f),
        )
    }
}

private fun DrawScope.drawPlayHead(
    d: Float,
    center: Offset,
    scale: Float,
    alpha: Float,
) {
    if (scale <= 0f || alpha <= 0f) return
    val path = Path().apply {
        moveTo(center.x - d * 0.038f * scale, center.y - d * 0.057f * scale)
        lineTo(center.x + d * 0.055f * scale, center.y)
        lineTo(center.x - d * 0.038f * scale, center.y + d * 0.057f * scale)
        close()
    }
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF6BC5FF).copy(alpha = alpha),
            Color(0xFF2F91F4).copy(alpha = alpha),
            Color(0xFF0B6BE5).copy(alpha = alpha),
        ),
        start = Offset(center.x - d * 0.04f, center.y - d * 0.06f),
        end = Offset(center.x + d * 0.055f, center.y + d * 0.06f),
    )
    drawPath(path, brush)
    drawPath(
        path = path,
        brush = brush,
        style = Stroke(
            width = d * 0.012f * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun DrawScope.drawWaterDrop(
    d: Float,
    center: Offset,
    scaleX: Float,
    scaleY: Float,
    alpha: Float,
) {
    val rx = d * 0.048f * scaleX
    val ry = d * 0.048f * scaleY
    val path = Path().apply {
        moveTo(center.x, center.y - ry * 1.35f)
        cubicTo(
            center.x - rx * 0.25f,
            center.y - ry * 0.70f,
            center.x - rx * 0.78f,
            center.y - ry * 0.18f,
            center.x - rx * 0.78f,
            center.y + ry * 0.30f,
        )
        cubicTo(
            center.x - rx * 0.78f,
            center.y + ry * 1.08f,
            center.x + rx * 0.78f,
            center.y + ry * 1.08f,
            center.x + rx * 0.78f,
            center.y + ry * 0.30f,
        )
        cubicTo(
            center.x + rx * 0.78f,
            center.y - ry * 0.18f,
            center.x + rx * 0.25f,
            center.y - ry * 0.70f,
            center.x,
            center.y - ry * 1.35f,
        )
        close()
    }
    drawPath(
        path,
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFB9E8FF).copy(alpha = alpha),
                Color(0xFF55ADF7).copy(alpha = alpha),
                Color(0xFF176ED1).copy(alpha = alpha),
            ),
            start = Offset(center.x - rx, center.y - ry),
            end = Offset(center.x + rx, center.y + ry),
        ),
    )
    drawCircle(
        color = Color.White.copy(alpha = alpha * 0.75f),
        radius = rx * 0.15f,
        center = Offset(center.x - rx * 0.28f, center.y - ry * 0.35f),
    )
}

private fun DrawScope.drawDropletBubble(center: Offset, radius: Float, alpha: Float) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = alpha * 0.92f),
                Color(0xFF8DD5FF).copy(alpha = alpha * 0.88f),
                Color(0xFF277ED4).copy(alpha = alpha * 0.82f),
            ),
            center = Offset(center.x - radius * 0.30f, center.y - radius * 0.35f),
            radius = radius * 1.35f,
        ),
        radius = radius,
        center = center,
    )
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

/** Also restores bars when the user has disabled the Compose splash entirely. */
@Composable
private fun VisibleSystemBarsEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
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

private fun span(nowMs: Float, start: Int, duration: Int): Float =
    ((nowMs - start) / duration).coerceIn(0f, 1f)

private fun smooth(value: Float): Float = value * value * (3f - 2f * value)

private fun easeOutCubic(value: Float): Float = 1f - (1f - value) * (1f - value) * (1f - value)

private fun easeOutBack(value: Float): Float {
    val shifted = value - 1f
    return 1f + BackCubic * shifted * shifted * shifted + BackOvershoot * shifted * shifted
}

private fun bell(value: Float): Float = sin(value * PI).toFloat().coerceAtLeast(0f)

private fun lerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

private const val SplashDurationMs = 2_000
private const val BackOvershoot = 1.70158f
private const val BackCubic = BackOvershoot + 1f
private val CloudBlue = Color(0xFF318AEF)

private val TriangleDropletTargets = listOf(
    -0.040f to -0.052f,
    -0.040f to -0.026f,
    -0.040f to 0.000f,
    -0.040f to 0.026f,
    -0.040f to 0.052f,
    -0.014f to -0.039f,
    -0.014f to -0.013f,
    -0.014f to 0.013f,
    -0.014f to 0.039f,
    0.012f to -0.026f,
    0.012f to 0.000f,
    0.012f to 0.026f,
    0.038f to -0.013f,
    0.038f to 0.013f,
    0.061f to 0.000f,
)

private val SplashTaglines = listOf(
    "水落云起，万象初醒。",
    "一滴入云，清梦徐开。",
    "云生水意，光影成诗。",
    "水漾云舒，万象缓缓而来。",
    "云水初逢，光影正好",
    "一滴落下，云海轻开",
    "水吻云端，万象初生",
    "云藏水意，光影徐来",
    "清水入云，唤醒一场梦",
    "水起微澜，云生万象",
    "一滴清露，落入云间",
    "云水相依，光影成诗",
    "水落无声，云开有梦",
    "云舒水漾，万象缓生",
    "一滴入梦，云起天光",
    "水映流云，光影悠然",
    "云从水起，梦向光生",
    "水落云间，静候花开",
    "清澜轻漾，云端初醒",
    "云水有意，光影无边",
    "一滴清澈，荡开云海",
    "水过云间，岁月生光",
    "云起于水，梦生于光",
    "水色轻盈，云影成诗",
    "云水初醒，万象皆明",
)
