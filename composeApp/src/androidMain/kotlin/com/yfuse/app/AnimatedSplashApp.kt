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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.R
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
            .drawBehind {
                val tint = smooth(span(clock.value, 0f, EntryTintMs))
                drawRect(lerpColor(entryColor, targetColor, tint))
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Only the artwork fades. The splash surface remains opaque until this
                    // whole layer is removed, so startup content can never leak through in a
                    // half-composed frame during the hand-off.
                    alpha = splashForegroundAlpha(
                        nowMs = clock.value,
                        fadeStartMs = choreography.fadeStartMs,
                        durationMs = choreography.durationMs,
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Keep launcher Logo and splash artwork paired. 折带展开 belongs to the
            // legacy cloud-player mark; 水火交接 belongs to the current water-fire mark.
            val mark = ImageBitmap.imageResource(
                if (choreography === SplashOne) {
                    R.drawable.cloud_player_logo
                } else {
                    R.drawable.yfuse_mark_ribbon
                },
            )
            Canvas(
                Modifier
                    // B lays the streak column and the mark out across one row 240 units
                    // wide; this is that row. The old square canvas framed a centred
                    // drawing, and the mark alone is only 70% of what goes in here now.
                    //
                    // Taken in from 0.74: at three quarters of the width the mark dominated
                    // a launch that lasts about a second, and left the wordmark under it
                    // looking like a caption rather than the other half of a lockup.
                    .fillMaxWidth(0.64f)
                    .sizeIn(maxWidth = 330.dp)
                    .aspectRatio(1f),
            ) {
                with(choreography) { drawMark(clock.value, mark) }
            }
            Spacer(Modifier.height(18.dp))
            SplashWordmark(wordmark = { choreography.wordmark(clock.value) })
        }
    }
}

/**
 * The name, and nothing under it.
 *
 * This slot has now held two things that were cut. The wordmark used to be filled with a
 * blue-purple gradient of its own and followed by a cloud-and-water tagline, both written
 * for the previous mark. A gradient rule replaced them and was cut in turn: on a 1.2s
 * launch a progress bar is a progress bar, and it invites the reading that something is
 * being waited for. The artwork carries the colour; the name says whose it is.
 */
@Composable
private fun SplashWordmark(
    wordmark: () -> Float,
) {
    Text(
        text = "Yfuse",
        style = TextStyle(
            // The mark's own run, left to right: water into fire. Flat ink was the safe
            // choice while the wordmark sat under a blue-purple logo; under this one it
            // is the only grey thing on the screen.
            brush = WordmarkBrush,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
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
}

/**
 * The one colour the system splash, the activity window and the Compose splash all paint. It is
 * the app's own background, so the last hand-off has no colour step in it either.
 */
internal fun splashBackground(dark: Boolean): Color =
    if (dark) DarkPalette.background else LightPalette.background

/**
 * The launch window must match the first Compose splash frame. When animation is disabled there
 * is no Compose splash, so it instead matches the app theme that will be drawn immediately.
 */
internal fun launchWindowDarkMode(
    splashEnabled: Boolean,
    systemDark: Boolean,
    appDark: Boolean,
): Boolean = if (splashEnabled) systemDark else appDark

/** Fades only the splash artwork; its background intentionally has no alpha transition. */
internal fun splashForegroundAlpha(nowMs: Float, fadeStartMs: Float, durationMs: Float): Float =
    1f - smooth(span(nowMs, fadeStartMs, durationMs - fadeStartMs))

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

private const val FirstLaunchMotionMs = 1_080
private const val FirstLaunchFadeMs = 120
private const val ReturningLaunchMotionMs = 1_080
private const val ReturningLaunchFadeMs = 120
private const val ReducedMotionHoldMs = 260L
private const val ReducedMotionFadeMs = 80
private const val SystemAnimationsOffHoldMs = 180L

private const val EntryTintMs = 300f

/**
 * 水 → 火, the palette from 「Yfuse 水火 Logo」, run across the wordmark in the same
 * direction the mark runs it. Identical in both themes: these are brand colours, and both
 * ends of the ramp clear the light and the dark page.
 */
private val WordmarkBrush = Brush.linearGradient(
    colorStops = arrayOf(
        0f to Color(0xFF22D3EE),
        0.34f to Color(0xFF2563EB),
        0.68f to Color(0xFFF97316),
        1f to Color(0xFFEAB308),
    ),
)
