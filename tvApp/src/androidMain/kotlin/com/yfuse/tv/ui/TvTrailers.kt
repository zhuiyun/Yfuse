package com.yfuse.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.model.MediaTrailer
import com.yfuse.feature.extras.TrailerLauncher
import com.yfuse.feature.extras.trailerDescription
import com.yfuse.tv.focus.requestFocusWhenAttached
import com.yfuse.tv.focus.tvFocusScope
import kotlinx.coroutines.delay

/**
 * 预告片 on the television's detail page, as the phone's key does it: a file plays in the player
 * the page's 播放 uses, a video site's link opens in the app the television has for it. The
 * preview on the hero gives way first — the trailer needs the decoder it holds.
 */
internal fun TrailerLauncher.openOnTv(
    trailer: MediaTrailer,
    ownerTitle: String,
) {
    stopTrailerPreview()
    open(trailer, ownerTitle)
}

/** Why a link did not open, said under the hero's keys for a while and then let go. */
@Composable
internal fun TvTrailerNoticeTimeout(launcher: TrailerLauncher) {
    val problem = launcher.problem ?: return
    LaunchedEffect(problem) {
        delay(TRAILER_NOTICE_MS)
        launcher.problemShown()
    }
}

/** The list several trailers open: each one a row, first one focused. */
@Composable
internal fun TvTrailerListDialog(
    title: String,
    trailers: List<MediaTrailer>,
    focusMemory: TvUiFocusMemory,
    onOpen: (MediaTrailer) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusScope = "detail:trailers"
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenAttached() }
    GlassDialog(onDismiss = onDismiss, maxWidth = 720.dp, contentPadding = 26.dp) {
        Column(
            Modifier.fillMaxWidth().tvFocusScope(trapFocus = true),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("预告片", color = TvOnSurface, fontSize = TvType.section, fontWeight = FontWeight.ExtraBold)
            Text(title, color = TvOnSurfaceMuted, fontSize = TvType.caption)
            trailers.forEachIndexed { index, trailer ->
                TvSettingRow(
                    title = trailer.title,
                    value = "",
                    stableId = "trailers:$index",
                    focusMemory = focusMemory,
                    onClick = { onOpen(trailer) },
                    icon = if (trailer is MediaTrailer.Local) AppIcons.Play else AppIcons.Cloud,
                    focusScope = focusScope,
                    subtitle = trailerDescription(trailer),
                    focusRequester = firstRequester.takeIf { index == 0 },
                )
            }
        }
    }
}

private const val TRAILER_NOTICE_MS = 4_000L
