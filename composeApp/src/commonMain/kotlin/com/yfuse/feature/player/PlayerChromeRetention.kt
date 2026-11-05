package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

private class RetainedLine<T>(
    var value: T?,
)

/**
 * The last value that was actually there, so a line can finish its exit still saying something.
 *
 * The cache is written from a [SideEffect] rather than during composition: composition can be
 * abandoned and replayed, and filling a cache from it is a side effect either way. A plain holder
 * rather than a state, for the same reason [ChromeContent] uses one — remembering the outgoing
 * value must not cost the bar a second pass.
 */
@Composable
internal fun <T : Any> rememberLastPlayerChromeLine(value: T?): T? {
    val retained = remember { RetainedLine(value) }
    SideEffect { if (value != null) retained.value = value }
    return value ?: retained.value
}
