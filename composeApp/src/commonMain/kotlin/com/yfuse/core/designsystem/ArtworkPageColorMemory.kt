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
) {
    private val state = mutableStateOf(initial)

    val value: Color?
        get() = state.value

    /** Only real samples are written, so a loading/fallback frame can never erase the return colour. */
    fun update(color: Color) {
        ArtworkPageColorMemory.write(key, color)
        state.value = color
    }
}

@Composable
fun rememberRetainedArtworkPageColor(key: String): RetainedArtworkPageColor =
    remember(key) {
        RetainedArtworkPageColor(
            initial = ArtworkPageColorMemory.read(key),
            key = key,
        )
    }
