package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Keeps the last resolved poster-to-page colour while a source screen is temporarily removed
 * from composition by navigation.
 *
 * This is deliberately process-local rather than persisted as a preference: it exists only to
 * make a return from detail render the exact page colour it had before navigation, instead of
 * flashing the app theme background while the same artwork is decoded and sampled again.
 */
private object ArtworkPageColorMemory {
    private val colors = mutableMapOf<String, Color>()

    fun read(key: String): Color? = colors[key]

    fun write(
        key: String,
        color: Color,
    ) {
        colors[key] = color
    }
}

@Stable
class RetainedArtworkPageColor internal constructor(
    initial: Color?,
    private val key: String,
    private val darkTheme: Boolean,
) {
    /** Raw sampled artwork colour stays untouched so changing appearance never compounds a tint. */
    private val rawState = mutableStateOf(initial)

    /**
     * The colour pages actually paint. Only this opaque fade target is protected; the artwork
     * itself is never brightened/darkened, so the adjustment is confined to the lower dissolve.
     */
    val value: Color?
        get() = rawState.value?.let { artworkPageSurface(it, darkTheme) }

    /** Only real samples are written, so a loading/fallback frame can never erase the return colour. */
    fun update(color: Color) {
        ArtworkPageColorMemory.write(key, color)
        rawState.value = color
    }
}

@Composable
fun rememberRetainedArtworkPageColor(key: String): RetainedArtworkPageColor {
    // Read this before a poster-aware ArtworkPageTheme is installed. At Home/Library/detail
    // call sites it represents the user's actual light/dark appearance, not the poster colour.
    val darkTheme = LocalPalette.current.isDark
    return remember(key, darkTheme) {
        RetainedArtworkPageColor(
            initial = ArtworkPageColorMemory.read(key),
            key = key,
            darkTheme = darkTheme,
        )
    }
}
