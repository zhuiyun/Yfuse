package com.yfuse.core.designsystem

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

internal class SettingsLocatorRequest(
    val pageTitle: String,
    val itemIndex: Int,
    val label: String,
) {
    var ready by mutableStateOf(false)
    var consumed by mutableStateOf(false)
}

internal val LocalSettingsLocator = staticCompositionLocalOf<SettingsLocatorRequest?> { null }

/** Scroll the lazy section first, then reveal the exact row within a potentially tall card. */
@Composable
internal fun Modifier.settingsSearchAnchor(label: String): Modifier {
    val request = LocalSettingsLocator.current ?: return this
    if (request.label != label) return this
    val selected = request.ready && !request.consumed
    val visible = LocalRouteVisible.current
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(request, selected, visible) {
        if (selected && visible) {
            withFrameNanos { }
            bring.bringIntoView()
            delay(1000)
            request.consumed = true
        }
    }
    return bringIntoViewRequester(bring).returnLocator(selected && visible)
}
