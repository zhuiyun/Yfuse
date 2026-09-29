package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.ActionToast
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.DialogPresence
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayActionRow
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionSpacing
import com.yfuse.core.designsystem.overlayActionBeforeExit
import com.yfuse.core.model.MediaTrailer
import com.yfuse.feature.extras.TrailerLaunchEffect
import com.yfuse.feature.extras.TrailerLauncher
import com.yfuse.feature.extras.rememberTrailerLauncher
import com.yfuse.feature.extras.trailerDescription
import com.yfuse.core.designsystem.ThemeIcon as Icon

/**
 * 预告片 on the phone's detail page: one more key in the row under 播放, there only while the title
 * has a trailer. One trailer plays — or opens its site — at once; several are listed first.
 */
@Stable
internal class DetailTrailers(
    val trailers: List<MediaTrailer>,
    val title: String,
    val launcher: TrailerLauncher,
    val choosing: Boolean,
    private val onChoosing: (Boolean) -> Unit,
) {
    /** The key for [DetailActionDock]'s row, or null when there is nothing to open. */
    val key: DetailActionKey?
        get() {
            if (trailers.isEmpty()) return null
            return DetailActionKey(
                id = DetailActionKeyIds.TRAILER,
                icon = AppIcons.Movie,
                label = "预告片",
                description = trailerKeyDescription(trailers),
                onClick = { if (trailers.size == 1) open(trailers.single()) else onChoosing(true) },
            )
        }

    fun open(trailer: MediaTrailer) {
        onChoosing(false)
        launcher.open(trailer, title)
    }

    fun closeList() = onChoosing(false)
}

/** What a screen reader calls the key: where the one trailer plays, or how many there are. */
internal fun trailerKeyDescription(trailers: List<MediaTrailer>): String =
    when (val only = trailers.singleOrNull()) {
        null -> "预告片，共 ${trailers.size} 个"
        is MediaTrailer.Local -> "播放预告片"
        is MediaTrailer.Remote -> "预告片，${trailerDescription(only)}"
    }

@Composable
internal fun rememberDetailTrailers(
    component: DetailComponent,
    title: String,
): DetailTrailers {
    val trailers by component.trailers.collectAsState()
    val launcher = rememberTrailerLauncher()
    var choosing by remember { mutableStateOf(false) }
    return DetailTrailers(trailers, title, launcher, choosing) { choosing = it }
}

/**
 * The part of 预告片 that is not the key: the list several trailers open, the hand-over to the
 * player, and the word when no app on the phone could open a link. Placed once, over the page.
 */
@Composable
internal fun BoxScope.DetailTrailerHost(
    trailers: DetailTrailers,
    accent: Color,
) {
    TrailerLaunchEffect(trailers.launcher)
    // Held through its exit: the choice starts the trailer while the list is still leaving.
    DialogPresence(trailers.trailers.takeIf { trailers.choosing && it.size > 1 }) { shown ->
        TrailerListDialog(
            title = trailers.title,
            trailers = shown,
            onOpen = trailers::open,
            onDismiss = trailers::closeList,
        )
    }
    ActionToast(message = trailers.launcher.problem, onDismiss = trailers.launcher::problemShown, accent = accent)
}

@Composable
private fun TrailerListDialog(
    title: String,
    trailers: List<MediaTrailer>,
    onOpen: (MediaTrailer) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(title = "预告片", subtitle = title.ifBlank { null }, onClose = onDismiss)
        Column(verticalArrangement = Arrangement.spacedBy(OverlayOptionSpacing)) {
            trailers.forEach { trailer ->
                OverlayActionRow(
                    label = trailer.title,
                    description = trailerDescription(trailer),
                    leadingContent = {
                        Icon(
                            if (trailer is MediaTrailer.Local) AppIcons.Play else AppIcons.Cloud,
                            contentDescription = null,
                            tint = palette.sub,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = overlayActionBeforeExit { onOpen(trailer) },
                )
            }
        }
    }
}
