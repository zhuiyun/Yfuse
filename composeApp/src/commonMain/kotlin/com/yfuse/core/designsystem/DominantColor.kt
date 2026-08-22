package com.yfuse.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Extracts a representative colour from the image at [url], used to tint the
 * detail screen. Returns [fallback] until (or unless) extraction succeeds.
 */
@Composable
expect fun rememberDominantColor(
    url: String?,
    fallback: Color,
): Color

/**
 * Raw colour sampled from the part of the displayed artwork removed by [fadeIntoPage].
 *
 * [targetAspectRatio] describes the actual hero slot, so Android can reproduce
 * `ContentScale.Crop` before sampling. [fadeFraction] is the fraction of that slot occupied by
 * [HeroPageFade]. A null result means that the resolved bitmap has not been sampled yet.
 */
@Composable
expect fun rememberArtworkPageColor(
    url: String?,
    targetAspectRatio: Float,
    fadeFraction: Float,
): Color?

/**
 * [rememberDominantColor], eased into place.
 *
 * The artwork accent reaches a long way — the library hero's content wash, the detail
 * page's play key and section rules, the TMDB sheet — and it changed as a hard cut,
 * because the raw value is a plain [Color] with nothing interpolating it. Every carousel
 * slide repainted half the page in one frame.
 */
@Composable
fun rememberAnimatedDominantColor(
    url: String?,
    fallback: Color,
    durationMillis: Int = Motion.ACCENT,
): Color {
    val extracted = rememberDominantColor(url, fallback)
    // [rememberDominantColor] snaps back to [fallback] the moment the URL changes and only
    // reports a real colour once Palette has run. Easing to that reset would take the page
    // through the fallback on the way to the new artwork — a blue flash between every two
    // slides — so the last real colour is held until the next one lands.
    var target by remember(fallback) { mutableStateOf(extracted) }
    LaunchedEffect(extracted, fallback) {
        if (extracted != fallback) target = extracted
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val eased by animateColorAsState(
        targetValue = target,
        animationSpec =
            tween(
                durationMillis = if (reduceMotion) 0 else durationMillis,
                easing = Motion.Curve,
            ),
        label = "dominantColor",
    )
    return eased
}

/**
 * Harmonizes the extracted artwork colour once, then animates that final UI target.
 *
 * Running [harmonizeArtworkAccent] on every intermediate animation frame is not continuous:
 * its luminance guard changes the number of black/white correction passes at thresholds. A
 * smooth raw-colour animation therefore produced several discrete jumps across the detail page.
 * [identity] keeps one target for the lifetime of a media item while its resolved fallback URL
 * changes, but resets it when the user opens a different item.
 */
@Composable
fun rememberAnimatedArtworkAccent(
    url: String?,
    fallback: Color,
    darkTheme: Boolean,
    identity: Any?,
    durationMillis: Int = Motion.ACCENT,
): Color {
    val extracted = rememberDominantColor(url, fallback)
    var target by remember(identity, fallback, darkTheme) {
        mutableStateOf(harmonizeArtworkAccent(extracted, darkTheme))
    }
    LaunchedEffect(extracted, fallback, darkTheme, identity) {
        if (extracted != fallback) {
            target = harmonizeArtworkAccent(extracted, darkTheme)
        }
    }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val eased by animateColorAsState(
        targetValue = target,
        animationSpec =
            tween(
                durationMillis = if (reduceMotion) 0 else durationMillis,
                easing = Motion.Curve,
            ),
        label = "artworkAccent",
    )
    return eased
}

/**
 * Weight used by the raw page-colour sampler at [fadeProgress] through [HeroPageFade].
 *
 * Squaring the mask coverage concentrates the fit at the lower edge that must meet the page,
 * while still following the exact S-curve used to remove the artwork. The final colour is a
 * direct linear-light average of source pixels; this weight does not brand, brighten, darken,
 * desaturate, or otherwise retone those pixels.
 */
internal fun artworkPageSampleWeight(fadeProgress: Float): Float {
    val progress = fadeProgress.coerceIn(0f, 1f)
    val stops = heroPageFadeMaskStops()
    val coverage =
        stops
            .asList()
            .zipWithNext()
            .firstOrNull { (start, end) -> progress <= end.first && progress >= start.first }
            ?.let { (start, end) ->
                val span = (end.first - start.first).coerceAtLeast(0.0001f)
                val fraction = (progress - start.first) / span
                start.second.alpha + (end.second.alpha - start.second.alpha) * fraction
            }
            ?: stops.last().second.alpha
    return coverage * coverage
}

/**
 * Turns an extracted bitmap swatch into a UI colour rather than trusting the raw pixel.
 * Backdrops routinely produce near-black night scenes or very bright skies; both are valid
 * image colours and poor button/selection colours. The restrained band also keeps every title
 * recognisably inside Yfuse's visual system instead of letting the artwork redesign the app.
 */
fun harmonizeArtworkAccent(
    raw: Color,
    darkTheme: Boolean,
): Color {
    val brandBlend = if (darkTheme) 0.10f else 0.16f
    var result = lerp(raw, Brand.Primary, brandBlend) // design-system: brand-identity
    val minimum = if (darkTheme) 0.10f else 0.08f
    val maximum = if (darkTheme) 0.34f else 0.28f
    repeat(5) {
        val light = result.luminance()
        result =
            when {
                light < minimum -> lerp(result, Color.White, 0.12f)
                light > maximum -> lerp(result, Color.Black, 0.12f)
                else -> return result
            }
    }
    return result
}
