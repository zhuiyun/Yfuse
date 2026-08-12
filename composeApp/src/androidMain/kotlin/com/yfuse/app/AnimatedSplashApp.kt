package com.yfuse.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LightPalette
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.resolveDark
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.lerp as lerpColor

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
    val reduceMotion by root.themePreferences.reduceMotion.collectAsState()
    val variant by root.themePreferences.splashVariant.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = themeMode.resolveDark(systemDark)

    val context = LocalContext.current
    val systemAnimationsOff = remember(context) { context.systemAnimationsOff() }
    val stillFrame = reduceMotion || systemAnimationsOff
    val splashHistory = remember(context) {
        context.getSharedPreferences(SplashHistoryPreferences, Context.MODE_PRIVATE)
    }
    val firstSplash = rememberSaveable {
        !splashHistory.getBoolean(SplashHistorySeenKey, false)
    }
    val timing = remember(firstSplash, reduceMotion, systemAnimationsOff) {
        splashTiming(
            firstLaunch = firstSplash,
            reduceMotion = reduceMotion,
            systemAnimationsOff = systemAnimationsOff,
        )
    }

    var splashVisible by rememberSaveable {
        mutableStateOf(root.themePreferences.splashAnimation.value)
    }
    LaunchedEffect(splashVisible, firstSplash, splashHistory) {
        if (splashVisible && firstSplash) {
            splashHistory.edit().putBoolean(SplashHistorySeenKey, true).apply()
        }
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
                choreography = variant.choreography,
                dark = dark,
                // The system painted the starting window from the -night resources, so it
                // followed the OS rather than our own setting. Opening on that colour and
                // easing to ours removes the black/white flash the two used to trade on every
                // cold start where the phone and the app disagreed.
                entryDark = systemDark,
                stillFrame = stillFrame,
                timing = timing,
                onFinished = { splashVisible = false },
            )
        }
    }
}

/**
 * Runs one [SplashChoreography] and hands over to the app.
 *
 * The clock is only ever read from draw-phase lambdas, so the whole animation costs
 * recomposition and layout nothing while the app builds itself behind us — the busiest moment
 * in the process's life.
 */
@Composable
private fun AnimatedSplashScreen(
    choreography: SplashChoreography,
    dark: Boolean,
    entryDark: Boolean,
    stillFrame: Boolean,
    timing: SplashTiming,
    onFinished: () -> Unit,
) {
    StatusBarIconStyle(darkIcons = !dark)

    val clock = remember(choreography) { Animatable(0f) }
    val tagline = rememberSaveable { SplashTaglines.random() }

    LaunchedEffect(choreography, stillFrame, timing) {
        if (stillFrame) {
            // "Reduce motion" still gets the brand, just none of the choreography: jump to the
            // resolved frame, hold it briefly, then use at most a short opacity hand-off.
            clock.snapTo(choreography.fadeStartMs)
            delay(timing.stillFrameHoldMs)
        } else {
            clock.animateTo(
                targetValue = choreography.fadeStartMs,
                // The drawings keep their authored timeline; startup policy controls how
                // quickly the clock travels through it.
                animationSpec = tween(timing.motionDurationMs, easing = LinearEasing),
            )
        }
        if (timing.fadeDurationMs == 0) {
            clock.snapTo(choreography.durationMs)
        } else {
            clock.animateTo(
                targetValue = choreography.durationMs,
                animationSpec = tween(timing.fadeDurationMs, easing = LinearEasing),
            )
        }
        onFinished()
    }

    val entryColor = splashBackground(entryDark)
    val targetColor = splashBackground(dark)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // The layer composites the subtree as a group, so the opaque background and the
                // mark cross-fade together instead of blending against each other.
                alpha = 1f - smooth(
                    span(
                        clock.value,
                        choreography.fadeStartMs,
                        choreography.durationMs - choreography.fadeStartMs,
                    ),
                )
            }
            .drawBehind {
                val tint = smooth(span(clock.value, 0f, EntryTintMs))
                drawRect(lerpColor(entryColor, targetColor, tint))
            },
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
                with(choreography) { drawMark(clock.value) }
            }
            Spacer(Modifier.height(16.dp))
            SplashWordmark(
                dark = dark,
                tagline = tagline,
                wordmark = { choreography.wordmark(clock.value) },
                taglineProgress = { choreography.tagline(clock.value) },
            )
        }
    }
}

/** Colourful name resolves first, then the tagline floats into place. */
@Composable
private fun SplashWordmark(
    dark: Boolean,
    tagline: String,
    wordmark: () -> Float,
    taglineProgress: () -> Float,
) {
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
                letterSpacing = 3.sp,
            ),
            // An earlier pass animated letterSpacing and Modifier.blur. letterSpacing
            // re-measured and re-laid-out the text on every frame, and blur built a fresh
            // RenderEffect on every frame while being a silent no-op below API 31, which is
            // most of our minSdk 26 range. A scale reads the same and never leaves the layer.
            modifier = Modifier.graphicsLayer {
                val settled = wordmark()
                alpha = settled
                scaleX = lerp(1.09f, 1f, settled)
                scaleY = lerp(1.04f, 1f, settled)
                translationY = lerp(9f, 0f, settled).dp.toPx()
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
                val settled = taglineProgress()
                alpha = settled
                translationY = lerp(10f, 0f, settled).dp.toPx()
            },
        )
    }
}

/**
 * The one colour the system splash, the activity window and the Compose splash all paint. It is
 * the app's own background, so the last hand-off has no colour step in it either.
 */
internal fun splashBackground(dark: Boolean): Color =
    if (dark) DarkPalette.background else LightPalette.background

/** Whether the OS is in dark mode — the configuration the -night resources resolved against. */
internal fun Resources.isNightMode(): Boolean =
    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Honours the system-wide "remove animations" switch, not only our own accessibility toggle. */
private fun Context.systemAnimationsOff(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

internal data class SplashTiming(
    val motionDurationMs: Int,
    val fadeDurationMs: Int,
    val stillFrameHoldMs: Long,
)

/**
 * The full illustration is a first-launch welcome, not a compulsory two-second gate.
 * Later launches retain the same choreography at a compact media-client pace.
 */
internal fun splashTiming(
    firstLaunch: Boolean,
    reduceMotion: Boolean,
    systemAnimationsOff: Boolean,
): SplashTiming = when {
    systemAnimationsOff -> SplashTiming(
        motionDurationMs = 0,
        fadeDurationMs = 0,
        stillFrameHoldMs = SystemAnimationsOffHoldMs,
    )
    reduceMotion -> SplashTiming(
        motionDurationMs = 0,
        fadeDurationMs = ReducedMotionFadeMs,
        stillFrameHoldMs = ReducedMotionHoldMs,
    )
    firstLaunch -> SplashTiming(
        motionDurationMs = FirstLaunchMotionMs,
        fadeDurationMs = FirstLaunchFadeMs,
        stillFrameHoldMs = 0,
    )
    else -> SplashTiming(
        motionDurationMs = ReturningLaunchMotionMs,
        fadeDurationMs = ReturningLaunchFadeMs,
        stillFrameHoldMs = 0,
    )
}

private const val SplashHistoryPreferences = "yfuse_splash_history"
private const val SplashHistorySeenKey = "has_seen_full_splash"

private const val FirstLaunchMotionMs = 1_000
private const val FirstLaunchFadeMs = 120
private const val ReturningLaunchMotionMs = 420
private const val ReturningLaunchFadeMs = 100
private const val ReducedMotionHoldMs = 260L
private const val ReducedMotionFadeMs = 80
private const val SystemAnimationsOffHoldMs = 180L

private const val EntryTintMs = 300f

/** Punctuation stays off every line. */
private val SplashTaglines = listOf(
    "水落云起，万象初醒",
    "一滴入云，清梦徐开",
    "云生水意，光影成诗",
    "水漾云舒，万象缓缓而来",
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
