package com.yfuse.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LaunchWaveGate
import com.yfuse.core.designsystem.LightPalette
import com.yfuse.core.designsystem.LightParticleBudget
import com.yfuse.core.designsystem.LocalParticleBudget
import com.yfuse.core.designsystem.LocalParticleLight
import com.yfuse.core.designsystem.LocalRouteVisibilityState
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.MotionTheme
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.StatusBarIconStyle
import com.yfuse.core.designsystem.drawPhaseLight
import com.yfuse.core.designsystem.rememberPhaseLightCount
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
    val reduceMotionSetting by root.themePreferences.reduceMotion.collectAsState()
    val motionTheme by root.themePreferences.motionTheme.collectAsState()
    // 静息 greets with the still frame too: the brand, held briefly, then a short fade.
    val reduceMotion = reduceMotionSetting || motionTheme == MotionTheme.Calm
    val systemDark = isSystemInDarkTheme()
    val dark = themeMode.resolveDark(systemDark)

    val context = LocalContext.current
    val systemAnimationsOff = remember(context) { context.systemAnimationsOff() }
    val stillFrame = reduceMotion || systemAnimationsOff
    val variant = SplashAnimation.forMotion(stillFrame)
    val splashHistory =
        remember(context) {
            context.getSharedPreferences(SPLASH_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        }
    val firstSplash =
        rememberSaveable {
            !splashHistory.getBoolean(SPLASH_HISTORY_SEEN_KEY, false)
        }
    val timing =
        remember(firstSplash, reduceMotion, systemAnimationsOff) {
            splashTiming(
                firstLaunch = firstSplash,
                reduceMotion = reduceMotion,
                systemAnimationsOff = systemAnimationsOff,
            )
        }

    var splashVisible by rememberSaveable {
        mutableStateOf(root.themePreferences.splashAnimation.value)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, splashVisible) {
        if (!splashVisible) return@DisposableEffect onDispose { }
        val observer =
            LifecycleEventObserver { _, event ->
                // Returning from Home should expose the prepared app, without resuming a hidden welcome.
                if (event == Lifecycle.Event.ON_STOP) splashVisible = false
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(splashVisible, firstSplash, splashHistory) {
        if (splashVisible && firstSplash) {
            splashHistory.edit().putBoolean(SPLASH_HISTORY_SEEN_KEY, true).apply()
        }
    }
    // The window was painted in the system's colour so the first splash frame could match it.
    // Once the splash is gone that colour only ever shows through a gap — the arrival fade below,
    // an activity transition — and there it has to be the app's, or a light phone running the
    // dark app blinks white between the splash and the first screen.
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(activity, splashVisible, dark) {
        if (!splashVisible) {
            activity?.window?.setBackgroundDrawable(ColorDrawable(splashBackground(dark).toArgb()))
        }
    }

    // The hand-off used to be a removal: the splash layer left the tree and the first screen was
    // simply already there, at full strength, in the one frame nobody animates. This is the app's
    // own arrival — the same short lift every route gets — driven from an Animatable read only in
    // the draw phase, so it costs the busiest moment in the process's life no recomposition.
    val arrival = remember { Animatable(if (splashVisible) 0f else 1f) }
    // A cold start into 库 hands the page's arrival to 「水火潮涌」: the shell only fades in, and
    // the wave supplies the movement. Scaling the whole page as well would move every row twice.
    val waveHandoff = remember { LaunchWaveGate.pending }
    LaunchedEffect(splashVisible, stillFrame) {
        if (splashVisible || arrival.value >= 1f) return@LaunchedEffect
        if (stillFrame) {
            arrival.snapTo(1f)
        } else if (waveHandoff) {
            arrival.animateTo(1f, tween(WAVE_HANDOFF_FADE_MS, easing = LinearEasing))
        } else {
            arrival.animateTo(1f, tween(Motion.EMPHASIZED, easing = Motion.Curve))
        }
    }

    val appBackground = splashBackground(dark)
    Box(
        Modifier
            .fillMaxSize()
            // Under the arriving app, the colour it arrives on: the splash's last frame. Without it
            // the fade from alpha 0 showed whatever the window held.
            .drawBehind { if (arrival.value < 1f) drawRect(appBackground) },
    ) {
        val parentRouteVisible = LocalRouteVisible.current
        val launched = remember { derivedStateOf { !splashVisible } }
        CompositionLocalProvider(
            LocalRouteVisible provides (parentRouteVisible && !splashVisible),
            LocalRouteVisibilityState provides launched,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Composed and laid out behind the splash but not drawn: nothing in it may be
                    // found by a screen reader either.
                    .then(if (splashVisible) Modifier.clearAndSetSemantics { } else Modifier)
                    .graphicsLayer {
                        // Both halves of the arrival live here. While the splash is still up the
                        // layer is fully transparent, which is also the cheapest possible frame:
                        // the app composes and lays out, and nothing it draws is composited.
                        val entered = arrival.value
                        alpha = entered
                        if (!waveHandoff) {
                            scaleX = SPLASH_HANDOFF_SCALE_FROM + (1f - SPLASH_HANDOFF_SCALE_FROM) * entered
                            scaleY = scaleX
                        }
                    }.drawWithContent {
                        // The splash background stays opaque through its final frame. Keep data and layout
                        // preparation active, but do not record wallpaper, posters and glass underneath it.
                        if (!splashVisible) drawContent()
                    },
            ) {
                App(root)
            }
        }

        // Dialog-based overlays use their own window and can otherwise appear above the
        // Compose splash. Do not compose them until the splash has fully finished.
        if (!splashVisible) {
            overlay()
        }

        if (splashVisible) {
            val particleLight by root.themePreferences.particleLight.collectAsState()
            val particleBudget = remember<LightParticleBudget>(calculation = ::LightParticleBudget)
            CompositionLocalProvider(
                LocalParticleLight provides particleLight,
                LocalParticleBudget provides particleBudget,
            ) {
                AnimatedSplashScreen(
                    variant = variant,
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
    variant: SplashAnimation,
    dark: Boolean,
    entryDark: Boolean,
    stillFrame: Boolean,
    timing: SplashTiming,
    onFinished: () -> Unit,
) {
    StatusBarIconStyle(darkIcons = !dark)
    val finish by rememberUpdatedState(onFinished)

    val choreography = variant.choreography
    // Where on the authored timeline this launch starts: 0 for the whole welcome, later for the
    // compact returning launch. The clock always runs at the speed the beats were drawn for.
    val clockStart =
        if (stillFrame) 0f else splashClockStart(choreography.fadeStartMs, timing.motionDurationMs)
    val clock = remember(choreography) { Animatable(clockStart) }

    LaunchedEffect(choreography, stillFrame, timing) {
        if (stillFrame) {
            // "Reduce motion" still gets the brand, just none of the choreography: jump to the
            // resolved frame, hold it briefly, then use at most a short opacity hand-off.
            clock.snapTo(choreography.fadeStartMs)
            delay(timing.stillFrameHoldMs)
        } else {
            clock.animateTo(
                targetValue = choreography.fadeStartMs,
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

    val lightCount = rememberPhaseLightCount(!stillFrame, enhancedOnly = true)
    val entryColor = splashBackground(entryDark)
    val targetColor = splashBackground(dark)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // The app is already laid out underneath, transparent. A tap here used to land on
                // whatever hero button or tab sat under the finger; now the splash takes every
                // touch, and a tap is the way to skip it.
                .pointerInput(Unit) { detectTapGestures { finish() } }
                .drawBehind {
                    val tint = smooth(span(clock.value, clockStart, ENTRY_TINT_MS))
                    drawRect(lerpColor(entryColor, targetColor, tint))
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Only the artwork fades. The splash surface remains opaque until this
                        // whole layer is removed, so startup content can never leak through in a
                        // half-composed frame during the hand-off.
                        // A launch that joins the timeline part-way fades the half-formed mark in
                        // rather than cutting to it.
                        val joined = if (clockStart > 0f) smooth(span(clock.value, clockStart, JOIN_FADE_MS)) else 1f
                        alpha =
                            joined *
                            splashForegroundAlpha(
                                nowMs = clock.value,
                                fadeStartMs = choreography.fadeStartMs,
                                durationMs = choreography.durationMs,
                            )
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The artwork belongs to the choreography, which belongs to a logo — the cloud
            // ones draw their own shapes and take nothing from here.
            val mark = variant.markResource()?.let { ImageBitmap.imageResource(it) }
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
                drawPhaseLight(
                    androidx.compose.ui.geometry.Rect(
                        size.width * 0.25f,
                        size.height * 0.3f,
                        size.width * 0.75f,
                        size.height * 0.7f,
                    ),
                    (clock.value / choreography.fadeStartMs).coerceIn(0f, 1f),
                    lightCount,
                    if (dark) Color.White else Color.Black,
                )
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
private fun SplashWordmark(wordmark: () -> Float) {
    Text(
        text = "Yfuse",
        style =
            TextStyle(
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
        modifier =
            Modifier.graphicsLayer {
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
internal fun splashBackground(dark: Boolean): Color = if (dark) DarkPalette.background else LightPalette.background

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
internal fun splashForegroundAlpha(
    nowMs: Float,
    fadeStartMs: Float,
    durationMs: Float,
): Float = 1f - smooth(span(nowMs, fadeStartMs, durationMs - fadeStartMs))

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
 * Where a launch joins the authored timeline so that [motionDurationMs] of it plays at 1×.
 *
 * The beats are drawn against their own clock, so a shorter launch never speeds them up — it
 * starts later, on the part where the mark resolves and the name arrives.
 */
internal fun splashClockStart(
    fadeStartMs: Float,
    motionDurationMs: Int,
): Float = (fadeStartMs - motionDurationMs).coerceAtLeast(0f)

/**
 * The full illustration is a first-launch welcome, not a compulsory gate on every launch.
 * Later launches play its closing part — the mark resolving, the name arriving — in 600ms.
 */
internal fun splashTiming(
    firstLaunch: Boolean,
    reduceMotion: Boolean,
    systemAnimationsOff: Boolean,
): SplashTiming =
    when {
        systemAnimationsOff ->
            SplashTiming(
                motionDurationMs = 0,
                fadeDurationMs = 0,
                stillFrameHoldMs = SYSTEM_ANIMATIONS_OFF_HOLD_MS,
            )
        reduceMotion ->
            SplashTiming(
                motionDurationMs = 0,
                fadeDurationMs = REDUCED_MOTION_FADE_MS,
                stillFrameHoldMs = REDUCED_MOTION_HOLD_MS,
            )
        firstLaunch ->
            SplashTiming(
                motionDurationMs = FIRST_LAUNCH_MOTION_MS,
                fadeDurationMs = FIRST_LAUNCH_FADE_MS,
                stillFrameHoldMs = 0,
            )
        else ->
            SplashTiming(
                motionDurationMs = RETURNING_LAUNCH_MOTION_MS,
                fadeDurationMs = RETURNING_LAUNCH_FADE_MS,
                stillFrameHoldMs = 0,
            )
    }

private const val SPLASH_HISTORY_PREFERENCES = "yfuse_splash_history"
private const val SPLASH_HISTORY_SEEN_KEY = "has_seen_full_splash"

private const val FIRST_LAUNCH_MOTION_MS = 1_080
private const val FIRST_LAUNCH_FADE_MS = 120

// The welcome was seen once; 600ms is a greeting where 1.2s on every launch is a wait. The
// clock still runs at 1× — the launch joins the timeline later (see [splashClockStart]).
private const val RETURNING_LAUNCH_MOTION_MS = 480
private const val RETURNING_LAUNCH_FADE_MS = 120
private const val REDUCED_MOTION_HOLD_MS = 260L
private const val REDUCED_MOTION_FADE_MS = 80
private const val SYSTEM_ANIMATIONS_OFF_HOLD_MS = 180L

private const val ENTRY_TINT_MS = 300f

/** How long a launch that joins the timeline part-way takes to fade its artwork in. */
private const val JOIN_FADE_MS = 120f

/**
 * How far back the first screen starts. Restrained on purpose: this is a whole page arriving, not
 * a card, and a deeper zoom on the first thing the user sees reads as the launch not being
 * finished yet.
 */
private const val SPLASH_HANDOFF_SCALE_FROM = 0.98f

/** The page frame's fade when the library wave takes over the arrival. */
private const val WAVE_HANDOFF_FADE_MS = 180

/**
 * 水 → 火, the palette from 「Yfuse 水火 Logo」, run across the wordmark in the same
 * direction the mark runs it. Identical in both themes: these are brand colours, and both
 * ends of the ramp clear the light and the dark page.
 */
private val WordmarkBrush =
    Brush.linearGradient(
        colorStops =
            arrayOf(
                0f to Color(0xFF22D3EE),
                0.34f to Color(0xFF2563EB),
                0.68f to Color(0xFFF97316),
                1f to Color(0xFFEAB308),
            ),
    )

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
